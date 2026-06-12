package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.world.item.ItemStack;

/**
 * Drop item action: drop an item from the bot's inventory.
 * Supports dropping by item name and count.
 */
public class DropAction extends Action {

    private final String itemId;
    private final int count;

    public DropAction(String itemId, int count) {
        super("Drop " + count + " " + itemId);
        this.itemId = itemId;
        this.count = Math.max(1, count);
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        // Check if bot has the item
        var inventory = bot.getInventory();
        for (int i = 0; i <= 40; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String stackId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
                if (stackId.equals(itemId) || stackId.equals("minecraft:" + itemId)) {
                    return true;
                }
            }
        }
        setFailReason("背包中没有 " + itemId);
        return false;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
        }

        if (status == ActionStatus.IN_PROGRESS) {
            var inventory = bot.getInventory();
            int remaining = count;

            for (int i = 0; i <= 40 && remaining > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;

                String stackId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
                if (!stackId.equals(itemId) && !stackId.equals("minecraft:" + itemId)) continue;

                int drop = Math.min(remaining, stack.getCount());
                ItemStack dropped = stack.copy();
                dropped.setCount(drop);
                stack.shrink(drop);
                if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);

                // Drop the item as an entity
                bot.drop(dropped, false);
                remaining -= drop;
                DevLog.info("DROP_ITEM", "item={}, count={}", itemId, drop);
            }

            if (remaining <= 0) {
                status = ActionStatus.COMPLETED;
            } else {
                status = ActionStatus.COMPLETED; // dropped what we could
                DevLog.warn("DROP_PARTIAL", "item={}, wanted={}, dropped={}", itemId, count, count - remaining);
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
