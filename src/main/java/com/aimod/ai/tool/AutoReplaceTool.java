package com.aimod.ai.tool;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Automatically replaces a nearly-broken tool with a fresh one
 * of the same type from inventory.
 *
 * <p>Inspired by SiliconeDolls' FakePlayerAutoReplaceTool.
 */
public final class AutoReplaceTool {
    private AutoReplaceTool() {}

    private static final int DURABILITY_THRESHOLD = 10;

    /**
     * Check and replace the bot's held tool if nearly broken.
     * Call from FakePlayer.tick().
     */
    public static void tick(FakePlayer bot) {
        var inv = bot.getInventory();
        int slot = inv.selected;
        ItemStack held = inv.getItem(slot);

        if (held.isEmpty()) return;
        if (!held.isDamageableItem()) return;

        int damage = held.getDamageValue();
        int max = held.getMaxDamage();
        int durability = max - damage;
        if (durability > DURABILITY_THRESHOLD) return;

        // Find a fresher tool of the same type
        for (int i = 0; i < 36; i++) {
            if (i == slot) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != held.getItem()) continue;

            int otherDur = stack.getMaxDamage() - stack.getDamageValue();
            if (otherDur > DURABILITY_THRESHOLD) {
                // Swap
                inv.setItem(slot, stack);
                inv.setItem(i, held.copy());
                break;
            }
        }
    }
}
