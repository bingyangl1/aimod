package com.aimod.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryUtilsFindItemTest {

    @Test void foundHotbar() {
        var r = new InventoryUtils.FindItemResult(3, 64);
        assertTrue(r.found());
        assertTrue(r.isHotbar());
        assertEquals(3, r.slot());
        assertEquals(64, r.count());
    }

    @Test void notFound() {
        assertFalse(InventoryUtils.FindItemResult.NOT_FOUND.found());
        assertEquals(-1, InventoryUtils.FindItemResult.NOT_FOUND.slot());
    }

    @Test void offhand() {
        var r = new InventoryUtils.FindItemResult(40, 1);
        assertTrue(r.isOffhand());
    }

    @Test void mainInventory() {
        var r = new InventoryUtils.FindItemResult(20, 16);
        assertTrue(r.isMainInventory());
    }

    @Test void armor() {
        assertTrue(new InventoryUtils.FindItemResult(38, 1).isArmor());
    }
}
