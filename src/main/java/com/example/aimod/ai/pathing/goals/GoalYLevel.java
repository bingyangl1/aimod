package com.example.aimod.ai.pathing.goals;

/**
 * Goal: reach a specific Y level at any X,Z.
 * Adapted from Baritone's GoalYLevel (LGPL-3.0).
 */
public class GoalYLevel implements Goal {
    public final int y;

    public GoalYLevel(int y) {
        this.y = y;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == this.y;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(0, y - this.y);
    }

    public static double calculate(int ignored, int yDiff) {
        int absY = Math.abs(yDiff);
        // Going up costs more than going down (jump up ~5 ticks vs fall ~3 ticks)
        if (yDiff > 0) {
            return absY * 5.0; // ascending cost
        } else {
            return absY * 3.0; // descending cost (falling is faster)
        }
    }

    @Override
    public String toString() {
        return String.format("GoalYLevel{%d}", y);
    }
}