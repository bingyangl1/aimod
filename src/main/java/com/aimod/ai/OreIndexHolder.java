package com.aimod.ai;

import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static holder for OreIndex instances per world/level.
 *
 * <p>This provides a simple way for the BlockChangeMixin to access
 * the OreIndex without requiring NeoForge capabilities or complex
 * dependency injection through the Mixin system.</p>
 */
public class OreIndexHolder {

    private static final Map<Level, OreIndex> INDEXES = new ConcurrentHashMap<>();

    /**
     * Get or create an OreIndex for the given level.
     */
    public static OreIndex getOrCreate(Level level) {
        return INDEXES.computeIfAbsent(level, k -> new OreIndex());
    }

    /**
     * Get the OreIndex for the given level (null if not created).
     */
    @Nullable
    public static OreIndex getForLevel(Level level) {
        return INDEXES.get(level);
    }

    /**
     * Remove the OreIndex for the given level (call on world unload).
     */
    public static void remove(Level level) {
        OreIndex removed = INDEXES.remove(level);
        if (removed != null) {
            removed.clear();
        }
    }

    /**
     * Clear all indexes (call on server shutdown).
     */
    public static void clearAll() {
        INDEXES.values().forEach(OreIndex::clear);
        INDEXES.clear();
    }
}
