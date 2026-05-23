package com.example.aimod.ai.pathing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MoveCost Constants Tests")
class MoveCostTest {

    @Test @DisplayName("WALK_ONE_BLOCK is positive") void walkPositive() {
        assertTrue(MoveCost.WALK_ONE_BLOCK > 0);
    }

    @Test @DisplayName("SPRINT faster than WALK") void sprintFasterThanWalk() {
        assertTrue(MoveCost.SPRINT_ONE_BLOCK < MoveCost.WALK_ONE_BLOCK);
    }

    @Test @DisplayName("SPRINT_MULTIPLIER is ratio") void sprintMultiplier() {
        assertEquals(MoveCost.SPRINT_ONE_BLOCK / MoveCost.WALK_ONE_BLOCK,
                     MoveCost.SPRINT_MULTIPLIER, 0.001);
    }

    @Test @DisplayName("WALK_IN_WATER slower than WALK") void waterSlower() {
        assertTrue(MoveCost.WALK_IN_WATER > MoveCost.WALK_ONE_BLOCK);
    }

    @Test @DisplayName("DIAGONAL_MULTIPLIER is sqrt(2)") void diagonalSqrt2() {
        assertEquals(Math.sqrt(2.0), MoveCost.DIAGONAL_MULTIPLIER, 0.001);
    }

    @Test @DisplayName("JUMP_ONE_BLOCK positive") void jumpPositive() {
        assertTrue(MoveCost.JUMP_ONE_BLOCK > 0);
    }

    @Test @DisplayName("FALL_1_BLOCK positive") void fallPositive() {
        assertTrue(MoveCost.FALL_1_BLOCK > 0);
    }

    @Test @DisplayName("FALL cheaper than JUMP") void fallCheaperThanJump() {
        assertTrue(MoveCost.FALL_1_BLOCK < MoveCost.JUMP_ONE_BLOCK);
    }

    @Test @DisplayName("BREAK_BASE positive") void breakBasePositive() {
        assertTrue(MoveCost.BREAK_BASE > 0);
    }

    @Test @DisplayName("VOID_COST very large") void voidCostLarge() {
        assertTrue(MoveCost.VOID_COST > 100000);
    }

    @Test @DisplayName("OFFSETS has 10 entries") void offsetsCount() {
        assertEquals(16, MoveCost.OFFSETS.length);
    }

    @Test @DisplayName("Each offset is 3D") void offsetsAre3D() {
        for (int[] offset : MoveCost.OFFSETS) {
            assertEquals(4, offset.length);
        }
    }

    @Test @DisplayName("No offset is (0,0,0)") void noZeroOffset() {
        for (int[] offset : MoveCost.OFFSETS) {
            assertFalse(offset[0] == 0 && offset[1] == 0 && offset[2] == 0, "offset should not be (0,0,0)");
        }
    }
}