package com.example.aimod.ai.pathing;

import net.minecraft.core.BlockPos;

/**
 * A* search node. Tracks position, accumulated cost, heuristic, and parent link.
 * Inspired by Baritone's PathNode.
 */
public class PathNode {
    public final int x, y, z;
    public double cost;            // g: cost from start
    public double heuristic;       // h: estimated cost to goal
    public double combinedCost;    // f = g + h
    public PathNode previous;
    public boolean inOpenSet;
    public int heapPosition = -1; // Position in binary heap

    public PathNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.cost = Double.MAX_VALUE;
        this.heuristic = 0;
        this.combinedCost = Double.MAX_VALUE;
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    /**
     * 3D hash for use as Long2ObjectOpenHashMap key.
     * Same packing as Baritone: x in bits 26-51, z in bits 0-25, y in bits 52-63.
     */
    public static long hash(int x, int y, int z) {
        return ((long) (x + 30000000) & 0x3FFFFFFL)
             | (((long) (z + 30000000) & 0x3FFFFFFL) << 26)
             | (((long) y & 0xFFFL) << 52);
    }

    /**
     * Euclidean distance heuristic (admissible for 3D movement).
     */
    public static double heuristic(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}