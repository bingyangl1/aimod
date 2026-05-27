package com.aimod.client;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Custom container menu that displays a bot's full inventory in the vanilla player-inventory layout.
 * Uses the vanilla inventory texture (176x166).
 *
 * Slot layout (matching InventoryMenu positions):
 *   Helmet (39):     (8, 8)
 *   Chestplate (38): (8, 26)
 *   Leggings (37):   (8, 44)
 *   Boots (36):      (8, 62)
 *   Offhand (40):    (77, 62)
 *   Main inv (9-35): (8, 84) to (152, 138) — 3 rows of 9
 *   Hotbar (0-8):    (8, 142) to (152, 142) — 9 slots
 */
public class BotInventoryMenu extends AbstractContainerMenu {

    private static final int BOT_SLOTS = 41;

    public BotInventoryMenu(int containerId, Inventory playerInv, Container botInv) {
        super(ModMenuTypes.BOT_INVENTORY.get(), containerId);

        // --- Bot armor slots (inventory indices 36-39) ---
        addSlot(new Slot(botInv, 39, 8, 8));    // helmet
        addSlot(new Slot(botInv, 38, 8, 26));   // chestplate
        addSlot(new Slot(botInv, 37, 8, 44));   // leggings
        addSlot(new Slot(botInv, 36, 8, 62));   // boots

        // --- Bot offhand (index 40) ---
        addSlot(new Slot(botInv, 40, 77, 62));

        // --- Bot main inventory (indices 9-35) — 3 rows of 9 ---
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(botInv, 9 + row * 9 + col, 8 + col * 18, 84 + row * 18));
            }
        }

        // --- Bot hotbar (indices 0-8) ---
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(botInv, col, 8 + col * 18, 142));
        }
    }

    /**
     * Client-side constructor: creates a placeholder bot inventory.
     * The actual items will be synced from the server via initializeContents().
     */
    public BotInventoryMenu(int containerId, Inventory playerInv) {
        this(containerId, playerInv, new SimpleContainer(BOT_SLOTS));
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();

            // Move between bot's main/hotbar and bot's armor/offhand
            if (index < 5) {
                // From armor/offhand → main inventory + hotbar
                if (!this.moveItemStackTo(stack, 5, BOT_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From main inventory/hotbar → try armor first, then offhand
                if (!this.moveItemStackTo(stack, 0, 5, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }
}
