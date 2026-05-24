package com.aimod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * BFS flood-fill scanner for connected blocks of the same type.
 * Used for vein mining (ores) and tree felling (logs).
 */
public final class VeinScanner {
    private VeinScanner() {}

    private static final int MAX_VEIN = 64; // hard cap to prevent runaway

    /**
     * Find all connected blocks of the same type starting from origin.
     * Uses 6-direction BFS (not diagonal — vein ores don't connect diagonally).
     * @param level the world
     * @param origin start position
     * @param targetBlock the block type to match (e.g., Blocks.DIAMOND_ORE)
     * @param maxBlocks max blocks to collect
     * @return sorted list of block positions (closest first)
     */
    public static List<BlockPos> findVein(ServerLevel level, BlockPos origin, Block targetBlock, int maxBlocks) {
        int limit = Math.min(maxBlocks, MAX_VEIN);
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);

        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!queue.isEmpty() && result.size() < limit) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == targetBlock) {
                result.add(pos);
                if (result.size() >= limit) break;
                for (int[] d : dirs) {
                    BlockPos next = pos.offset(d[0], d[1], d[2]);
                    if (!visited.contains(next)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        // Sort by distance from origin (mine closest first)
        result.sort(Comparator.comparingDouble(origin::distSqr));
        return result;
    }

    /**
     * Find all connected LOG blocks for tree felling.
     * Uses 26-direction BFS since logs can connect diagonally.
     */
    public static List<BlockPos> findTree(ServerLevel level, BlockPos origin, Block targetLog, int maxBlocks) {
        int limit = Math.min(maxBlocks, MAX_VEIN);
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && result.size() < limit) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == targetLog) {
                result.add(pos);
                if (result.size() >= limit) break;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos next = pos.offset(dx, dy, dz);
                            if (!visited.contains(next)) {
                                visited.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(origin::distSqr));
        return result;
    }
}
