package com.example.aimod.ai;

import com.example.aimod.entity.AIBotEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class InventoryUtils {
    private InventoryUtils() {
    }

    public static int countItem(AIBotEntity bot, Item item) {
        SimpleContainer inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static Map<Item, Integer> missingItems(AIBotEntity bot, Map<Item, Integer> requiredItems) {
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int available = countItem(bot, entry.getKey());
            if (available < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - available);
            }
        }
        return missing;
    }

    public static boolean hasItems(AIBotEntity bot, Map<Item, Integer> requiredItems) {
        return missingItems(bot, requiredItems).isEmpty();
    }

    public static void consumeItems(AIBotEntity bot, Map<Item, Integer> requiredItems) {
        SimpleContainer inventory = bot.getInventory();
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int remaining = entry.getValue();
            for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == entry.getKey()) {
                    int used = Math.min(remaining, stack.getCount());
                    stack.shrink(used);
                    remaining -= used;
                    if (stack.isEmpty()) {
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    public static ItemStack removeItem(AIBotEntity bot, Item item, int count) {
        SimpleContainer inventory = bot.getInventory();
        ItemStack result = new ItemStack(item, 0);
        int remaining = count;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int taken = Math.min(remaining, stack.getCount());
                if (result.isEmpty()) {
                    result = new ItemStack(item, taken);
                } else {
                    result.grow(taken);
                }
                stack.shrink(taken);
                remaining -= taken;
                if (stack.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        return result;
    }

    public static boolean addItem(AIBotEntity bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        SimpleContainer inventory = bot.getInventory();
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int transferable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                if (transferable > 0) {
                    existing.grow(transferable);
                    remaining.shrink(transferable);
                }
            }
            if (remaining.isEmpty()) {
                return true;
            }
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, remaining.copy());
                return true;
            }
        }
        return false;
    }

    public static String describeItems(Map<Item, Integer> items) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : items.entrySet()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey().getDescriptionId()).append(" x").append(entry.getValue());
        }
        return builder.toString();
    }
}
