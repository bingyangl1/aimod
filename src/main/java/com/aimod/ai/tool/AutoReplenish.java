package com.aimod.ai.tool;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-replenishes the held item when its count drops below a threshold.
 * Searches inventory for matching items and refills to a target amount.
 *
 * <p>Inspired by SiliconeDolls' FakePlayerAutoReplenishment.
 */
public final class AutoReplenish {
    private AutoReplenish() {}

    private static final int REFILL_TO = 32;   // refill to half stack
    private static final int REFILL_AT = 4;    // trigger at 4 or fewer

    /**
     * Check and refill the bot's held item. Call from FakePlayer.tick().
     */
    public static void tick(FakePlayer bot) {
        var inv = bot.getInventory();
        int slot = inv.selected;
        ItemStack held = inv.getItem(slot);

        if (held.isEmpty()) return;
        if (held.getCount() > REFILL_AT) return;
        if (held.getMaxStackSize() <= 1) return; // tools, weapons — use AutoReplaceTool

        // Find matching items elsewhere in inventory
        for (int i = 0; i < 36 && held.getCount() < REFILL_TO; i++) {
            if (i == slot) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(stack, held)) continue;

            int take = Math.min(stack.getCount(), REFILL_TO - held.getCount());
            stack.shrink(take);
            held.grow(take);
            if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
    }
}
