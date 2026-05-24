package com.aimod.ai.llm;

import com.aimod.util.DevLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class LLMService {
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final int HEALTH_CHECK_MAX_TOKENS = 1;
    
    // Retry constants
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000L;
    private static final long RETRY_MAX_DELAY_MS = 30000L;
    
    // HTTP client for connection reuse
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    // Improved health check caching with AtomicReference
    private static final AtomicReference<HealthCheckResult> HEALTH_CHECK_CACHE = 
            new AtomicReference<>(new HealthCheckResult("", 0L, false));

    private String apiUrl;
    private String apiKey;
    private String model;
    private int maxTokens;
    private double temperature;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private boolean streamResponses;
    private boolean modelHealthCheck;
    private int healthCheckIntervalMs;
    private int healthCheckTimeoutMs;
    private int maxRetries;
    private RateLimiter rateLimiter;

    /** Simple container for health check cache */
    private static class HealthCheckResult {
        final String key;
        final long checkedAtMs;
        final boolean isOk;
        
        HealthCheckResult(String key, long checkedAtMs, boolean isOk) {
            this.key = key;
            this.checkedAtMs = checkedAtMs;
            this.isOk = isOk;
        }
    }

    /**
     * Sliding window rate limiter.
     * Tracks request timestamps in a circular buffer.
     * When at capacity, blocks the caller until the oldest request expires.
     */
    static class RateLimiter {
        private final long windowMs;
        private final int maxRequests;
        private final long[] timestamps;
        private int head;
        private int count;

        RateLimiter(int maxRequestsPerMinute) {
            this(maxRequestsPerMinute, 60_000L);
        }

        RateLimiter(int maxRequestsPerMinute, long windowMs) {
            this.windowMs = windowMs;
            this.maxRequests = Math.max(1, maxRequestsPerMinute);
            this.timestamps = new long[this.maxRequests];
        }

        synchronized void acquire() throws InterruptedException {
            long now = System.currentTimeMillis();
            while (count > 0 && now - timestamps[head] > windowMs) {
                head = (head + 1) % maxRequests;
                count--;
            }
            if (count >= maxRequests) {
                long oldestTs = timestamps[head];
                long waitMs = windowMs - (now - oldestTs);
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                }
                now = System.currentTimeMillis();
                while (count > 0 && now - timestamps[head] > windowMs) {
                    head = (head + 1) % maxRequests;
                    count--;
                }
            }
            int tail = (head + count) % maxRequests;
            timestamps[tail] = now;
            count++;
        }

        synchronized int getCount() {
            long now = System.currentTimeMillis();
            while (count > 0 && now - timestamps[head] > windowMs) {
                head = (head + 1) % maxRequests;
                count--;
            }
            return count;
        }
    }

    public LLMService() {
        // 从配置文件读取API设置
        this.apiUrl = com.aimod.config.ModConfig.getApiUrl();
        this.apiKey = com.aimod.config.ModConfig.getApiKey();
        this.model = com.aimod.config.ModConfig.getModelName();
        this.maxTokens = com.aimod.config.ModConfig.getMaxTokens();
        this.temperature = com.aimod.config.ModConfig.getTemperature();
        this.connectTimeoutMs = com.aimod.config.ModConfig.getConnectTimeoutSeconds() * 1000;
        this.readTimeoutMs = com.aimod.config.ModConfig.getReadTimeoutSeconds() * 1000;
        this.streamResponses = com.aimod.config.ModConfig.getStreamResponses();
        this.modelHealthCheck = com.aimod.config.ModConfig.getModelHealthCheck();
        this.healthCheckIntervalMs = com.aimod.config.ModConfig.getHealthCheckIntervalSeconds() * 1000;
        this.healthCheckTimeoutMs = com.aimod.config.ModConfig.getHealthCheckTimeoutSeconds() * 1000;
        this.maxRetries = com.aimod.config.ModConfig.getMaxRetries();
        boolean rateLimitEnabled = com.aimod.config.ModConfig.getRateLimitEnabled();
        if (rateLimitEnabled) {
            int requestsPerMinute = com.aimod.config.ModConfig.getRateLimitRequestsPerMinute();
            this.rateLimiter = new RateLimiter(requestsPerMinute);
            DevLog.info("LLM_RATE_LIMIT", "enabled=true, requestsPerMinute={}", requestsPerMinute);
        } else {
            this.rateLimiter = null;
            DevLog.info("LLM_RATE_LIMIT", "enabled=false");
        }
        DevLog.info("LLM_CONFIG", "apiUrl={}, model={}, maxTokens={}, temperature={}, connectTimeoutMs={}, readTimeoutMs={}, streamResponses={}, modelHealthCheck={}, healthCheckIntervalMs={}, healthCheckTimeoutMs={}, maxRetries={}, rateLimitEnabled={}, hasApiKey={}",
                apiUrl, model, maxTokens, temperature, connectTimeoutMs, readTimeoutMs,
                streamResponses, modelHealthCheck, healthCheckIntervalMs, healthCheckTimeoutMs,
                maxRetries, rateLimitEnabled,
                apiKey != null && !apiKey.isBlank());
    }

    public LLMResponse parseCommand(String naturalLanguageCommand) {
        return parseCommand(naturalLanguageCommand, null);
    }

    public LLMResponse parseCommand(String naturalLanguageCommand, String worldContext) {
        DevLog.info("LLM_REQUEST", "command={}", DevLog.compact(naturalLanguageCommand));
        if (apiKey == null || apiKey.isBlank()) {
            DevLog.warn("LLM_FALLBACK", "apiKey is empty; skipping HTTP call and using local planner fallback");
            return LLMResponse.failure("API key is empty; using local planner fallback");
        }
        try {
            if (!isModelAvailable()) {
                return LLMResponse.failure("Model health check failed");
            }
            String prompt = buildPrompt(naturalLanguageCommand, worldContext);
            DevLog.info("LLM_PROMPT", "{}", DevLog.compact(prompt));
            String response = callLLMApi(prompt);
            return parseResponse(response);
        } catch (java.net.SocketTimeoutException e) {
            DevLog.warn("LLM_TIMEOUT", "api call timed out after readTimeoutMs={} or connectTimeoutMs={}: {}",
                    readTimeoutMs, connectTimeoutMs, e.getMessage());
            return LLMResponse.failure("API call timed out: " + e.getMessage());
        } catch (Exception e) {
            DevLog.warn("LLM_ERROR", "api call failed: {}", e.getMessage());
            return LLMResponse.failure("API call failed: " + e.getMessage());
        }
    }

    /** Lightweight prompt for incremental replanning. */
    public LLMResponse sendPrompt(String prompt) {
        if (apiKey == null || apiKey.isBlank()) return LLMResponse.failure("No API key");
        try {
            if (!isModelAvailable()) return LLMResponse.failure("Model health check failed");
            String response = callLLMApi(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            return LLMResponse.failure("Prompt failed: " + e.getMessage());
        }
    }

    private boolean isModelAvailable() {
        if (!modelHealthCheck) {
            DevLog.info("LLM_HEALTH_SKIP", "reason=disabled");
            return true;
        }

        String healthKey = apiUrl + "|" + model;
        long now = System.currentTimeMillis();
        HealthCheckResult cached = HEALTH_CHECK_CACHE.get();
        if (cached.isOk && healthKey.equals(cached.key) &&
                healthCheckIntervalMs > 0 && now - cached.checkedAtMs < healthCheckIntervalMs) {
            DevLog.info("LLM_HEALTH_CACHE", "status=ok, ageMs={}, intervalMs={}",
                    now - cached.checkedAtMs, healthCheckIntervalMs);
            return true;
        }

        DevLog.info("LLM_HEALTH_START", "url={}, model={}, maxTokens={}, timeoutMs={}",
                apiUrl, model, HEALTH_CHECK_MAX_TOKENS, healthCheckTimeoutMs);
        try {
            long healthStartMs = System.currentTimeMillis();
            callLLMApiDirect("ping", HEALTH_CHECK_MAX_TOKENS, healthCheckTimeoutMs);
            long healthElapsedMs = System.currentTimeMillis() - healthStartMs;
            HEALTH_CHECK_CACHE.set(new HealthCheckResult(healthKey, now, true));
            DevLog.info("LLM_HEALTH_OK", "elapsedMs={}", healthElapsedMs);
            return true;
        } catch (Exception e) {
            HEALTH_CHECK_CACHE.set(new HealthCheckResult(healthKey, now, false));
            DevLog.warn("LLM_HEALTH_FAIL", "error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建 LLM 提示词
     */
    private String buildPrompt(String command) {
        return buildPrompt(command, null);
    }

    /**
     * 构建 LLM 提示词（带世界上下文）
     */
    private String buildPrompt(String command, String worldContext) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are an AI assistant in Minecraft 1.21.1. You control a bot that executes tasks for players.\n");
        prompt.append("Given a natural language command, respond with a JSON object containing an \"actions\" array.\n\n");

        prompt.append("## Available Action Types\n\n");

        prompt.append("### Movement\n");
        prompt.append("- move_to: {\"type\": \"move_to\", \"x\": int, \"y\": int, \"z\": int, \"speed\": 1.0}\n");
        prompt.append("- follow: {\"type\": \"follow\", \"player\": \"player_name\"}\n\n");

        prompt.append("### Block Operations\n");
        prompt.append("- break_block: {\"type\": \"break_block\", \"x\": int, \"y\": int, \"z\": int}\n");
        prompt.append("- place_block: {\"type\": \"place_block\", \"x\": int, \"y\": int, \"z\": int, \"block_id\": \"minecraft:stone\"}\n");
        prompt.append("- mine: {\"type\": \"mine\", \"block_id\": \"minecraft:diamond_ore\", \"count\": 1, \"radius\": 32}\n");
        prompt.append("- gather: {\"type\": \"gather\", \"resource_type\": \"WOOD\", \"count\": 16, \"radius\": 32}\n");
        prompt.append("  Resource types: WOOD, STONE, DIRT, SAND, COBBLESTONE\n\n");

        prompt.append("### Crafting & Inventory\n");
        prompt.append("- craft: {\"type\": \"craft\", \"item_id\": \"minecraft:diamond_sword\", \"count\": 1}\n");
        prompt.append("- give_item: {\"type\": \"give_item\", \"item_id\": \"minecraft:diamond\", \"count\": 1, \"player\": \"player_name\"}\n");
        prompt.append("- require_items: {\"type\": \"require_items\", \"items\": [{\"item_id\": \"minecraft:diamond\", \"count\": 24}]}\n");
        prompt.append("- equip: {\"type\": \"equip\", \"item_id\": \"minecraft:diamond_pickaxe\", \"slot\": \"MAINHAND\"}\n");
        prompt.append("  Slots: HEAD, CHEST, LEGS, FEET, MAINHAND, OFFHAND\n\n");

        prompt.append("### Interaction\n");
        prompt.append("- interact: {\"type\": \"interact\", \"interact_type\": \"CRAFTING_TABLE\"}\n");
        prompt.append("  Types: CRAFTING_TABLE, FURNACE, CHEST, ANVIL, ENCHANTING_TABLE, BREWING_STAND\n\n");

        prompt.append("### Combat\n");
        prompt.append("- attack: {\"type\": \"attack\", \"target\": \"zombie\"}\n\n");

        prompt.append("### Communication\n");
        prompt.append("- say: {\"type\": \"say\", \"message\": \"Hello!\"}\n");
        prompt.append("- wait: {\"type\": \"wait\", \"seconds\": 5}\n\n");

        prompt.append("## Important Rules\n");
        prompt.append("1. Always use \"mine\" action for mining ores (not break_block)\n");
        prompt.append("2. Use \"gather\" for collecting wood, stone, etc.\n");
        prompt.append("3. Use \"interact\" before crafting to ensure crafting table is available\n");
        prompt.append("4. Use \"equip\" to wear armor or hold tools\n");
        prompt.append("5. Break complex tasks into simple sequential actions\n");
        prompt.append("6. Always include \"say\" actions to report progress\n\n");

        if (worldContext != null && !worldContext.isBlank()) {
            prompt.append("## World Context\n");
            prompt.append(worldContext).append("\n\n");
        }

        prompt.append("## Player Command\n");
        prompt.append(command).append("\n\n");

        prompt.append("## Response Format\n");
        prompt.append("Respond ONLY with a JSON object: {\"actions\": [...]}\n");
        prompt.append("Do not include any other text or explanation.\n");

        return prompt.toString();
    }

    private String callLLMApi(String prompt) throws IOException, InterruptedException {
        return callWithRetry(prompt, maxTokens, readTimeoutMs);
    }

    private String callWithRetry(String prompt, int maxTokens, int timeoutMs) throws IOException, InterruptedException {
        int retries = Math.max(0, maxRetries);
        if (retries == 0) {
            return callLLMApiDirect(prompt, maxTokens, timeoutMs);
        }
        long delay = RETRY_BASE_DELAY_MS;
        for (int attempt = 0; ; attempt++) {
            try {
                return callLLMApiDirect(prompt, maxTokens, timeoutMs);
            } catch (IOException e) {
                if (attempt >= retries || !isRetryable(e)) {
                    throw e;
                }
                long jitter = (long) (delay * 0.25 * (Math.random() * 2.0 - 1.0));
                long sleepMs = Math.max(1, delay + jitter);
                DevLog.info("LLM_RETRY", "attempt={}/{}, sleepMs={}, error={}",
                        attempt + 1, retries, sleepMs, e.getMessage());
                Thread.sleep(sleepMs);
                delay = Math.min(delay * 2, RETRY_MAX_DELAY_MS);
            }
        }
    }

    private static boolean isRetryable(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) return false;
        if (msg.contains("timed out") || msg.contains("timeout")
                || msg.contains("refused") || msg.contains("reset")) {
            return true;
        }
        return msg.contains("status 429") || msg.contains("status 5");
    }

    private String callLLMApiDirect(String prompt, int maxTokens, int timeoutMs) throws IOException, InterruptedException {
        if (rateLimiter != null) {
            rateLimiter.acquire();
        }
        long startedAt = System.nanoTime();

        URI uri = URI.create(apiUrl);
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", maxTokens);
        requestBody.addProperty("temperature", temperature);

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "You are a Minecraft AI assistant that responds only with JSON actions.");
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        requestBody.add("messages", messages);

        if (streamResponses && timeoutMs > 10000) {
            requestBody.addProperty("stream", true);
        }

        String jsonBody = requestBody.toString();
        DevLog.info("LLM_REQUEST_BODY", "{}", DevLog.compact(jsonBody));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int responseCode = response.statusCode();
        String responseBody = response.body();

        DevLog.info("LLM_RESPONSE_CODE", "code={}, elapsedMs={}", responseCode, elapsedMs(startedAt));

        if (responseCode != 200) {
            DevLog.warn("LLM_ERROR_RESPONSE", "code={}, body={}", responseCode, DevLog.compact(responseBody));
            throw new IOException("API returned status " + responseCode + ": " + responseBody);
        }

        if (requestBody.has("stream") && requestBody.get("stream").getAsBoolean()) {
            return readSSEStream(responseBody, startedAt);
        }

        DevLog.info("LLM_HTTP_RESPONSE", "elapsedMs={}, body={}", elapsedMs(startedAt), DevLog.compact(responseBody));
        return responseBody;
    }

    private String readSSEStream(String responseBody, long startedAt) {
        // Process SSE format from the response body
        BufferedReader reader = new BufferedReader(new StringReader(responseBody));
        StringBuilder content = new StringBuilder();

        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    try {
                        JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                        appendStreamingChoice(chunk, content);
                    } catch (Exception e) {
                        DevLog.warn("LLM_STREAM_PARSE_ERROR", "data={}", DevLog.compact(data));
                    }
                }
            }
        } catch (IOException e) {
            DevLog.warn("LLM_STREAM_READ_ERROR", "{}", e.getMessage());
        }

        if (content.length() == 0) {
            DevLog.warn("LLM_STREAM_EMPTY", "stream returned no content");
            // Return the original response body as fallback
            DevLog.info("LLM_HTTP_RESPONSE", "elapsedMs={}, body={}", elapsedMs(startedAt), DevLog.compact(responseBody));
            return responseBody;
        }

        DevLog.info("LLM_STREAM_DONE", "elapsedMs={}, content={}", elapsedMs(startedAt), DevLog.compact(content.toString()));
        JsonObject message = new JsonObject();
        message.addProperty("content", content.toString());
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private void appendStreamingChoice(JsonObject chunk, StringBuilder content) {
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                ? choice.getAsJsonObject("delta")
                : null;
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message")
                : null;
        JsonObject source = delta != null ? delta : message;
        if (source == null) {
            return;
        }

        appendIfPresent(source, "reasoning_content", "LLM_STREAM_REASONING", null);
        appendIfPresent(source, "reasoning", "LLM_STREAM_REASONING", null);
        appendIfPresent(source, "content", "LLM_STREAM_CHUNK", content);
    }

    private void appendIfPresent(JsonObject object, String key, String tag, StringBuilder target) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return;
        }
        String value = object.get(key).getAsString();
        if (value.isEmpty()) {
            return;
        }
        DevLog.info(tag, "{}", DevLog.compact(value));
        if (target != null) {
            target.append(value);
        }
    }

    private LLMResponse parseResponse(String response) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                String content = message.get("content").getAsString();
                DevLog.info("LLM_RESPONSE", "content={}", DevLog.compact(content));
                
                LLMResponse llmResponse = LLMResponse.success(content);
                // 解析内容中的动作
                List<String> actions = parseActionsFromContent(content);
                llmResponse.setActions(actions);
                DevLog.info("LLM_ACTIONS", "count={}, actions={}", actions.size(), DevLog.compact(actions.toString()));
                return llmResponse;
            }
        } catch (Exception e) {
            DevLog.warn("LLM_PARSE_ERROR", "failed to parse provider response: {}", e.getMessage());
        }
        return LLMResponse.failure("Failed to parse LLM response");
    }

    private List<String> parseActionsFromContent(String content) {
        List<String> actions = new ArrayList<>();
        // 简单解析：尝试提取JSON数组
        try {
            if (content.contains("{") && content.contains("}")) {
                int start = content.indexOf("{");
                int end = content.lastIndexOf("}") + 1;
                String jsonStr = content.substring(start, end);
                JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                if (json.has("actions")) {
                    JsonArray actionsArray = json.getAsJsonArray("actions");
                    for (int i = 0; i < actionsArray.size(); i++) {
                        actions.add(actionsArray.get(i).toString());
                    }
                }
            }
        } catch (Exception e) {
            DevLog.warn("LLM_ACTION_PARSE_ERROR", "failed to parse actions from content: {}", e.getMessage());
        }
        return actions;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
