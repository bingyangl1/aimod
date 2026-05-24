package com.aimod.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Helper methods for GameTest templates.
 * Provides common setup patterns for tests.
 */
public class GameTestTemplates {

    /**
     * Create a flat 5x5 platform at the test origin.
     * Useful for tests that need a small walkable area.
     */
    public static void createFlatPlatform(GameTestHelper helper, int size) {
        BlockPos base = new BlockPos(0, 1, 0);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(base.offset(x, -1, z), Blocks.STONE);
            }
        }
    }

    /**
     * Create a simple 3x3 room with walls.
     * Useful for testing movement and navigation.
     */
    public static void createSimpleRoom(GameTestHelper helper) {
        BlockPos base = new BlockPos(0, 1, 0);
        // Floor
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(base.offset(x, -1, z), Blocks.STONE);
            }
        }
        // Walls (1 block high)
        for (int x = -1; x <= 5; x++) {
            helper.setBlock(base.offset(x, 0, -1), Blocks.STONE);
            helper.setBlock(base.offset(x, 0, 5), Blocks.STONE);
        }
        for (int z = 0; z < 5; z++) {
            helper.setBlock(base.offset(-1, 0, z), Blocks.STONE);
            helper.setBlock(base.offset(5, 0, z), Blocks.STONE);
        }
    }

    /**
     * Place some test blocks for gathering tests.
     */
    public static void placeTestLogs(GameTestHelper helper, int count) {
        BlockPos base = new BlockPos(2, 1, 2);
        for (int i = 0; i < count; i++) {
            helper.setBlock(base.offset(i * 2, 0, 0), Blocks.OAK_LOG);
        }
    }

    /**
     * Place a crafting table for crafting tests.
     */
    public static void placeCraftingTable(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 1, 2);
        helper.setBlock(base, Blocks.CRAFTING_TABLE);
    }
}
