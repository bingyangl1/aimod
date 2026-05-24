package com.aimod.ai.planner;

import com.aimod.ai.RecipeIndex;
import com.aimod.ai.action.*;
import com.aimod.ai.craft.MaterialTree;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/**
 * Generates action sequences from MaterialTree recipe decomposition.
 * Checks inventory + nearby chests, provisions tools/furnace/fuel,
 * and cleans up placed blocks after use.
 */
public final class SequencePlanner {

    private static final String CRAFTING_TABLE_ID = "minecraft:crafting_table";

    // Pickaxe tier requirements for ores
    private static final Map<String, Integer> PICKAXE_TIER = Map.of(
        "minecraft:diamond_ore", 3, "minecraft:gold_ore", 3,
        "minecraft:emerald_ore", 3, "minecraft:ancient_debris", 3,
        "minecraft:iron_ore", 2, "minecraft:copper_ore", 2,
        "minecraft:lapis_ore", 2, "minecraft:redstone_ore", 2,
        "minecraft:coal_ore", 1, "minecraft:nether_quartz_ore", 1
    );

    // Wood tier pickaxes (can mine stone, coal)
    private static final List<Item> WOOD_PICKAXE = List.of(Items.WOODEN_PICKAXE);
    // Stone tier (can mine iron, copper)
    private static final List<Item> STONE_PICKAXE = List.of(Items.STONE_PICKAXE);
    // Iron tier (can mine diamond, gold, emerald)
    private static final List<Item> IRON_PICKAXE = List.of(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    // Fuel items with burn time (ticks). Higher = better.
    private static final Map<Item, Integer> FUEL_SCORE = new LinkedHashMap<>();
    static {
        FUEL_SCORE.put(Items.LAVA_BUCKET, 20000);
        FUEL_SCORE.put(Items.COAL_BLOCK, 16000);
        FUEL_SCORE.put(Items.DRIED_KELP_BLOCK, 4000);
        FUEL_SCORE.put(Items.BLAZE_ROD, 2400);
        FUEL_SCORE.put(Items.COAL, 1600);
        FUEL_SCORE.put(Items.CHARCOAL, 1600);
        FUEL_SCORE.put(Items.OAK_LOG, 300);
        FUEL_SCORE.put(Items.OAK_PLANKS, 300);
        FUEL_SCORE.put(Items.OAK_SLAB, 150);
        FUEL_SCORE.put(Items.STICK, 100);
        FUEL_SCORE.put(Items.OAK_SAPLING, 100);
    }

    public static List<Action> planCraftAndGive(
            FakePlayer bot, Item targetItem, int count, String playerName) {

        List<Action> actions = new ArrayList<>();
        RecipeIndex.InventoryState inv = com.aimod.ai.InventoryUtils.asInventoryStateWithChests(bot, 8);
        MaterialTree tree = new MaterialTree(targetItem, count);
        tree.build(inv, 6);

        Map<Item, Integer> raw = tree.getRequiredRawMaterials();
        if (raw.isEmpty() && tree.getRoot() == null) return actions;

        // Phase 0a: provision tools for mining
        int maxPickTier = 0;
        for (var entry : raw.entrySet()) {
            String blockId = findBlockForItem(entry.getKey());
            if (blockId != null) {
                maxPickTier = Math.max(maxPickTier, PICKAXE_TIER.getOrDefault(blockId, 0));
            }
        }
        if (maxPickTier > 0 && !hasPickaxe(bot, inv, maxPickTier)) {
            // Try to craft a pickaxe first
            Item pickaxe = getPickaxeForTier(maxPickTier);
            if (pickaxe != null && inv.countItem(Items.CRAFTING_TABLE) > 0) {
                // Recursively plan the pickaxe craft (shallow - just the pickaxe)
                MaterialTree pickTree = new MaterialTree(pickaxe, 1);
                pickTree.build(inv, 4);
                var pickRaw = pickTree.getRequiredRawMaterials();
                for (var e : pickRaw.entrySet()) {
                    if (inv.countItem(e.getKey()) >= e.getValue()) continue;
                    actions.add(mineOrGather(e.getKey(), e.getValue() - inv.countItem(e.getKey())));
                }
                BlockPos ptPos = placeWorkbench(actions, bot, inv);
                for (var step : pickTree.getCraftingSteps())
                    actions.add(makeCraftAction(step.getOutputItem(), 1));
                actions.add(makeCraftAction(pickaxe, 1));
                if (ptPos != null) actions.add(new BreakBlockAction(ptPos));
            }
        }

        // Phase 0b: smelting support (furnace + fuel)
        List<Item> smeltTargets = new ArrayList<>();
        boolean needsSmelting = false;
        for (var entry : raw.entrySet()) {
            if (needsSmelting(entry.getKey())) { needsSmelting = true; smeltTargets.add(entry.getKey()); }
        }
        BlockPos furnacePos = null;
        if (needsSmelting) {
            furnacePos = placeFurnace(actions, bot, inv);
            if (furnacePos != null) {
                actions.add(new InteractBlockAction(InteractBlockAction.InteractType.FURNACE, furnacePos));
                // Fuel check: ensure 1+ fuel per 8 items
                Item bestFuel = findBestFuel(inv);
                int fuelNeeded = (smeltTargets.stream().mapToInt(raw::get).sum() + 7) / 8;
                if (bestFuel != null && inv.countItem(bestFuel) < fuelNeeded) {
                    actions.add(mineOrGather(bestFuel, fuelNeeded));
                }
            }
        }

        // Phase 1: gather raw materials (mine ores, gather wood)
        for (var entry : raw.entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            if (inv.countItem(item) >= needed) continue;
            actions.add(mineOrGather(item, needed - inv.countItem(item)));
        }

        // Phase 2: workbench — reuse if exists, otherwise place
        boolean needsCraftingTable = !raw.isEmpty() || tree.getRoot() != null;
        BlockPos tablePos = null;
        if (needsCraftingTable) {
            tablePos = placeWorkbench(actions, bot, inv);
        }

        // Phase 3: craft intermediate + final
        if (needsCraftingTable && tree.getRoot() != null) {
            for (var recipe : tree.getCraftingSteps())
                actions.add(makeCraftAction(recipe.getOutputItem(), 1));
            actions.add(makeCraftAction(targetItem, count));
        } else if (needsCraftingTable) {
            actions.add(makeCraftAction(targetItem, count));
        }

        // Phase 4: clean up placed blocks
        if (tablePos != null) actions.add(new BreakBlockAction(tablePos));
        if (furnacePos != null) actions.add(new BreakBlockAction(furnacePos));

        // Phase 5: give
        if (playerName != null && !playerName.isBlank() && !playerName.equals("me")) {
            actions.add(new GiveItemAction(itemKey(targetItem), count, playerName));
        }

        return actions;
    }

    public static List<Action> planMine(Item targetItem, int count) {
        String blockId = findBlockForItem(targetItem);
        if (blockId == null) return List.of();
        return List.of(new MineBlockAction(blockId, count));
    }

    public static List<Action> planGather(Item targetItem, int count) {
        var type = classifyResource(targetItem);
        if (type == null) return List.of();
        return List.of(new GatherResourceAction(type, count));
    }

    // ---- Helpers ----

    /** Place a workbench if not already in inventory; return position or null. */
    private static BlockPos placeWorkbench(List<Action> actions, FakePlayer bot, RecipeIndex.InventoryState inv) {
        if (inv.countItem(Items.CRAFTING_TABLE) > 0) {
            // Already have one — place it
            return placeBlock(actions, bot, inv, Items.CRAFTING_TABLE,
                    net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        }
        // Need to craft one first
        if (inv.countItem(Items.OAK_PLANKS) >= 4 || inv.countItem(Items.OAK_LOG) >= 1) {
            if (inv.countItem(Items.OAK_PLANKS) < 4) {
                actions.add(new GatherResourceAction(GatherResourceAction.ResourceType.WOOD, 1));
            }
            // Can't craft without workbench — need to place wood logs and use 2x2 grid
        }
        // Place at bot's feet
        return placeBlock(actions, bot, inv, Items.CRAFTING_TABLE,
                net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
    }

    /** Place a furnace from inventory or craft one. Returns position or null. */
    private static BlockPos placeFurnace(List<Action> actions, FakePlayer bot, RecipeIndex.InventoryState inv) {
        if (inv.countItem(Items.FURNACE) > 0) {
            return placeBlock(actions, bot, inv, Items.FURNACE, Blocks.FURNACE);
        }
        // Need to craft furnace: 8 cobblestone
        if (inv.countItem(Items.COBBLESTONE) >= 8 || inv.countItem(Items.STONE) >= 8) {
            if (inv.countItem(Items.COBBLESTONE) < 8 && inv.countItem(Items.STONE) >= 8) {
                // Can't smelt stone to cobblestone without furnace. Just mine cobblestone.
                actions.add(new GatherResourceAction(GatherResourceAction.ResourceType.STONE, 8));
            }
            // Place workbench first, craft furnace, then place furnace
            BlockPos wbPos = placeWorkbench(actions, bot, inv);
            actions.add(new CraftAction("minecraft:furnace", 1));
            if (wbPos != null) actions.add(new BreakBlockAction(wbPos));
        } else {
            // Gather cobblestone first
            actions.add(new GatherResourceAction(GatherResourceAction.ResourceType.STONE, 8));
            BlockPos wbPos = placeWorkbench(actions, bot, inv);
            actions.add(new CraftAction("minecraft:furnace", 1));
            if (wbPos != null) actions.add(new BreakBlockAction(wbPos));
        }
        return placeBlock(actions, bot, inv, Items.FURNACE, Blocks.FURNACE);
    }

    /** Place a block from inventory, or add PlaceBlockAction. */
    private static BlockPos placeBlock(List<Action> actions, FakePlayer bot,
                                        RecipeIndex.InventoryState inv, Item blockItem, net.minecraft.world.level.block.Block block) {
        BlockPos pos = bot.blockPosition().offset(1, 0, 0);
        if (inv.countItem(blockItem) <= 0) {
            // Don't have one — skip placement (caller should have ensured availability)
            return null;
        }
        actions.add(new PlaceBlockAction(pos, (net.minecraft.world.item.BlockItem) blockItem));
        return pos;
    }

    /** Check if bot has a pickaxe of given tier or better. */
    private static boolean hasPickaxe(FakePlayer bot, RecipeIndex.InventoryState inv, int tier) {
        List<Item> required;
        switch (tier) {
            case 1: return inv.countItem(Items.WOODEN_PICKAXE) > 0
                || inv.countItem(Items.STONE_PICKAXE) > 0
                || inv.countItem(Items.IRON_PICKAXE) > 0
                || inv.countItem(Items.DIAMOND_PICKAXE) > 0
                || inv.countItem(Items.NETHERITE_PICKAXE) > 0;
            case 2: return inv.countItem(Items.STONE_PICKAXE) > 0
                || inv.countItem(Items.IRON_PICKAXE) > 0
                || inv.countItem(Items.DIAMOND_PICKAXE) > 0
                || inv.countItem(Items.NETHERITE_PICKAXE) > 0;
            case 3: return inv.countItem(Items.IRON_PICKAXE) > 0
                || inv.countItem(Items.DIAMOND_PICKAXE) > 0
                || inv.countItem(Items.NETHERITE_PICKAXE) > 0;
            default: return false;
        }
    }

    private static Item getPickaxeForTier(int tier) {
        return switch (tier) {
            case 1 -> Items.WOODEN_PICKAXE;
            case 2 -> Items.STONE_PICKAXE;
            case 3 -> Items.IRON_PICKAXE;
            default -> null;
        };
    }

    /** Find best fuel available in inventory/chests. */
    static Item findBestFuel(RecipeIndex.InventoryState inv) {
        Item best = null;
        int bestScore = 0;
        for (var entry : FUEL_SCORE.entrySet()) {
            if (inv.countItem(entry.getKey()) > 0 && entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                best = entry.getKey();
            }
        }
        return best != null ? best : Items.COAL; // default
    }

    private static Action mineOrGather(Item item, int shortage) {
        var resourceType = classifyResource(item);
        if (resourceType != null) return new GatherResourceAction(resourceType, shortage);
        String blockId = findBlockForItem(item);
        if (blockId != null) return new MineBlockAction(blockId, shortage);
        return new SayAction("Need " + shortage + "x " + item.getDescriptionId()); // fallback
    }

    private static CraftAction makeCraftAction(Item item, int count) {
        return new CraftAction(itemKey(item), count);
    }

    private static String itemKey(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Map an item to the block that produces it (ore→raw item, wood→log, etc.) */
    private static String findBlockForItem(Item item) {
        String key = itemKey(item);
        // Gems & crystals (drop directly, no smelting)
        if (key.contains("diamond")) return "minecraft:diamond_ore";
        if (key.contains("emerald")) return "minecraft:emerald_ore";
        if (key.contains("lapis_lazuli") || key.contains("lapis")) return "minecraft:lapis_ore";
        if (key.contains("redstone")) return "minecraft:redstone_ore";
        if (key.contains("quartz")) return "minecraft:nether_quartz_ore";
        if (key.contains("coal")) return "minecraft:coal_ore";
        if (key.contains("amethyst_shard")) return "minecraft:amethyst_cluster";
        // Ores that need smelting → mine the raw ore block
        if (key.contains("iron_ingot") || key.contains("iron_nugget") || key.contains("raw_iron"))
            return "minecraft:iron_ore";
        if (key.contains("gold_ingot") || key.contains("gold_nugget") || key.contains("raw_gold"))
            return "minecraft:gold_ore";
        if (key.contains("copper_ingot") || key.contains("raw_copper"))
            return "minecraft:copper_ore";
        if (key.contains("netherite_ingot") || key.contains("netherite_scrap"))
            return "minecraft:ancient_debris";
        // Wood derivatives → log
        if (key.contains("_planks") || key.contains("stick") || key.contains("_sign")
                || key.contains("_slab") || key.contains("_stairs") || key.contains("_fence")
                || key.contains("_door") || key.contains("_trapdoor") || key.contains("_button")
                || key.contains("_pressure_plate") || key.contains("bowl") || key.contains("chest_boat")
                || key.contains("crafting_table"))
            return "minecraft:oak_log";
        // Stone derivatives → stone
        if (key.contains("cobblestone") || key.contains("stone_") || key.contains("_stone"))
            return "minecraft:stone";
        return null;
    }

    /** Check if this item requires smelting (ore→ingot). */
    private static boolean needsSmelting(Item item) {
        String key = itemKey(item);
        return (key.contains("iron_ingot") && !key.contains("iron_ore"))
            || (key.contains("gold_ingot") && !key.contains("gold_ore"))
            || key.contains("copper_ingot");
    }

    private static GatherResourceAction.ResourceType classifyResource(Item item) {
        String key = itemKey(item);
        if (key.contains("log") || key.contains("wood") || key.contains("stem") || key.contains("hyphae"))
            return GatherResourceAction.ResourceType.WOOD;
        if (key.contains("stone") || key.contains("cobblestone") || key.contains("rock"))
            return GatherResourceAction.ResourceType.STONE;
        if (key.contains("dirt") || key.contains("grass"))
            return GatherResourceAction.ResourceType.DIRT;
        if (key.contains("sand"))
            return GatherResourceAction.ResourceType.SAND;
        return null;
    }
}
