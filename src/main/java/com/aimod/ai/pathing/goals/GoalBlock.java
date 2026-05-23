package com.aimod.ai.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * Goal: reach a specific block position.
 * Adapted from Baritone's GoalBlock (LGPL-3.0).
 */
public class GoalBlock implements Goal {
    public final int x, y, z;

    public GoalBlock(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return GoalXZ.calculate(x - this.x, z - this.z) + GoalYLevel.calculate(0, y - this.y);
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("GoalBlock{%d, %d, %d}", x, y, z);
    }
}