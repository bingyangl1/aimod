package com.aimod.ai;

import com.aimod.fakeplayer.FakePlayer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class InventoryUtils {
    private InventoryUtils() {
    }

    public static int countItem(FakePlayer bot, Item item) {
        Inventory inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static Map<Item, Integer> missingItems(FakePlayer bot, Map<Item, Integer> requiredItems) {
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int available = countItem(bot, entry.getKey());
            if (available < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - available);
            }
        }
        return missing;
    }

    public static boolean hasItems(FakePlayer bot, Map<Item, Integer> requiredItems) {
        return missingItems(bot, requiredItems).isEmpty();
    }

    public static void consumeItems(FakePlayer bot, Map<Item, Integer> requiredItems) {
        Inventory inventory = bot.getInventory();
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int remaining = entry.getValue();
            for (int i = 0; i < 36 && remaining > 0; i++) {
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

    public static ItemStack removeItem(FakePlayer bot, Item item, int count) {
        Inventory inventory = bot.getInventory();
        ItemStack result = new ItemStack(item, 0);
        int remaining = count;
        for (int i = 0; i < 36 && remaining > 0; i++) {
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

    public static boolean addItem(FakePlayer bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        Inventory inventory = bot.getInventory();
        ItemStack remaining = stack.copy();
        for (int i = 0; i < 36; i++) {
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

        for (int i = 0; i < 36; i++) {
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

    /**
     * Create an InventoryState adapter for RecipeIndex queries.
     */
    public static RecipeIndex.InventoryState asInventoryState(FakePlayer bot) {
        return new RecipeIndex.InventoryState() {
            @Override public int countItem(Item item) { return InventoryUtils.countItem(bot, item); }
            @Override public boolean hasItem(Item item, int count) { return InventoryUtils.countItem(bot, item) >= count; }
        };
    }

    // ── FindItemResult ─────────────────────────────────────────────────

    /**
     * Search result for item finding operations.
     * Inspired by Meteor Client's FindItemResult record.
     */
    public record FindItemResult(int slot, int count) {
        public boolean found() { return slot != -1; }
        public boolean isHotbar() { return slot >= 0 && slot < 9; }
        public boolean isMainInventory() { return slot >= 9 && slot < 36; }
        public boolean isArmor() { return slot >= 36 && slot < 40; }
        public boolean isOffhand() { return slot == 40; }

        public net.minecraft.world.InteractionHand getHand() {
            if (slot == 40) return net.minecraft.world.InteractionHand.OFF_HAND;
            if (slot >= 0 && slot < 9) return net.minecraft.world.InteractionHand.MAIN_HAND;
            return null;
        }

        public static final FindItemResult NOT_FOUND = new FindItemResult(-1, 0);
    }

    /**
     * Find an item matching the predicate. Priority: offhand > main hand > hotbar > inventory.
     */
    public static FindItemResult find(FakePlayer bot, java.util.function.Predicate<ItemStack> predicate) {
        var inv = bot.getInventory();
        // Offhand
        ItemStack offhand = inv.getItem(40);
        if (predicate.test(offhand)) return new FindItemResult(40, offhand.getCount());
        // Hotbar (0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (predicate.test(stack)) return new FindItemResult(i, stack.getCount());
        }
        // Main inventory (9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (predicate.test(stack)) return new FindItemResult(i, stack.getCount());
        }
        return FindItemResult.NOT_FOUND;
    }

    /**
     * Find an item by exact type. Priority: offhand > main hand > hotbar > inventory.
     */
    public static FindItemResult find(FakePlayer bot, Item item) {
        return find(bot, stack -> stack.getItem() == item);
    }

    /**
     * Create an InventoryState that includes nearby containers (chests, barrels, etc.)
     * within searchRadius. Useful for the planner to check if materials are
     * already stored in nearby chests before deciding to mine/gather.
     */
    public static RecipeIndex.InventoryState asInventoryStateWithChests(FakePlayer bot, int searchRadius) {
        java.util.Map<Item, Integer> chestItems = new java.util.LinkedHashMap<>();
        var pos = bot.blockPosition();
        var level = bot.level();
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                if (dx * dx + dz * dz > searchRadius * searchRadius) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    var be = level.getBlockEntity(pos.offset(dx, dy, dz));
                    if (be instanceof net.minecraft.world.Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            var stack = c.getItem(i);
                            if (!stack.isEmpty()) chestItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
                        }
                    }
                }
            }
        }
        return new RecipeIndex.InventoryState() {
            @Override public int countItem(Item item) {
                return InventoryUtils.countItem(bot, item) + chestItems.getOrDefault(item, 0);
            }
            @Override public boolean hasItem(Item item, int count) {
                return countItem(item) >= count;
            }
        };
    }
}