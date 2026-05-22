package com.example.aimod.ai.pathing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PathNode Tests")
class PathNodeTest {

    @Test
    @DisplayName("hash produces consistent results for same coordinates")
    void hashConsistentForSameCoords() {
        long hash1 = PathNode.hash(100, 64, 200);
        long hash2 = PathNode.hash(100, 64, 200);
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("hash produces different results for different coordinates")
    void hashDifferentForDifferentCoords() {
        long hash1 = PathNode.hash(100, 64, 200);
        long hash2 = PathNode.hash(101, 64, 200);
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("heuristic returns 0 for same position")
    void heuristicZeroForSamePosition() {
        double h = PathNode.heuristic(100, 64, 200, 100, 64, 200);
        assertEquals(0.0, h, 0.001);
    }

    @Test
    @DisplayName("heuristic returns correct Euclidean distance")
    void heuristicReturnsEuclideanDistance() {
        double h = PathNode.heuristic(0, 0, 0, 3, 4, 0);
        assertEquals(5.0, h, 0.001);
    }

    @Test
    @DisplayName("heuristic is symmetric")
    void heuristicIsSymmetric() {
        double h1 = PathNode.heuristic(10, 20, 30, 40, 50, 60);
        double h2 = PathNode.heuristic(40, 50, 60, 10, 20, 30);
        assertEquals(h1, h2, 0.001);
    }

    @Test
    @DisplayName("PathNode initializes with max cost values")
    void pathNodeInitializesWithMaxCost() {
        PathNode node = new PathNode(10, 20, 30);
        assertEquals(10, node.x);
        assertEquals(20, node.y);
        assertEquals(30, node.z);
        assertEquals(Double.MAX_VALUE, node.cost);
        assertEquals(Double.MAX_VALUE, node.combinedCost);
        assertEquals(0, node.heuristic);
        assertNull(node.previous);
        assertFalse(node.inOpenSet);
    }
}