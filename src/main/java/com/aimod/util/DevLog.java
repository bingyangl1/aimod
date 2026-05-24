package com.aimod.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class DevLog {
    public static final String KEY = "[AIMOD-DEV]";
    private static final Logger LOGGER;
    static {
        Logger temp;
        try { temp = LogManager.getLogger("AIModDev"); }
        catch (Exception | NoClassDefFoundError e) { temp = null; }
        LOGGER = temp;
    }
    private static final int MAX_TEXT_LENGTH = 4000;

    private DevLog() {
    }

    public static void info(String tag, String message, Object... args) {
        if (LOGGER != null) LOGGER.info(KEY + " [" + tag + "] " + message, args);
    }

    public static void warn(String tag, String message, Object... args) {
        if (LOGGER != null) LOGGER.warn(KEY + " [" + tag + "] " + message, args);
    }

    public static void error(String tag, String message, Throwable throwable) {
        if (LOGGER != null) LOGGER.error(KEY + " [" + tag + "] " + message, throwable);
    }

    public static String compact(String value) {
        if (value == null) {
            return "null";
        }
        String compacted = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (compacted.length() <= MAX_TEXT_LENGTH) {
            return compacted;
        }
        return compacted.substring(0, MAX_TEXT_LENGTH) + "...<truncated>";
    }
}
