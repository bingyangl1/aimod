package com.aimod.ai;

import com.aimod.ai.recipe.ItemUid;
import com.aimod.util.DevLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recipe index for O(1) lookup by output item or input item.
 * Inspired by EMI's EmiRecipeManager indexing strategy.
 *
 * Supports:
 * - Lookup by output item (what recipe produces this item?)
 * - Lookup by input item (what recipes use this item?)
 * - Tag-aware ingredient matching (any planks, any logs, etc.)
 * - Catalyst vs consumed input distinction
 */
public final class RecipeIndex {

    private final Map<Item, List<IndexedRecipe>> byOutput = new ConcurrentHashMap<>();
    private final Map<Item, List<IndexedRecipe>> byInput = new ConcurrentHashMap<>();
    private final Map<Object, List<IndexedRecipe>> byOutputUid = new ConcurrentHashMap<>();
    private final Map<Object, List<IndexedRecipe>> byInputUid = new ConcurrentHashMap<>();
    private boolean built = false;

    private static final RecipeIndex INSTANCE = new RecipeIndex();

    private RecipeIndex() {}

    public static RecipeIndex getInstance() {
        return INSTANCE;
    }

    /**
     * Build the index from the server's recipe manager.
     * Call once when the server starts or recipes reload.
     */
    public void build(ServerLevel level) {
        byOutput.clear();
        byInput.clear();
        byOutputUid.clear();
        byInputUid.clear();

        RecipeManager manager = level.getRecipeManager();
        int count = 0;

        for (RecipeHolder<?> holder : manager.getRecipes()) {
            Recipe<?> recipe = holder.value();

            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) continue;

            Item outputItem = result.getItem();
            if (outputItem == Items.AIR) continue;

            RecipeType<?> type = recipe.getType();
            RecipeCategory category = classifyRecipe(type);

            List<Ingredient> allIngredients = recipe.getIngredients();
            List<IngredientEntry> consumedInputs = new ArrayList<>();
            List<Item> catalysts = new ArrayList<>();

            if (category == RecipeCategory.CRAFTING) {
                catalysts.add(Items.CRAFTING_TABLE);
            } else if (category == RecipeCategory.SMELTING) {
                catalysts.add(Items.FURNACE);
            } else if (category == RecipeCategory.BLASTING) {
                catalysts.add(Items.BLAST_FURNACE);
            } else if (category == RecipeCategory.SMOKING) {
                catalysts.add(Items.SMOKER);
            } else if (category == RecipeCategory.STONECUTTING) {
                catalysts.add(Items.STONECUTTER);
            }

            for (Ingredient ingredient : allIngredients) {
                if (ingredient.isEmpty()) continue;
                consumedInputs.add(new IngredientEntry(ingredient));
            }

            IndexedRecipe indexed = new IndexedRecipe(
                    holder, category, consumedInputs, catalysts,
                    outputItem, result.getCount()
            );

            // Index by output Item (backward compatible)
            byOutput.computeIfAbsent(outputItem, k -> new ArrayList<>()).add(indexed);

            // Index by output UID (NBT-aware, RECIPE context)
            Object outputUid = ItemUid.compute(result, ItemUid.Context.RECIPE);
            byOutputUid.computeIfAbsent(outputUid, k -> new ArrayList<>()).add(indexed);

            // Index by each input item and UID
            for (IngredientEntry entry : consumedInputs) {
                for (Item item : entry.getMatchingItems()) {
                    byInput.computeIfAbsent(item, k -> new ArrayList<>()).add(indexed);
                    // RECIPE context: broad match for recipe lookup
                    Object uid = ItemUid.compute(new ItemStack(item), ItemUid.Context.RECIPE);
                    byInputUid.computeIfAbsent(uid, k -> new ArrayList<>()).add(indexed);
                }
            }

            count++;
        }

