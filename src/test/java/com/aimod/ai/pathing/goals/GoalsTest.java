package com.aimod.ai.pathing.goals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Goal Classes Tests")
class GoalsTest {

    @Test @DisplayName("GoalXZ isInGoal") void goalXZInGoal() {
        GoalXZ g = new GoalXZ(100, 200);
        assertTrue(g.isInGoal(100, 64, 200));
        assertTrue(g.isInGoal(100, 0, 200));
        assertFalse(g.isInGoal(101, 64, 200));
    }

    @Test @DisplayName("GoalXZ heuristic zero at target") void goalXZHeuristicZero() {
        GoalXZ g = new GoalXZ(100, 200);
        assertEquals(0.0, g.heuristic(100, 64, 200), 0.001);
    }

    @Test @DisplayName("GoalXZ heuristic positive") void goalXZHeuristicPositive() {
        GoalXZ g = new GoalXZ(0, 0);
        assertTrue(g.heuristic(3, 64, 4) > 0);
    }

    @Test @DisplayName("GoalXZ toString") void goalXZToString() {
        assertEquals("GoalXZ{10, 20}", new GoalXZ(10, 20).toString());
    }

    @Test @DisplayName("GoalYLevel isInGoal ignores x,z") void goalYLevelInGoal() {
        GoalYLevel g = new GoalYLevel(64);
        assertTrue(g.isInGoal(0, 64, 0));
        assertTrue(g.isInGoal(999, 64, -999));
        assertFalse(g.isInGoal(0, 65, 0));
    }

    @Test @DisplayName("GoalYLevel ascending costs more") void goalYLevelAscending() {
        GoalYLevel g = new GoalYLevel(64);
        assertTrue(g.heuristic(0, 74, 0) > g.heuristic(0, 54, 0));
    }

    @Test @DisplayName("GoalYLevel heuristic zero") void goalYLevelZero() {
        assertEquals(0.0, new GoalYLevel(64).heuristic(100, 64, 200), 0.001);
    }

    @Test @DisplayName("GoalYLevel toString") void goalYLevelToString() {
        assertEquals("GoalYLevel{64}", new GoalYLevel(64).toString());
    }

    @Test @DisplayName("GoalBlock isInGoal exact") void goalBlockInGoal() {
        GoalBlock g = new GoalBlock(10, 20, 30);
        assertTrue(g.isInGoal(10, 20, 30));
        assertFalse(g.isInGoal(10, 20, 31));
    }

    @Test @DisplayName("GoalBlock heuristic zero at target") void goalBlockZero() {
        assertEquals(0.0, new GoalBlock(10, 20, 30).heuristic(10, 20, 30), 0.001);
    }

    @Test @DisplayName("GoalBlock heuristic increases with distance") void goalBlockIncreases() {
        GoalBlock g = new GoalBlock(0, 0, 0);
        assertTrue(g.heuristic(10, 10, 10) > g.heuristic(1, 1, 1));
    }

    @Test @DisplayName("GoalBlock toString") void goalBlockToString() {
        assertEquals("GoalBlock{1, 2, 3}", new GoalBlock(1, 2, 3).toString());
    }

    @Test @DisplayName("GoalComposite isInGoal any match") void compositeInGoal() {
        GoalComposite g = new GoalComposite(new GoalBlock(10, 20, 30), new GoalBlock(40, 50, 60));
        assertTrue(g.isInGoal(10, 20, 30));
        assertTrue(g.isInGoal(40, 50, 60));
        assertFalse(g.isInGoal(99, 99, 99));
    }

    @Test @DisplayName("GoalComposite heuristic is min") void compositeHeuristicMin() {
        GoalBlock a = new GoalBlock(0, 0, 0);
        GoalBlock b = new GoalBlock(100, 100, 100);
        GoalComposite g = new GoalComposite(a, b);
        double h = g.heuristic(10, 10, 10);
        assertEquals(Math.min(a.heuristic(10, 10, 10), b.heuristic(10, 10, 10)), h, 0.001);
    }

    @Test @DisplayName("GoalComposite toString has OR") void compositeToString() {
        assertTrue(new GoalComposite(new GoalBlock(1, 2, 3), new GoalBlock(4, 5, 6)).toString().contains("OR"));
    }
}