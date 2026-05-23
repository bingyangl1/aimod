package com.aimod.ai.recipe;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Objects;

/**
 * Computes stable UIDs for ItemStacks to enable NBT-aware recipe lookup.
 *
 * <p>Inspired by JEI's {@code ISubtypeInterpreter} + {@code UidContext} pattern
 * and EMI's {@code Comparison} system.</p>
 *
 * <p>Two contexts:
 * <ul>
 *   <li><b>RECIPE</b> — broad matching for recipe lookup (ignores durability, generic NBT)</li>
 *   <li><b>INVENTORY</b> — strict matching for inventory queries (considers NBT subtypes)</li>
 * </ul>
 * </p>
 */
public final class ItemUid {

    public enum Context { RECIPE, INVENTORY }

    private ItemUid() {}

    /**
     * Compute a stable UID for use as a HashMap key.
     *
     * @param stack the ItemStack
     * @param context matching strictness
     * @return the Item itself (for simple items) or a CompositeKey (for NBT-sensitive items)
     */
    public static Object compute(ItemStack stack, Context context) {
        if (stack.isEmpty()) return Items.AIR;
        Item item = stack.getItem();
        Object subtype = getSubtypeData(stack, context);
        return subtype != null ? new CompositeKey(item, subtype) : item;
    }

    /**
     * Extract subtype data for items that need NBT-aware distinction.
     * Returns null if the item has no meaningful subtype (treat all stacks as equal).
     */
    private static Object getSubtypeData(ItemStack stack, Context context) {
        Item item = stack.getItem();

        // Enchanted books: different enchantments = different items
        if (item == Items.ENCHANTED_BOOK) {
            ItemEnchantments enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);
            return enchantments != null ? enchantments : null;
        }

        // Potions and tipped arrows: different effects = different items
        if (item == Items.POTION || item == Items.SPLASH_POTION
                || item == Items.LINGERING_POTION || item == Items.TIPPED_ARROW) {
            var contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents != null ? contents.potion().orElse(null) : null;
        }

        // RECIPE context: ignore durability for tools/weapons/armor
        if (context == Context.RECIPE && stack.isDamageableItem()) {
            return null;
        }

        // Items with custom NBT (named items, custom data from commands)
        if (context == Context.INVENTORY) {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null && !customData.isEmpty()) {
                return customData.copyTag(); // Use tag hash for uniqueness
            }
        }

        return null;
    }

    /**
     * Composite key for items with subtypes. Must implement equals/hashCode
     * for correct HashMap behavior.
     */
    public static final class CompositeKey {
        private final Item item;
        private final Object subtype;

        CompositeKey(Item item, Object subtype) {
            this.item = item;
            this.subtype = subtype;
        }

        public Item item() { return item; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CompositeKey that)) return false;
            return item.equals(that.item) && subtype.equals(that.subtype);
        }

        @Override
        public int hashCode() {
            return 31 * item.hashCode() + subtype.hashCode();
        }

        @Override
        public String toString() {
            return item + "[" + subtype + "]";
        }
    }
}
