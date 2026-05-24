package com.aimod.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import com.aimod.AIMod;

@EventBusSubscriber(modid = AIMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class GameTestRegistry {

    @SubscribeEvent
    public static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(GameTestRegistry.class);
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 200)
    public static void testModLoads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testEntityRegistration(GameTestHelper helper) {
        try {
            var entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aimod", "ai_bot"));
            if (entityType.isPresent()) {
                helper.succeed();
            } else {
                helper.fail("AI Bot entity type not registered");
            }
        } catch (Exception e) {
            helper.fail("Entity registration check failed: " + e.getMessage());
        }
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testTaskCreation(GameTestHelper helper) {
        try {
            var task = new com.aimod.ai.Task("test task");
            if (task != null && task.getDescription().equals("test task")) {
                helper.succeed();
            } else {
                helper.fail("Task creation failed");
            }
        } catch (Exception e) {
            helper.fail("Task creation threw exception: " + e.getMessage());
        }
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testLLMResponseParsing(GameTestHelper helper) {
        try {
            String mockResponse = "{\"choices\": [{\"message\": {\"content\": \"{\\\"actions\\\": [{\\\"type\\\": \\\"move_to\\\", \\\"x\\\": 10, \\\"y\\\": 64, \\\"z\\\": 10}]}\"}}]}";
            var result = com.aimod.ai.llm.LLMResponseParser.parseResponse(mockResponse);
            if (result != null && result.isSuccess()) {
                helper.succeed();
            } else {
                helper.fail("LLM response parsing failed");
            }
        } catch (Exception e) {
            helper.fail("LLM parsing threw exception: " + e.getMessage());
        }
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testInventoryUtils(GameTestHelper helper) {
        try {
            var inv = new net.minecraft.world.SimpleContainer(36);
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5);
            inv.setItem(0, stack);
            int count = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() == net.minecraft.world.item.Items.DIAMOND) {
                    count += s.getCount();
                }
            }
            if (count == 5) {
                helper.succeed();
            } else {
                helper.fail("Expected 5 diamonds, got " + count);
            }
        } catch (Exception e) {
            helper.fail("InventoryUtils test failed: " + e.getMessage());
        }
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testPathfinderBasic(GameTestHelper helper) {
        try {
            var level = helper.getLevel();
            var start = new net.minecraft.core.BlockPos(0, 1, 0);
            var goal = new net.minecraft.core.BlockPos(5, 1, 5);
            var pathfinder = new com.aimod.ai.pathing.Pathfinder(level, start, goal);
            var result = pathfinder.findPath();
            if (result.isFound()) {
                helper.succeed();
            } else {
                helper.fail("Pathfinder could not find path on flat ground");
            }
        } catch (Exception e) {
            helper.fail("Pathfinder test failed: " + e.getMessage());
        }
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testChunkCacheCreation(GameTestHelper helper) {
        try {
            var level = helper.getLevel();
            var cache = new com.aimod.ai.cache.ChunkCache(level);
            if (cache != null) {
                helper.succeed();
            } else {
                helper.fail("ChunkCache creation returned null");
            }
        } catch (Exception e) {
            helper.fail("ChunkCache test failed: " + e.getMessage());
        }
    }

    // ── CommandParser ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testCommandParserCraft(GameTestHelper helper) {
        var p = com.aimod.ai.planner.CommandParser.parse("制作一把钻石镐给我");
        if (p.verb() == com.aimod.ai.planner.CommandParser.Verb.CRAFT && p.isGive()) {
            helper.succeed();
        } else helper.fail("Craft parse failed: verb=" + p.verb() + " give=" + p.isGive());
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testCommandParserMine(GameTestHelper helper) {
        var p = com.aimod.ai.planner.CommandParser.parse("挖5个铁矿石");
        if (p.verb() == com.aimod.ai.planner.CommandParser.Verb.MINE && p.count() == 5) {
            helper.succeed();
        } else helper.fail("Mine parse: verb=" + p.verb() + " count=" + p.count());
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testCommandParserGather(GameTestHelper helper) {
        var p = com.aimod.ai.planner.CommandParser.parse("砍3棵树");
        if (p.verb() == com.aimod.ai.planner.CommandParser.Verb.GATHER && p.count() == 3) {
            helper.succeed();
        } else helper.fail("Gather parse: verb=" + p.verb() + " count=" + p.count());
    }

    // ── Item Lookup ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testItemLookupDiamondPickaxe(GameTestHelper helper) {
        var item = com.aimod.ai.planner.CommandParser.findItem("diamond_pickaxe");
        if (item != null && item == net.minecraft.world.item.Items.DIAMOND_PICKAXE) helper.succeed();
        else helper.fail("diamond_pickaxe not found, got: " + item);
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testItemLookupIronOre(GameTestHelper helper) {
        var item = com.aimod.ai.planner.CommandParser.findItem("iron_ore");
        if (item != null && item == net.minecraft.world.item.Items.IRON_ORE) helper.succeed();
        else helper.fail("iron_ore not found, got: " + item);
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testItemLookupNotFound(GameTestHelper helper) {
        var item = com.aimod.ai.planner.CommandParser.findItem("nonexistent_item_xyz");
        if (item == null) helper.succeed();
        else helper.fail("Should return null for nonexistent item, got: " + item);
    }

    // ── SequencePlanner ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testSequencePlannerMine(GameTestHelper helper) {
        var actions = com.aimod.ai.planner.SequencePlanner.planMine(
                net.minecraft.world.item.Items.DIAMOND_ORE, 5);
        if (!actions.isEmpty() && actions.get(0) instanceof com.aimod.ai.action.MineBlockAction m
                && m.getCount() == 5) helper.succeed();
        else helper.fail("Mine plan wrong: " + actions.size() + " actions");
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testSequencePlannerGather(GameTestHelper helper) {
        var actions = com.aimod.ai.planner.SequencePlanner.planGather(
                net.minecraft.world.item.Items.OAK_LOG, 16);
        if (!actions.isEmpty() && actions.get(0) instanceof com.aimod.ai.action.GatherResourceAction g
                && g.getCount() == 16) helper.succeed();
        else helper.fail("Gather plan wrong: " + actions.size() + " actions");
    }

    // ── MaterialTree ──

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testMaterialTreeSimple(GameTestHelper helper) {
        var inv = new com.aimod.ai.RecipeIndex.InventoryState() {
            @Override public int countItem(net.minecraft.world.item.Item item) { return 0; }
            @Override public boolean hasItem(net.minecraft.world.item.Item item, int count) { return false; }
        };
        var tree = new com.aimod.ai.craft.MaterialTree(net.minecraft.world.item.Items.STICK, 4);
        tree.build(inv, 5);
        var raw = tree.getRequiredRawMaterials();
        if (!raw.isEmpty()) helper.succeed();
        else helper.fail("MaterialTree returned no raw materials for stick");
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testMaterialTreeAlreadyHave(GameTestHelper helper) {
        var inv = new com.aimod.ai.RecipeIndex.InventoryState() {
            @Override public int countItem(net.minecraft.world.item.Item item) {
                return item == net.minecraft.world.item.Items.STICK ? 10 : 0;
            }
            @Override public boolean hasItem(net.minecraft.world.item.Item item, int count) {
                return countItem(item) >= count;
            }
        };
        var tree = new com.aimod.ai.craft.MaterialTree(net.minecraft.world.item.Items.STICK, 4);
        tree.build(inv, 5);
        var raw = tree.getRequiredRawMaterials();
        if (raw.getOrDefault(net.minecraft.world.item.Items.OAK_PLANKS, 0) == 0) helper.succeed();
        else helper.fail("Should not need planks when we already have sticks");
    }

    // ── ItemUid ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testItemUidSameForSameItem(GameTestHelper helper) {
        var a = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND);
        var b = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND);
        var uidA = com.aimod.ai.recipe.ItemUid.compute(a, com.aimod.ai.recipe.ItemUid.Context.RECIPE);
        var uidB = com.aimod.ai.recipe.ItemUid.compute(b, com.aimod.ai.recipe.ItemUid.Context.RECIPE);
        if (uidA.equals(uidB)) helper.succeed();
        else helper.fail("Same item should have same UID");
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testItemUidDifferentForDifferentItem(GameTestHelper helper) {
        var a = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND);
        var b = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
        var uidA = com.aimod.ai.recipe.ItemUid.compute(a, com.aimod.ai.recipe.ItemUid.Context.RECIPE);
        var uidB = com.aimod.ai.recipe.ItemUid.compute(b, com.aimod.ai.recipe.ItemUid.Context.RECIPE);
        if (!uidA.equals(uidB)) helper.succeed();
        else helper.fail("Different items should have different UIDs");
    }

    // ── BotInfo ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testBotInfoSerializedItemRoundTrip(GameTestHelper helper) {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1);
        var si = com.aimod.fakeplayer.BotInfo.SerializedItem.from(stack, 0);
        var restored = si.toStack();
        if (restored.getItem() == net.minecraft.world.item.Items.DIAMOND_PICKAXE && restored.getCount() == 1)
            helper.succeed();
        else helper.fail("Round trip failed");
    }

    // ── FindItemResult ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testFindItemResultBasic(GameTestHelper helper) {
        var r = new com.aimod.ai.InventoryUtils.FindItemResult(3, 64);
        if (r.found() && r.isHotbar() && r.slot() == 3 && r.count() == 64) helper.succeed();
        else helper.fail("FindItemResult basic properties wrong");
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testFindItemResultNotFound(GameTestHelper helper) {
        var r = com.aimod.ai.InventoryUtils.FindItemResult.NOT_FOUND;
        if (!r.found() && r.slot() == -1) helper.succeed();
        else helper.fail("NOT_FOUND should have slot=-1");
    }

    // ── BotAIStateMachine ──

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testStateMachineTransitions(GameTestHelper helper) {
        var sm = new com.aimod.ai.llm.BotAIStateMachine();
        if (sm.getCurrent() != com.aimod.ai.llm.BotAIStateMachine.State.IDLE)
        { helper.fail("Start state not IDLE"); return; }
        sm.startPlanning("test", 3);
        if (sm.getCurrent() != com.aimod.ai.llm.BotAIStateMachine.State.PLANNING)
        { helper.fail("Not PLANNING after startPlanning"); return; }
        sm.startExecuting();
        if (sm.getCurrent() != com.aimod.ai.llm.BotAIStateMachine.State.EXECUTING)
        { helper.fail("Not EXECUTING after startExecuting"); return; }
        sm.complete();
        if (sm.getCurrent() != com.aimod.ai.llm.BotAIStateMachine.State.COMPLETED)
        { helper.fail("Not COMPLETED after complete"); return; }
        helper.succeed();
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testStateMachinePauseResume(GameTestHelper helper) {
        var sm = new com.aimod.ai.llm.BotAIStateMachine();
        sm.startExecuting();
        sm.pause();
        if (sm.getCurrent() != com.aimod.ai.llm.BotAIStateMachine.State.PAUSED)
        { helper.fail("Not PAUSED"); return; }
        sm.resume();
        if (sm.getCurrent() != com.aimod.ai.llm.BotAIStateMachine.State.EXECUTING)
        { helper.fail("Not EXECUTING after resume"); return; }
        helper.succeed();
    }

    @GameTest(template = "aimod:empty", timeoutTicks = 400)
    public static void testStateMachineReplan(GameTestHelper helper) {
        var sm = new com.aimod.ai.llm.BotAIStateMachine();
        sm.startExecuting();
        sm.requestReplan();
        if (sm.getCurrent() == com.aimod.ai.llm.BotAIStateMachine.State.REPLAN) helper.succeed();
        else helper.fail("Not REPLAN");
    }

    // ── DangerZone ──

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testDangerZoneClassLoads(GameTestHelper helper) {
        // Verify DangerZone is loadable and basic methods exist
        try {
            var clz = com.aimod.ai.chain.DangerZone.class;
            var methods = clz.getDeclaredMethods();
            if (methods.length >= 4) helper.succeed();
            else helper.fail("DangerZone missing methods");
        } catch (Exception e) {
            helper.fail("DangerZone load failed: " + e.getMessage());
        }
    }

    // ── RecipeIndex ──

    @GameTest(template = "aimod:empty", timeoutTicks = 600)
    public static void testRecipeIndexBuilt(GameTestHelper helper) {
        var index = com.aimod.ai.RecipeIndex.getInstance();
        try {
            index.clear();
            index.build(helper.getLevel());
            if (index.isBuilt()) helper.succeed();
            else helper.fail("RecipeIndex not built");
        } catch (Exception e) {
            helper.fail("RecipeIndex build failed: " + e.getMessage());
        }
    }
}
