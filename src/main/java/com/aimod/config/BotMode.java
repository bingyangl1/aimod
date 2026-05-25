package com.aimod.config;

public enum BotMode {
    FP("fp"),
    DUAL("dual");

    private final String key;

    BotMode(String key) { this.key = key; }

    public String getKey() { return key; }

    public static BotMode fromKey(String key) {
        if (key == null) return FP;
        for (BotMode m : values()) {
            if (m.key.equalsIgnoreCase(key.trim())) return m;
        }
        return FP;
    }
}
