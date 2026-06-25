package com.aimod.ai;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Ore depth knowledge — knows where each ore type spawns in Minecraft 1.21.1.
 *
 * <p>Used by MineBlockAction to automatically dig down to the correct Y level
 * when no ore is found nearby. This implements the "tactic layer" of the
 * hierarchical planning system: LLM decides WHAT to mine, this class decides
 * WHERE to mine it.</p>
 */
public class OreDepthHelper {

    /** Best Y level for each ore type (Minecraft 1.21.1 ore distribution). */
    private static final Map<String, OreInfo> ORE_DEPTHS = Map.ofEntries(
            Map.entry("minecraft:diamond_ore", new OreInfo(-64, 16, -59, "diamond")),
            Map.entry("minecraft:deepslate_diamond_ore", new OreInfo(-64, 16, -59, "diamond")),
            Map.entry("minecraft:iron_ore", new OreInfo(-64, 64, 16, "iron")),
            Map.entry("minecraft:deepslate_iron_ore", new OreInfo(-64, 64, 16, "iron")),
            Map.entry("minecraft:gold_ore", new OreInfo(-64, 32, -16, "gold")),
            Map.entry("minecraft:deepslate_gold_ore", new OreInfo(-64, 32, -16, "gold")),
            Map.entry("minecraft:copper_ore", new OreInfo(-16, 112, 48, "copper")),
            Map.entry("minecraft:deepslate_copper_ore", new OreInfo(-16, 112, 48, "copper")),
            Map.entry("minecraft:redstone_ore", new OreInfo(-64, 16, -59, "redstone")),
            Map.entry("minecraft:deepslate_redstone_ore", new OreInfo(-64, 16, -59, "redstone")),
            Map.entry("minecraft:lapis_ore", new OreInfo(-64, 32, -1, "lapis")),
            Map.entry("minecraft:deepslate_lapis_ore", new OreInfo(-64, 32, -1, "lapis")),
            Map.entry("minecraft:emerald_ore", new OreInfo(-16, 320, 232, "emerald")),
            Map.entry("minecraft:coal_ore", new OreInfo(0, 96, 48, "coal")),
            Map.entry("minecraft:deepslate_coal_ore", new OreInfo(0, 96, 48, "coal")),
            Map.entry("minecraft:nether_gold_ore", new OreInfo(10, 117, 15, "nether_gold")),
            Map.entry("minecraft:nether_quartz_ore", new OreInfo(10, 117, 15, "nether_quartz")),
            Map.entry("minecraft:ancient_debris", new OreInfo(8, 119, 15, "ancient_debris"))
    );

    /**
     * Get ore info for a block ID.
     * Returns null if the block is not a known ore.
     */
    @Nullable
    public static OreInfo getOreInfo(String blockId) {
        return ORE_DEPTHS.get(blockId);
    }

    /**
     * Check if the given block ID is a known ore type.
     */
    public static boolean isKnownOre(String blockId) {
        return ORE_DEPTHS.containsKey(blockId);
    }

    /**
     * Get the target Y level to dig to for a given ore type.
     * Returns -64 (void) if not a known ore.
     */
    public static int getTargetY(String blockId) {
        OreInfo info = ORE_DEPTHS.get(blockId);
        return info != null ? info.bestY : -64;
    }

    /**
     * Check if the given Y level is within the spawn range for the ore type.
     */
    public static boolean isInSpawnRange(String blockId, int y) {
        OreInfo info = ORE_DEPTHS.get(blockId);
        if (info == null) return false;
        return y >= info.minY && y <= info.maxY;
    }

    /**
     * Get a human-readable description of where the ore spawns.
     */
    public static String getSpawnDescription(String blockId) {
        OreInfo info = ORE_DEPTHS.get(blockId);
        if (info == null) return "unknown ore";
        return String.format("%s ore: Y %d to %d (best at Y=%d)", info.name, info.minY, info.maxY, info.bestY);
    }

    /**
     * Ore depth information.
     *
     * @param minY   minimum Y where this ore spawns
     * @param maxY   maximum Y where this ore spawns
     * @param bestY  Y level with highest spawn rate
     * @param name   human-readable ore name
     */
    public record OreInfo(int minY, int maxY, int bestY, String name) {}
}
