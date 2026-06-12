package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Use item in hand action: eat food, use bow, drink potion, etc.
 * The bot holds the item and uses it (right-click).
 */
public class UseItemAction extends Action {

    private final String itemId;
    private int useTicks;
    private static final int MAX_USE_TICKS = 100; // 5 seconds max

    public UseItemAction(String itemId) {
        super("Use " + itemId);
        this.itemId = itemId;
        this.useTicks = 0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        // Check if bot has the item in any slot
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
            // Find and equip the item
            var inventory = bot.getInventory();
            for (int i = 0; i <= 40; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    String stackId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).toString();
                    if (stackId.equals(itemId) || stackId.equals("minecraft:" + itemId)) {
                        if (i < 9) {
                            inventory.selected = i;
                        } else {
                            // Swap with current hotbar slot
                            int hotbarSlot = inventory.selected;
                            ItemStack hotbarItem = inventory.getItem(hotbarSlot);
                            inventory.setItem(hotbarSlot, stack);
                            inventory.setItem(i, hotbarItem);
                        }
                        break;
                    }
                }
            }
            status = ActionStatus.IN_PROGRESS;
            useTicks = 0;
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            DevLog.info("USE_ITEM_START", "item={}", itemId);
        }

        if (status == ActionStatus.IN_PROGRESS) {
            useTicks++;
            if (useTicks >= MAX_USE_TICKS) {
                bot.stopUsingItem();
                status = ActionStatus.COMPLETED;
                DevLog.info("USE_ITEM_DONE", "item={}, ticks={}", itemId, useTicks);
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
