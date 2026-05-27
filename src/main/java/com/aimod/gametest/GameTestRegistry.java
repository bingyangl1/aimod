package com.aimod.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import com.aimod.AIMod;
import com.aimod.ai.Task;
import com.aimod.ai.llm.LLMResponseParser;
import com.aimod.ai.pathing.Pathfinder;
import com.aimod.ai.pathing.PathResult;
import com.aimod.ai.cache.ChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@GameTestHolder("aimod")
@EventBusSubscriber(modid = AIMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class GameTestRegistry {

    @SubscribeEvent
    public static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(GameTestRegistry.class);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void testModLoads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void testEntityRegistration(GameTestHelper helper) {
        var key = ResourceLocation.fromNamespaceAndPath("aimod", "ai_bot");
        var found = BuiltInRegistries.ENTITY_TYPE.getOptional(key);
        if (found.isPresent()) {
            helper.succeed();
        } else {
            helper.fail("Entity type aimod:ai_bot not registered");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void testTaskCreation(GameTestHelper helper) {
        var task = new Task("test task");
        if ("test task".equals(task.getDescription())) {
            helper.succeed();
        } else {
            helper.fail("Task description mismatch");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void testLLMResponseParsing(GameTestHelper helper) {
        String mockResponse = """
            {"choices": [{"message": {"content": "{\\"actions\\": [{\\"type\\": \\"move_to\\"}]}"}}]}""";
        try {
            var result = LLMResponseParser.parseResponse(mockResponse);
            if (result.isSuccess()) {
                helper.succeed();
            } else {
                helper.fail("LLM response parsing returned non-success");
            }
        } catch (Exception e) {
            helper.fail("LLM parsing threw: " + e.getMessage());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void testInventoryUtils(GameTestHelper helper) {
        SimpleContainer inv = new SimpleContainer(36);
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        inv.setItem(0, stack);

        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(Items.DIAMOND)) {
                count += s.getCount();
            }
        }
        if (count == 5) {
            helper.succeed();
        } else {
            helper.fail("Expected 5 diamonds, got " + count);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void testPathfinderBasic(GameTestHelper helper) {
        ServerLevel level = (ServerLevel) helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos goal = helper.absolutePos(new BlockPos(4, 1, 4));

        try {
            PathResult result = new Pathfinder(level, start, goal).findPath();
            helper.succeed();
        } catch (Exception e) {
            helper.fail("Pathfinder threw: " + e.getMessage());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void testChunkCacheCreation(GameTestHelper helper) {
        ServerLevel level = (ServerLevel) helper.getLevel();
        try {
            ChunkCache cache = new ChunkCache(level);
            helper.succeed();
        } catch (Exception e) {
            helper.fail("ChunkCache creation threw: " + e.getMessage());
        }
    }
}
