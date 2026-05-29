package com.aimod.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InventoryUtils — inventory search and counting.
 */
@DisplayName("InventoryUtils — inventory operations")
class InventoryUtilsTest {

    @Nested
    @DisplayName("FindItemResult")
    class FindItemResult {
        @Test
        @DisplayName("found() returns true when slot >= 0")
        void foundTrue() {
            InventoryUtils.FindItemResult result = new InventoryUtils.FindItemResult(5, 10);
            assertTrue(result.found());
        }

        @Test
        @DisplayName("found() returns false when slot < 0")
        void foundFalse() {
            InventoryUtils.FindItemResult result = new InventoryUtils.FindItemResult(-1, 0);
            assertFalse(result.found());
        }

        @Test
        @DisplayName("isHotbar() returns true for slots 0-8")
        void hotbarSlots() {
            assertTrue(new InventoryUtils.FindItemResult(0, 1).isHotbar());
            assertTrue(new InventoryUtils.FindItemResult(8, 1).isHotbar());
            assertFalse(new InventoryUtils.FindItemResult(9, 1).isHotbar());
            assertFalse(new InventoryUtils.FindItemResult(35, 1).isHotbar());
        }

        @Test
        @DisplayName("slot() returns correct value")
        void slotValue() {
            InventoryUtils.FindItemResult result = new InventoryUtils.FindItemResult(15, 64);
            assertEquals(15, result.slot());
        }

        @Test
        @DisplayName("count() returns correct value")
        void countValue() {
            InventoryUtils.FindItemResult result = new InventoryUtils.FindItemResult(0, 42);
            assertEquals(42, result.count());
        }
    }
}
