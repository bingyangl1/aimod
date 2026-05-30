package com.aimod.ai.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Helper for vein-mining trees and ores (breaking connected blocks).
 * Extracted from GatherResourceAction (P2-14).
 */
public final class VeinMiningHelper {

    /** Break all connected logs at and around the given position. */
    public static int veinMineTree(ServerLevel level, BlockPos start, Block targetBlock, int maxBlocks, boolean dropItems) {
        var vein = com.aimod.ai.VeinScanner.findTree(level, start, targetBlock, maxBlocks);
        if (vein.isEmpty()) return 0;

        for (BlockPos vp : vein) {
            level.destroyBlock(vp, dropItems, null);
        }
        return vein.size();
    }

    /** Break all connected ore blocks at and around the given position. */
    public static int veinMineOre(ServerLevel level, BlockPos start, Block targetBlock, int maxBlocks, boolean dropItems) {
        var vein = com.aimod.ai.VeinScanner.findVein(level, start, targetBlock, maxBlocks);
        if (vein.isEmpty()) return 0;

        for (BlockPos vp : vein) {
            level.destroyBlock(vp, dropItems, null);
        }
        return vein.size();
    }

    public static boolean isLogBlock(Block block) {
        return block == Blocks.OAK_LOG || block == Blocks.SPRUCE_LOG
            || block == Blocks.BIRCH_LOG || block == Blocks.JUNGLE_LOG
            || block == Blocks.ACACIA_LOG || block == Blocks.DARK_OAK_LOG
            || block == Blocks.MANGROVE_LOG || block == Blocks.CHERRY_LOG
            || block == Blocks.CRIMSON_STEM || block == Blocks.WARPED_STEM
            || block == Blocks.OAK_WOOD || block == Blocks.SPRUCE_WOOD
            || block == Blocks.BIRCH_WOOD || block == Blocks.JUNGLE_WOOD
            || block == Blocks.ACACIA_WOOD || block == Blocks.DARK_OAK_WOOD
            || block == Blocks.MANGROVE_WOOD || block == Blocks.CHERRY_WOOD
            || block == Blocks.CRIMSON_HYPHAE || block == Blocks.WARPED_HYPHAE;
    }

    public static boolean isOreBlock(Block block) {
        return block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
            || block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
            || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
            || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
            || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
            || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
            || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
            || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
            || block == Blocks.NETHER_GOLD_ORE || block == Blocks.NETHER_QUARTZ_ORE;
    }

    private VeinMiningHelper() {}
}
