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
                "Model name to use for full task planning (premium model)",
                "OpenAI: gpt-3.5-turbo, gpt-4, gpt-4o",
                "Claude: claude-3-sonnet-20240229",
                "Ollama: llama3, qwen2, mistral"
            )
            .define("modelName", "deepseek-v4-pro");

    public static final ModConfigSpec.ConfigValue<String> CHEAP_MODEL_NAME = BUILDER
            .comment(
                "Model name for incremental replan (cheaper/faster model)",
                "Used for replan after action failure — simpler tasks need less intelligence",
                "Leave empty to use the same model as modelName"
            )
            .define("cheapModelName", "");

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

    public static final ModConfigSpec.ConfigValue<Boolean> VEIN_MINE = BUILDER
            .comment("Mine connected blocks of same type (vein mining / tree felling)")
            .define("veinMine", true);

    public static final ModConfigSpec.ConfigValue<Integer> UNDO_HISTORY = BUILDER
            .comment("Maximum number of undo operations to remember (0 = disable undo)")
            .defineInRange("undoHistory", 10, 0, 50);

    public static final ModConfigSpec.ConfigValue<Boolean> PERSIST_TASKS = BUILDER
            .comment("Persist in-progress tasks to disk. When true, bots resume tasks after server restart.")
            .define("persistTasks", true);

    public static final ModConfigSpec.ConfigValue<Boolean> SHOW_TASK_ABOVE_HEAD = BUILDER
            .comment("Show bot task status above head as a name tag",
                     "Displays: bot name (green) + state + task description + progress")
            .define("showTaskAboveHead", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_PVP_DEFENSE = BUILDER
            .comment("Enable PvP defense chain — bot retreats/shields when attacked by other players",
                     "Default: false (disabled)")
            .define("enablePvpDefense", false);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_DANGER_CHAIN = BUILDER
            .comment("Enable danger avoidance chain (lava, fire, drowning, falls)",
                     "Default: true")
            .define("enableDangerChain", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_DEFENSE_CHAIN = BUILDER
            .comment("Enable mob defense chain (attacks hostile mobs)",
                     "Default: true")
            .define("enableDefenseChain", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_FOOD_CHAIN = BUILDER
            .comment("Enable auto-eating chain",
                     "Default: true")
            .define("enableFoodChain", true);

    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_UNSTUCK_CHAIN = BUILDER
            .comment("Enable stuck detection and recovery chain",
                     "Default: true")
            .define("enableUnstuckChain", true);

    public static final ModConfigSpec.ConfigValue<Integer> PATHFINDER_TIMEOUT_MS = BUILDER
            .comment("A* pathfinding timeout in milliseconds",
                     "Higher values allow longer paths but may block longer")
            .defineInRange("pathfinderTimeoutMs", 2000, 500, 10000);

    public static final ModConfigSpec.ConfigValue<Integer> PATHFINDER_MAX_RADIUS = BUILDER
            .comment("Maximum A* pathfinding search radius in blocks",
                     "Higher values find longer paths but use more CPU")
            .defineInRange("pathfinderMaxRadius", 20, 5, 64);

    public static final ModConfigSpec.ConfigValue<Integer> MAX_VEIN_SIZE = BUILDER
            .comment("Maximum number of blocks in a single vein mine operation",
                     "Prevents accidentally mining too many connected blocks")
            .defineInRange("maxVeinSize", 64, 1, 256);

    public static final ModConfigSpec.ConfigValue<Integer> DEFENSE_SCAN_RADIUS = BUILDER
            .comment("Radius (blocks) to scan for hostile mobs for defense chain",
                     "Larger values detect threats earlier but use more CPU")
            .defineInRange("defenseScanRadius", 8, 3, 32);

    public static final ModConfigSpec.ConfigValue<Integer> DEFENSE_RETREAT_HEALTH = BUILDER
            .comment("Health level below which bot retreats from combat",
                     "Bot will run away when health drops below this value")
            .defineInRange("defenseRetreatHealth", 6, 1, 20);

    public static final ModConfigSpec.ConfigValue<Integer> HUNGER_THRESHOLD = BUILDER
            .comment("Food level below which the bot auto-eats")
            .defineInRange("hungerThreshold", 14, 0, 20);

    public static final ModConfigSpec.ConfigValue<String> BOT_MODE = BUILDER
            .comment(
                "Bot entity mode: 'fp' (FakePlayer only, default) or 'dual' (Mob + FakePlayer)",
                "FP: bot is a real ServerPlayer, uses default player model, no Mob wrapper",
                "DUAL: bot spawns a Mob entity that wraps a FakePlayer (two entities)",
                "Changing this requires restart."
            )
            .define("botMode", "fp");

    public static final ModConfigSpec.ConfigValue<Double> MOVEMENT_SPEED = BUILDER
            .comment("Bot movement speed multiplier (0.3 = default player walk speed)")
            .defineInRange("movementSpeed", 0.3, 0.1, 1.0);

    public static final ModConfigSpec.ConfigValue<Integer> MAX_CONTEXT_TOKENS = BUILDER
            .comment("Maximum context tokens sent to LLM (hard limit). When context exceeds this,",
                     "older observations will be compacted via BotMemoryStore.",
                     "Note: this controls prompt context, not LLM max_tokens (which controls response length).")
            .defineInRange("maxContextTokens", 32000, 1024, 128000);

    public static final ModConfigSpec.ConfigValue<Integer> COMPACT_TRIGGER_TOKENS = BUILDER
            .comment("Token threshold that triggers automatic memory compaction.",
                     "Should be ~75% of maxContextTokens. When estimated tokens exceed this,",
                     "oldest working-memory entries are compressed into short-term summaries.")
            .defineInRange("compactTriggerTokens", 24000, 512, 128000);

    public static final ModConfigSpec.ConfigValue<Integer> MAX_WORKING_MEMORY = BUILDER
            .comment("Maximum number of WorldObservation snapshots kept in working memory.")
            .defineInRange("maxWorkingMemory", 100, 20, 500);

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

    public static String getCheapModelName() {
        String cheap = CHEAP_MODEL_NAME.get();
        return (cheap != null && !cheap.isBlank()) ? cheap : getModelName();
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
    public static boolean getVeinMine() { return VEIN_MINE.get(); }
    public static int getUndoHistory() { return UNDO_HISTORY.get(); }
    public static boolean getPersistTasks() { return PERSIST_TASKS.get(); }
    public static boolean getShowTaskAboveHead() { return SHOW_TASK_ABOVE_HEAD.get(); }
    public static boolean getEnablePvpDefense() { return ENABLE_PVP_DEFENSE.get(); }
    public static boolean getEnableDangerChain() { return ENABLE_DANGER_CHAIN.get(); }
    public static boolean getEnableDefenseChain() { return ENABLE_DEFENSE_CHAIN.get(); }
    public static boolean getEnableFoodChain() { return ENABLE_FOOD_CHAIN.get(); }
    public static boolean getEnableUnstuckChain() { return ENABLE_UNSTUCK_CHAIN.get(); }
    public static int getPathfinderTimeoutMs() { return PATHFINDER_TIMEOUT_MS.get(); }
    public static int getPathfinderMaxRadius() { return PATHFINDER_MAX_RADIUS.get(); }
    public static int getMaxVeinSize() { return MAX_VEIN_SIZE.get(); }
    public static int getDefenseScanRadius() { return DEFENSE_SCAN_RADIUS.get(); }
    public static int getDefenseRetreatHealth() { return DEFENSE_RETREAT_HEALTH.get(); }
    public static int getHungerThreshold() { return HUNGER_THRESHOLD.get(); }
    public static double getMovementSpeed() { return MOVEMENT_SPEED.get(); }
    public static int getMaxContextTokens() { return MAX_CONTEXT_TOKENS.get(); }
    public static int getCompactTriggerTokens() {
        int trigger = COMPACT_TRIGGER_TOKENS.get();
        int max = MAX_CONTEXT_TOKENS.get();
        // Ensure trigger is always less than max (prevent misconfiguration)
        return Math.min(trigger, max - 512);
    }
    public static int getMaxWorkingMemory() { return MAX_WORKING_MEMORY.get(); }

    public static BotMode getBotMode() { return BotMode.fromKey(BOT_MODE.get()); }

    // -- Runtime setters (for /ai_bot config command) --
    public static void setDefaultScanRadius(int v) { DEFAULT_SCAN_RADIUS.set(Math.max(8, Math.min(128, v))); }
    public static void setHungerThreshold(int v) { HUNGER_THRESHOLD.set(Math.max(0, Math.min(20, v))); }
    public static void setMovementSpeed(double v) { MOVEMENT_SPEED.set(Math.max(0.1, Math.min(1.0, v))); }
    public static void setVeinMine(boolean v) { VEIN_MINE.set(v); }
    public static void setAutoReplenish(boolean v) { AUTO_REPLENISH.set(v); }
    public static void setAutoReplaceTool(boolean v) { AUTO_REPLACE_TOOL.set(v); }
    public static void setAutoFish(boolean v) { AUTO_FISH.set(v); }
    public static void setMaxBots(int v) { MAX_BOTS.set(Math.max(1, Math.min(50, v))); }
    public static void setShowTaskAboveHead(boolean v) { SHOW_TASK_ABOVE_HEAD.set(v); }
    public static void setEnablePvpDefense(boolean v) { ENABLE_PVP_DEFENSE.set(v); }
    public static void setEnableDangerChain(boolean v) { ENABLE_DANGER_CHAIN.set(v); }
    public static void setEnableDefenseChain(boolean v) { ENABLE_DEFENSE_CHAIN.set(v); }
    public static void setEnableFoodChain(boolean v) { ENABLE_FOOD_CHAIN.set(v); }
    public static void setEnableUnstuckChain(boolean v) { ENABLE_UNSTUCK_CHAIN.set(v); }
    public static void setCheapModelName(String v) { CHEAP_MODEL_NAME.set(v); }
    public static void setPathfinderTimeoutMs(int v) { PATHFINDER_TIMEOUT_MS.set(v); }
    public static void setPathfinderMaxRadius(int v) { PATHFINDER_MAX_RADIUS.set(v); }
    public static void setMaxVeinSize(int v) { MAX_VEIN_SIZE.set(v); }
    public static void setDefenseScanRadius(int v) { DEFENSE_SCAN_RADIUS.set(v); }
    public static void setDefenseRetreatHealth(int v) { DEFENSE_RETREAT_HEALTH.set(v); }
}
