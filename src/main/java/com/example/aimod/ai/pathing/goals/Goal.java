package com.example.aimod.ai.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * Abstract Goal for pathfinding.
 * Adapted from Baritone's Goal interface (LGPL-3.0).
 */
public interface Goal {
    boolean isInGoal(int x, int y, int z);
    double heuristic(int x, int y, int z);

    default boolean isInGoal(BlockPos pos) {
        return isInGoal(pos.getX(), pos.getY(), pos.getZ());
    }

    default double heuristic(BlockPos pos) {
        return heuristic(pos.getX(), pos.getY(), pos.getZ());
    }
}