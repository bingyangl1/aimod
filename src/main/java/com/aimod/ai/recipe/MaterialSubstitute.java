package com.aimod.ai.recipe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Tag-aware material substitution engine.
 * Solves the "spruce_log can make spruce_planks, which match #planks" problem.
 *
 * <p>Core insight: Minecraft tags define equivalence groups.
 * If an item belongs to #minecraft:planks, it's equivalent to any other item in #planks.
 * If a log belongs to #minecraft:logs, it can be converted to planks that match #planks.</p>
 */
public final class MaterialSubstitute {
    private MaterialSubstitute() {}

    /** Known raw→processed conversions with yield. */
    private static final Map<TagKey<Item>, Conversion> PROCESSING_RULES = new LinkedHashMap<>();
    static {
        // Log → 4 planks (2x2 inventory crafting)
        PROCESSING_RULES.put(ItemTags.LOGS, new Conversion(ItemTags.PLANKS, 4, false));
        // Wood → 4 planks
        PROCESSING_RULES.put(ItemTags.LOGS_THAT_BURN, new Conversion(ItemTags.PLANKS, 4, false));
        // Bamboo block → 2 bamboo planks
        register("bamboo_blocks", "planks", 2, false);
    }

    public static class Conversion {
        public final TagKey<Item> outputTag;
        public final int yieldCount;
        public final boolean needsTable; // true = needs crafting table

        Conversion(TagKey<Item> out, int y, boolean table) { outputTag = out; yieldCount = y; needsTable = table; }
    }

    /**
     * Count how many items matching a Tag are available in inventory.
     * Includes items that can be converted from raw materials (logs→planks).
     */
    public static int countByTag(Inventory inv, TagKey<Item> tag) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.get(i);
            if (stack.isEmpty()) continue;
            if (stack.is(tag)) total += stack.getCount();
        }
        // Check for raw materials that can be converted
        for (var entry : PROCESSING_RULES.entrySet()) {
            if (entry.getValue().outputTag.equals(tag)) {
                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.get(i);
                    if (stack.isEmpty()) continue;
                    if (stack.is(entry.getKey())) {
                        total += stack.getCount() * entry.getValue().yieldCount;
                    }
                }
            }
        }
        return total;
    }

    /**
     * Find the best substitute for a specific needed item.
     * Returns the best matching item from inventory, or null if none available.
     * Prioritizes: exact match > same tag > raw conversion.
     */
    public static Item findBestSubstitute(Inventory inv, Item needed) {
        // Exact match first
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.get(i);
            if (!stack.isEmpty() && stack.getItem() == needed) return needed;
        }

        // Try same-tag alternatives
        ResourceLocation neededId = BuiltInRegistries.ITEM.getKey(needed);
        Set<TagKey<Item>> neededTags = findTags(needed);
        for (var tag : neededTags) {
            for (int i = 0; i < inv.size(); i++) {
                var stack = inv.get(i);
                if (stack.isEmpty()) continue;
                if (stack.is(tag) && stack.getItem() != needed) {
                    return stack.getItem(); // found tag-equivalent
                }
            }
        }

        // Try raw→processed conversion
        for (var entry : PROCESSING_RULES.entrySet()) {
            if (neededTags.contains(entry.getValue().outputTag)) {
                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.get(i);
                    if (stack.isEmpty()) continue;
                    if (stack.is(entry.getKey())) {
                        return stack.getItem(); // found raw material
                    }
                }
            }
        }
        return null;
    }

    /**
     * Auto-convert raw materials to satisfy crafting requirements.
     * Walks the inventory and converts logs→planks whenever a plank is needed.
     * @return total planks produced
     */
    public static int autoConvert(Inventory inv, Item needed) {
        int produced = 0;
        Set<TagKey<Item>> neededTags = findTags(needed);
        for (var entry : PROCESSING_RULES.entrySet()) {
            if (neededTags.contains(entry.getValue().outputTag)) {
                for (int i = 0; i < inv.size() && produced < 128; i++) {
                    var stack = inv.get(i);
                    if (stack.isEmpty()) continue;
                    if (stack.is(entry.getKey())) {
                        String itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                        String plankKey = itemKey
                            .replace("_log", "_planks").replace("_stem", "_planks")
                            .replace("_hyphae", "_planks").replace("_wood", "_planks")
                            .replace("bamboo_block", "bamboo_planks");
                        String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
                        Item plank = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(namespace + ":" + plankKey));
                        if (plank != null && plank != Items.AIR) {
                            int logs = stack.getCount();
                            stack.setCount(0); // consume all logs
                            int planks = logs * entry.getValue().yieldCount;
                            // Add planks to inventory
                            for (int j = 0; j < inv.size(); j++) {
                                var dst = inv.get(j);
                                if (dst.isEmpty()) {
                                    inv.set(j, new ItemStack(plank, Math.min(planks, 64)));
                                    planks -= Math.min(planks, 64);
                                    if (planks <= 0) break;
                                } else if (dst.getItem() == plank && dst.getCount() < 64) {
                                    int add = Math.min(planks, 64 - dst.getCount());
                                    dst.grow(add);
                                    planks -= add;
                                    if (planks <= 0) break;
                                }
                            }
                            produced += logs * entry.getValue().yieldCount;
                            if (produced >= 128) break;
                        }
                    }
                }
            }
        }
        return produced;
    }

    /** Find all tags an item belongs to. */
    private static Set<TagKey<Item>> findTags(Item item) {
        Set<TagKey<Item>> tags = new HashSet<>();
        // Only look at most common tags (avoid iterating ALL tags)
        for (var tagKey : List.of(ItemTags.PLANKS, ItemTags.LOGS, ItemTags.LOGS_THAT_BURN,
                ItemTags.STONE_CRAFTING_MATERIALS, ItemTags.STONE_TOOL_MATERIALS,
                ItemTags.WOODEN_TOOL_MATERIALS, ItemTags.COALS,
                ItemTags.WOOL, ItemTags.SAPLINGS, ItemTags.SAND)) {
            if (item.builtInRegistryHolder().is(tagKey)) tags.add(tagKey);
        }
        return tags;
    }

    /** Inventory abstraction. */
    public interface Inventory {
        ItemStack get(int slot);
        void set(int slot, ItemStack stack);
        int size();
    }

    /** FakePlayer adapter. */
    public static Inventory of(com.aimod.fakeplayer.FakePlayer bot) {
        var playerInv = bot.getInventory();
        return new Inventory() {
            @Override public ItemStack get(int slot) { return playerInv.getItem(slot); }
            @Override public void set(int slot, ItemStack stack) { playerInv.setItem(slot, stack); }
            @Override public int size() { return playerInv.getContainerSize(); }
        };
    }

    private static void register(String rawTag, String outTag, int yield, boolean needsTable) {
        var raw = ItemTags.create(ResourceLocation.fromNamespaceAndPath("minecraft", rawTag));
        var out = ItemTags.create(ResourceLocation.fromNamespaceAndPath("minecraft", outTag));
        PROCESSING_RULES.put(raw, new Conversion(out, yield, needsTable));
    }
}