        built = true;
        DevLog.info("RECIPE_INDEX_BUILT", "recipes={}, outputs={}, inputs={}, uidOutputs={}, uidInputs={}",
                count, byOutput.size(), byInput.size(), byOutputUid.size(), byInputUid.size());
    }

    /**
     * Get all recipes that produce the given item. O(1).
     */
    public List<IndexedRecipe> getRecipesForOutput(Item output) {
        return byOutput.getOrDefault(output, Collections.emptyList());
    }

    /**
     * Get all recipes that use the given item as input. O(1).
     */
    public List<IndexedRecipe> getRecipesUsingInput(Item input) {
        return byInput.getOrDefault(input, Collections.emptyList());
    }

    /**
     * NBT-aware: get recipes that produce an item matching the given stack.
     * For items with subtypes (enchanted books, potions), use strict matching.
     * For normal items, falls back to Item-based lookup. O(1).
     */
    public List<IndexedRecipe> getRecipesForOutput(ItemStack stack) {
        Object uid = ItemUid.compute(stack, ItemUid.Context.RECIPE);
        List<IndexedRecipe> result = byOutputUid.get(uid);
        if (result != null && !result.isEmpty()) return result;
        // Fallback to Item-based lookup
        return byOutput.getOrDefault(stack.getItem(), Collections.emptyList());
    }

    /**
     * NBT-aware: get recipes that use an item matching the given stack.
     * O(1).
     */
    public List<IndexedRecipe> getRecipesUsingInput(ItemStack stack) {
        Object uid = ItemUid.compute(stack, ItemUid.Context.RECIPE);
        List<IndexedRecipe> result = byInputUid.get(uid);
        if (result != null && !result.isEmpty()) return result;
        // Fallback to Item-based lookup
        return byInput.getOrDefault(stack.getItem(), Collections.emptyList());
    }

    /**
     * Find the best recipe for producing an item, preferring recipes
     * whose inputs are available in the given inventory.
     */
    public IndexedRecipe findBestRecipe(Item output, InventoryState inventory) {
        List<IndexedRecipe> recipes = getRecipesForOutput(output);
        if (recipes.isEmpty()) return null;

        // First pass: find recipes where all inputs are available
        IndexedRecipe bestAvailable = null;
        int fewestMissing = Integer.MAX_VALUE;

        for (IndexedRecipe recipe : recipes) {
            int missing = recipe.countMissingInputs(inventory);
            if (missing == 0) {
                // All inputs available - check if it's the simplest recipe
                if (bestAvailable == null || recipe.consumedInputs.size() < bestAvailable.consumedInputs.size()) {
                    bestAvailable = recipe;
                }
            } else if (missing < fewestMissing) {
                fewestMissing = missing;
            }
        }

        if (bestAvailable != null) return bestAvailable;

        // No recipe with all inputs available, return the one with fewest missing
        return recipes.stream()
                .min(Comparator.comparingInt(r -> r.countMissingInputs(inventory)))
                .orElse(recipes.get(0));
    }

    /**
     * Check what the bot can craft with current inventory.
     * Returns items that have at least one craftable recipe.
     */
    public List<Item> getCraftableItems(InventoryState inventory) {
        List<Item> result = new ArrayList<>();
        for (Map.Entry<Item, List<IndexedRecipe>> entry : byOutput.entrySet()) {
            for (IndexedRecipe recipe : entry.getValue()) {
                if (recipe.countMissingInputs(inventory) == 0) {
                    result.add(entry.getKey());
                    break;
                }
            }
        }
        return result;
    }

    public boolean isBuilt() {
        return built;
    }

    public void clear() {
        byOutput.clear();
        byInput.clear();
        byOutputUid.clear();
        byInputUid.clear();
        built = false;
    }

    /**
     * Classify a RecipeType into our simplified categories.
     */
    private RecipeCategory classifyRecipe(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) return RecipeCategory.CRAFTING;
        if (type == RecipeType.SMELTING) return RecipeCategory.SMELTING;
        if (type == RecipeType.BLASTING) return RecipeCategory.BLASTING;
        if (type == RecipeType.SMOKING) return RecipeCategory.SMOKING;
        if (type == RecipeType.STONECUTTING) return RecipeCategory.STONECUTTING;
        if (type == RecipeType.SMITHING) return RecipeCategory.SMITHING;
        if (type == RecipeType.CAMPFIRE_COOKING) return RecipeCategory.CAMPFIRE_COOKING;
        return RecipeCategory.OTHER;
    }

    // ==================== Inner Types ====================

    public enum RecipeCategory {
        CRAFTING, SMELTING, BLASTING, SMOKING, STONECUTTING, SMITHING, CAMPFIRE_COOKING, OTHER
    }

    /**
     * A recipe entry in the index with pre-computed metadata.
     */
    public static class IndexedRecipe {
        private final RecipeHolder<?> holder;
        private final RecipeCategory category;
        private final List<IngredientEntry> consumedInputs;
        private final List<Item> catalysts;
        private final Item outputItem;
        private final int outputCount;

        public IndexedRecipe(RecipeHolder<?> holder, RecipeCategory category,
                             List<IngredientEntry> consumedInputs, List<Item> catalysts,
                             Item outputItem, int outputCount) {
            this.holder = holder;
            this.category = category;
            this.consumedInputs = Collections.unmodifiableList(consumedInputs);
            this.catalysts = Collections.unmodifiableList(catalysts);
            this.outputItem = outputItem;
            this.outputCount = outputCount;
        }

        public RecipeHolder<?> getHolder() { return holder; }
        public RecipeCategory getCategory() { return category; }
        public List<IngredientEntry> getConsumedInputs() { return consumedInputs; }
        public List<Item> getCatalysts() { return catalysts; }
        public Item getOutputItem() { return outputItem; }
        public int getOutputCount() { return outputCount; }

        /**
         * Count how many input ingredients are missing from the inventory.
         * Returns 0 if all inputs are available.
         */
        public int countMissingInputs(InventoryState inventory) {
            int missing = 0;
            for (IngredientEntry entry : consumedInputs) {
                if (!entry.isAvailableIn(inventory)) {
                    missing++;
                }
            }
            return missing;
        }

        /**
         * Get total items needed (for planning).
         */
        public Map<Item, Integer> getTotalRequiredItems() {
            Map<Item, Integer> required = new LinkedHashMap<>();
            for (IngredientEntry entry : consumedInputs) {
                for (Item item : entry.getMatchingItems()) {
                    required.merge(item, entry.getCount(), Integer::sum);
                    break; // Just count the first matching item
                }
            }
            return required;
        }

        public ResourceLocation getId() {
            return holder.id();
        }

        @Override
        public String toString() {
            return "Recipe{" + outputItem + " x" + outputCount + " via " + category + "}";
        }
    }

    /**
     * Represents one ingredient slot in a recipe, with Tag awareness.
     * Inspired by EMI's EmiIngredient.
     */
    public static class IngredientEntry {
        private final Ingredient ingredient;
        private final Item[] matchingItems;
        private final int count;

        public IngredientEntry(Ingredient ingredient) {
            this.ingredient = ingredient;
            this.matchingItems = Arrays.stream(ingredient.getItems())
                    .map(ItemStack::getItem)
                    .distinct()
                    .toArray(Item[]::new);
            this.count = ingredient.getItems().length > 0 ? ingredient.getItems()[0].getCount() : 1;
        }

        /**
         * Check if the inventory has at least one of the matching items.
         * Supports Tag matching (e.g., any planks).
         */
        public boolean isAvailableIn(InventoryState inventory) {
            for (Item item : matchingItems) {
                if (inventory.countItem(item) >= count) return true;
            }
            return false;
        }

        /**
         * Find the first available item in the inventory that matches.
         */
        public Item findAvailableItem(InventoryState inventory) {
            for (Item item : matchingItems) {
                if (inventory.countItem(item) >= count) return item;
            }
            return null;
        }

        public Item[] getMatchingItems() { return matchingItems; }
        public int getCount() { return count; }
        public Ingredient getIngredient() { return ingredient; }

        /**
         * Whether this ingredient matches multiple items (Tag-based).
         */
        public boolean isTagLike() { return matchingItems.length > 1; }

        @Override
        public String toString() {
            if (matchingItems.length == 0) return "empty";
            if (matchingItems.length == 1) return matchingItems[0] + " x" + count;
            return matchingItems[0] + "|" + (matchingItems.length - 1) + " more x" + count;
        }
    }

    /**
     * Simple inventory state interface for checking item availability.
     */
    public interface InventoryState {
        int countItem(Item item);
        boolean hasItem(Item item, int count);

        static InventoryState of(com.aimod.fakeplayer.FakePlayer bot) {
            return com.aimod.ai.InventoryUtils.asInventoryState(bot);
        }
    }
}