package com.example.aimod.ai.pathing.goals;

/**
 * Composite goal: reach ANY of the given goals (OR logic).
 * Useful for "find nearest of multiple positions".
 * Adapted from Baritone's GoalComposite (LGPL-3.0).
 */
public class GoalComposite implements Goal {
    private final Goal[] goals;

    public GoalComposite(Goal... goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal goal : goals) {
            if (goal.isInGoal(x, y, z)) return true;
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (Goal goal : goals) {
            min = Math.min(min, goal.heuristic(x, y, z));
        }
        return min;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GoalComposite{");
        for (int i = 0; i < goals.length; i++) {
            if (i > 0) sb.append(" OR ");
            sb.append(goals[i]);
        }
        sb.append("}");
        return sb.toString();
    }
}