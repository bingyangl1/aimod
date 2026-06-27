package com.aimod.ai.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * Goal: be within a certain radius of a position.
 *
 * <p>Useful for "get near this block" without requiring exact positioning.
 * For example, getting near a crafting table or near a player.</p>
 */
public class GoalNear implements Goal {
    private final BlockPos pos;
    private final int radius;
    private final int radiusSq;

    public GoalNear(BlockPos pos, int radius) {
        this.pos = pos;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int dx = x - pos.getX();
        int dy = y - pos.getY();
        int dz = z - pos.getZ();
        return (dx * dx + dy * dy + dz * dz) <= radiusSq;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dist = Math.sqrt((x - pos.getX()) * (x - pos.getX()) +
                (y - pos.getY()) * (y - pos.getY()) +
                (z - pos.getZ()) * (z - pos.getZ()));
        return Math.max(0, dist - radius);
    }

    public BlockPos getPos() { return pos; }
    public int getRadius() { return radius; }

    @Override
    public String toString() {
        return "GoalNear(" + pos.toShortString() + ", r=" + radius + ")";
    }
}
