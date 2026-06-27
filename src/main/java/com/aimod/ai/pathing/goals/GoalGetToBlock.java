package com.aimod.ai.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * Goal: get adjacent to a specific block (within 1 block distance).
 *
 * <p>Useful for interacting with blocks (crafting tables, furnaces, chests)
 * where you need to be next to the block, not on top of it.</p>
 */
public class GoalGetToBlock implements Goal {
    private final BlockPos pos;

    public GoalGetToBlock(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int dx = Math.abs(x - pos.getX());
        int dy = Math.abs(y - pos.getY());
        int dz = Math.abs(z - pos.getZ());
        return (dx + dy + dz == 1); // exactly 1 block away
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int dx = Math.abs(x - pos.getX());
        int dy = Math.abs(y - pos.getY());
        int dz = Math.abs(z - pos.getZ());
        return Math.max(0, dx + dy + dz - 1);
    }

    public BlockPos getPos() { return pos; }

    @Override
    public String toString() {
        return "GoalGetToBlock(" + pos.toShortString() + ")";
    }
}
