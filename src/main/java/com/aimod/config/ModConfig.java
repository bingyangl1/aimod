package com.aimod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ModConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> API_URL = BUILDER
            .comment(
                "LLM API endpoint URL",
                "Default: OpenAI API. Change this for other providers:",
                "- Claude: https://api.anthropic.com/v1/messages",
                "- Ollama: http://localhost:11434/api/generate",
                "- LM Studio: http://localhost:1234/v1/chat/completions"
            )
            .define("apiUrl", "https://api.deepseek.com/chat/completions");

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment(
                "API Key for LLM service",
                "For OpenAI: sk-...",
                "For local models (Ollama/LM Studio): leave empty or use 'ollama'"
            )
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> MODEL_NAME = BUILDER
            .comment(
                "Model name to use",
                "OpenAI: gpt-3.5-turbo, gpt-4, gpt-4o",
                "Claude: claude-3-sonnet-20240229",
                "Ollama: llama3, qwen2, mistral"
            )
            .define("modelName", "deepseek-v4-pro");

    public static final ModConfigSpec.ConfigValue<Integer> MAX_TOKENS = BUILDER
            .comment("Maximum tokens for LLM response (higher = more detailed actions)")
            .defineInRange("maxTokens", 1024, 256, 1280000);

    public static final ModConfigSpec.ConfigValue<Double> TEMPERATURE = BUILDER
            .comment("LLM temperature (0.0 = deterministic, 1.0 = creative)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);

    public static final ModConfigSpec.ConfigValue<Integer> CONNECT_TIMEOUT_SECONDS = BUILDER
            .comment(
                "HTTP connect timeout in seconds",
                "Keep this relatively short; it is used to detect unreachable networks or refused connections."
            )
            .defineInRange("connectTimeoutSeconds", 10, 1, 120);

    public static final ModConfigSpec.ConfigValue<Integer> READ_TIMEOUT_SECONDS = BUILDER
            .comment(
                "HTTP read timeout in seconds",
                "Deep-thinking models may need several minutes before returning a final answer.",
                "If streamResponses is true and the provider streams chunks, each chunk resets the read timeout."
            )
            .defineInRange("readTimeoutSeconds", 600, 30, 3600);

    public static final ModConfigSpec.ConfigValue<Boolean> STREAM_RESPONSES = BUILDER
            .comment(
                "Request OpenAI-compatible streaming responses",
                "Useful during development because streamed chunks prove the model is working instead of unreachable."
            )
            .define("streamResponses", false);

    public static final ModConfigSpec.ConfigValue<Boolean> MODEL_HEALTH_CHECK = BUILDER
            .comment(
                "Run a tiny model availability check before expensive task planning requests",
                "The check uses max_tokens=1 and is cached by healthCheckIntervalSeconds to avoid wasting tokens."
            )
            .define("modelHealthCheck", true);

    public static final ModConfigSpec.ConfigValue<Integer> HEALTH_CHECK_INTERVAL_SECONDS = BUILDER
            .comment(
                "How long to reuse a successful model health check",
                "Set to 0 to check before every request, or increase to reduce token usage."
            )
            .defineInRange("healthCheckIntervalSeconds", 300, 0, 86400);

    public static final ModConfigSpec.ConfigValue<Integer> HEALTH_CHECK_TIMEOUT_SECONDS = BUILDER
            .comment(
                "Read timeout for the tiny model health check",
                "Keep this shorter than the main read timeout because the health check asks for only one token."
            )
            .defineInRange("healthCheckTimeoutSeconds", 20, 1, 300);

    public static final ModConfigSpec.ConfigValue<Boolean> RATE_LIMIT_ENABLED = BUILDER
            .comment(
                "Enable rate limiting for LLM API requests to avoid hitting provider rate limits",
                "When enabled, the bot will throttle requests to rateLimitRequestsPerMinute per minute."
            )
            .define("rateLimitEnabled", false);

    public static final ModConfigSpec.ConfigValue<Integer> RATE_LIMIT_REQUESTS_PER_MINUTE = BUILDER
            .comment(
                "Maximum number of LLM API requests per minute when rate limiting is enabled",
                "Set to match your LLM provider's rate limits (e.g., 30 for most OpenAI tiers)."
            )
            .defineInRange("rateLimitRequestsPerMinute", 30, 1, 600);

    public static final ModConfigSpec.ConfigValue<Integer> MAX_RETRIES = BUILDER
            .comment(
                "Maximum number of retries for transient API errors (timeouts, 429, 5xx)",
                "Uses exponential backoff with jitter. Set to 0 to disable retries."
            )
            .defineInRange("maxRetries", 3, 0, 10);

    public static final ModConfigSpec.ConfigValue<Boolean> ALLOW_DEV_CREATIVE_ITEM_PROVISIONING = BUILDER
            .comment(
                "Development escape hatch: allow give_item to create missing items from thin air",
                "Keep false when testing real survival behavior. When false, give_item only transfers items already in the bot inventory."
            )
            .define("allowDevCreativeItemProvisioning", false);

    public static final ModConfigSpec.ConfigValue<Integer> MAX_BOTS = BUILDER
            .comment("Maximum number of AI bots allowed simultaneously")
            .defineInRange("maxBots", 10, 1, 50);

    public static final ModConfigSpec.ConfigValue<Integer> DEFAULT_SCAN_RADIUS = BUILDER
            .comment("Default block scanning radius for WorldScanner")
            .defineInRange("defaultScanRadius", 32, 8, 128);

    public static final ModConfigSpec.ConfigValue<String> BOT_SKIN_URL = BUILDER
            .comment(
                "Custom skin URL for AI bots. Leave empty for default Steve skin.",
                "Supports direct PNG URLs. The skin is downloaded asynchronously."
            )
            .define("botSkinUrl", "");

    public static final ModConfigSpec.ConfigValue<Boolean> AUTO_REPLENISH = BUILDER
            .comment("Automatically refill held item stacks from inventory")
            .define("autoReplenish", true);

    public static final ModConfigSpec.ConfigValue<Boolean> AUTO_REPLACE_TOOL = BUILDER
            .comment("Automatically replace nearly-broken tools")
            .define("autoReplaceTool", true);

    public static final ModConfigSpec.ConfigValue<Boolean> AUTO_FISH = BUILDER
            .comment("Automatically fish when holding a fishing rod")
            .define("autoFish", false);

    public static final ModConfigSpec.ConfigValue<Integer> HUNGER_THRESHOLD = BUILDER
            .comment("Food level below which the bot auto-eats")
            .defineInRange("hungerThreshold", 14, 0, 20);

    public static final ModConfigSpec.ConfigValue<Double> MOVEMENT_SPEED = BUILDER
            .comment("Bot movement speed multiplier (0.3 = default player walk speed)")
            .defineInRange("movementSpeed", 0.3, 0.1, 1.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static String getApiUrl() {
        return API_URL.get();
    }

    public static String getApiKey() {
        return API_KEY.get();
    }

    public static String getModelName() {
        return MODEL_NAME.get();
    }

    public static int getMaxTokens() {
        return MAX_TOKENS.get();
    }

    public static double getTemperature() {
        return TEMPERATURE.get();
    }

    public static int getConnectTimeoutSeconds() {
        return CONNECT_TIMEOUT_SECONDS.get();
    }

    public static int getReadTimeoutSeconds() {
        return READ_TIMEOUT_SECONDS.get();
    }

    public static boolean getStreamResponses() {
        return STREAM_RESPONSES.get();
    }

    public static boolean getModelHealthCheck() {
        return MODEL_HEALTH_CHECK.get();
    }

    public static int getHealthCheckIntervalSeconds() {
        return HEALTH_CHECK_INTERVAL_SECONDS.get();
    }

    public static int getHealthCheckTimeoutSeconds() {
        return HEALTH_CHECK_TIMEOUT_SECONDS.get();
    }

    public static boolean getRateLimitEnabled() {
        return RATE_LIMIT_ENABLED.get();
    }

    public static int getRateLimitRequestsPerMinute() {
        return RATE_LIMIT_REQUESTS_PER_MINUTE.get();
    }

    public static int getMaxRetries() {
        return MAX_RETRIES.get();
    }

    public static boolean getAllowDevCreativeItemProvisioning() {
        return ALLOW_DEV_CREATIVE_ITEM_PROVISIONING.get();
    }

    public static int getMaxBots() { return MAX_BOTS.get(); }
    public static int getDefaultScanRadius() { return DEFAULT_SCAN_RADIUS.get(); }
    public static String getBotSkinUrl() { return BOT_SKIN_URL.get(); }
    public static boolean getAutoReplenish() { return AUTO_REPLENISH.get(); }
    public static boolean getAutoReplaceTool() { return AUTO_REPLACE_TOOL.get(); }
    public static boolean getAutoFish() { return AUTO_FISH.get(); }
    public static int getHungerThreshold() { return HUNGER_THRESHOLD.get(); }
    public static double getMovementSpeed() { return MOVEMENT_SPEED.get(); }
}
