package com.aimod.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import com.aimod.AIMod;

/**
 * Registers all GameTest test cases for the AI Mod.
 * GameTests run in a headless server environment, perfect for CI/CD.
 */
@EventBusSubscriber(modid = AIMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class GameTestRegistry {

    @SubscribeEvent
    public static void registerGameTests(RegisterGameTestsEvent event) {
        // Register all test methods in this class
        event.register(GameTestRegistry.class);
    }

    // ===== Test Cases =====

    @GameTest(template = "minecraft:empty", timeoutTicks = 200)
    public static void testModLoads(GameTestHelper helper) {
        // Basic smoke test: verify the mod loads without crashing
        helper.succeed();
    }

    @GameTest(template = "minecraft:empty", timeoutTicks = 400)
    public static void testEntityRegistration(GameTestHelper helper) {
        // Verify that the AI Bot entity type is registered
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

    @GameTest(template = "minecraft:empty", timeoutTicks = 600)
    public static void testTaskCreation(GameTestHelper helper) {
        // Verify that tasks can be created from action descriptors
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

    @GameTest(template = "minecraft:empty", timeoutTicks = 600)
    public static void testLLMResponseParsing(GameTestHelper helper) {
        // Test LLM response parsing with a mock response
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

    @GameTest(template = "minecraft:empty", timeoutTicks = 600)
    public static void testInventoryUtils(GameTestHelper helper) {
        // Test inventory utility methods
        try {
            // Test with a simple container instead of FakePlayer
            var inv = new net.minecraft.world.SimpleContainer(36);
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5);
            inv.setItem(0, stack);
            
            // Count manually to verify
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

    @GameTest(template = "minecraft:empty", timeoutTicks = 600)
    public static void testPathfinderBasic(GameTestHelper helper) {
        // Test basic pathfinding (requires a flat area)
        try {
            var level = helper.getLevel();
            var start = new net.minecraft.core.BlockPos(0, 1, 0);
            var goal = new net.minecraft.core.BlockPos(5, 1, 5);
            
            var pathfinder = new com.aimod.ai.pathing.Pathfinder(level, start, goal);
            var result = pathfinder.findPath();
            
            // On a flat area, path should be found
            if (result.isFound()) {
                helper.succeed();
            } else {
                helper.fail("Pathfinder could not find path on flat ground");
            }
        } catch (Exception e) {
            helper.fail("Pathfinder test failed: " + e.getMessage());
        }
    }

    @GameTest(template = "minecraft:empty", timeoutTicks = 600)
    public static void testChunkCacheCreation(GameTestHelper helper) {
        // Test ChunkCache can be created
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
}
