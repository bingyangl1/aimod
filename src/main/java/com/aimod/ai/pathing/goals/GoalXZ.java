package com.aimod.ai.pathing.goals;

/**
 * Goal: reach a specific X,Z coordinate at any Y level.
 * Adapted from Baritone's GoalXZ (LGPL-3.0).
 */
public class GoalXZ implements Goal {
    public final int x, z;

    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, z - this.z);
    }

    /**
     * Heuristic: mix of Euclidean and Manhattan distance (Baritone's approach).
     * Better than pure Euclidean for grid-based movement.
     */
    public static double calculate(double xDiff, double zDiff) {
        double absX = Math.abs(xDiff);
        double absZ = Math.abs(zDiff);
        // Euclidean for the main distance
        double euclidean = Math.sqrt(absX * absX + absZ * absZ);
        // Manhattan for the minimum
        double manhattan = absX + absZ;
        // Mix: Euclidean + (Manhattan - Euclidean) * 0.001
        // This gives a very slight preference to straighter paths
        return euclidean + (manhattan - euclidean) * 0.001;
    }

    @Override
    public String toString() {
        return String.format("GoalXZ{%d, %d}", x, z);
    }
}