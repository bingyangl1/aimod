package com.example.aimod.ai.pathing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BinaryHeapOpenSetTest {
    private BinaryHeapOpenSet heap;

    @BeforeEach void setUp() { heap = new BinaryHeapOpenSet(); }

    @Test @DisplayName("empty heap") void emptyHeap() {
        assertTrue(heap.isEmpty());
        assertEquals(0, heap.size());
    }

    @Test @DisplayName("insert and remove lowest") void insertRemove() {
        PathNode a = new PathNode(0, 0, 0); a.combinedCost = 5.0;
        PathNode b = new PathNode(1, 0, 0); b.combinedCost = 1.0;
        PathNode c = new PathNode(0, 1, 0); c.combinedCost = 3.0;
        heap.insert(a); heap.insert(b); heap.insert(c);
        assertEquals(3, heap.size());
        assertEquals(1.0, heap.removeLowest().combinedCost);
        assertEquals(3.0, heap.removeLowest().combinedCost);
        assertEquals(5.0, heap.removeLowest().combinedCost);
        assertTrue(heap.isEmpty());
    }

    @Test @DisplayName("ascending order") void ascendingOrder() {
        double[] costs = {5.0, 3.0, 8.0, 1.0, 7.0, 2.0};
        for (int i = 0; i < costs.length; i++) {
            PathNode n = new PathNode(i, 0, 0);
            n.combinedCost = costs[i];
            heap.insert(n);
        }
        double prev = -1;
        while (!heap.isEmpty()) {
            double c = heap.removeLowest().combinedCost;
            assertTrue(c >= prev);
            prev = c;
        }
    }

    @Test @DisplayName("throws on empty remove") void throwsOnEmpty() {
        assertThrows(IllegalStateException.class, () -> heap.removeLowest());
    }

    @Test @DisplayName("many insertions") void manyInsertions() {
        for (int i = 100; i >= 0; i--) {
            PathNode n = new PathNode(i, 0, 0);
            n.combinedCost = i;
            heap.insert(n);
        }
        assertEquals(101, heap.size());
        assertEquals(0.0, heap.removeLowest().combinedCost);
    }

    @Test @DisplayName("update decreases key") void updateKey() {
        PathNode a = new PathNode(0, 0, 0); a.combinedCost = 10.0; heap.insert(a);
        PathNode b = new PathNode(1, 0, 0); b.combinedCost = 5.0; heap.insert(b);
        a.combinedCost = 2.0; heap.update(a);
        assertSame(a, heap.removeLowest());
    }

    @Test @DisplayName("grows capacity") void growsCapacity() {
        BinaryHeapOpenSet small = new BinaryHeapOpenSet(4);
        for (int i = 0; i < 100; i++) {
            PathNode n = new PathNode(i, 0, 0);
            n.combinedCost = i;
            small.insert(n);
        }
        assertEquals(100, small.size());
    }
}
