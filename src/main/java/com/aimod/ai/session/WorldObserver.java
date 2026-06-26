package com.aimod.ai.session;

import com.aimod.fakeplayer.FakePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Captures a snapshot of the bot's current world state.
 * Used by AgentLoop for context assembly and by SessionLog for recording.
 *
 * <p>Equivalent to OpenCode's System Context — provides the "observation"
 * that the LLM uses to make decisions.</p>
 */
public class WorldObserver {

    /**
     * Take a full snapshot of the bot's current world state.
     */
    public static JsonObject observe(FakePlayer bot) {
        JsonObject state = new JsonObject();

        // Position
        BlockPos pos = bot.blockPosition();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        state.add("position", posObj);

        // Health & food
        state.addProperty("health", bot.getHealth());
        state.addProperty("food", bot.getFoodData() != null ? bot.getFoodData().getFoodLevel() : 0);
        state.addProperty("onGround", bot.onGround());
        state.addProperty("inWater", bot.isInWater());

        // Time
        long time = bot.level().getDayTime();
        state.addProperty("timeOfDay", time % 24000);
        state.addProperty("isDay", (time % 24000) < 13000);

        // Biome
        try {
            var biome = bot.level().getBiome(pos);
            state.addProperty("biome", biome.unwrapKey()
                    .map(k -> k.toString()).orElse("unknown"));
        } catch (Exception e) {
            state.addProperty("biome", "unknown");
        }

        // Inventory summary
        state.add("inventory", summarizeInventory(bot));

        // Equipped items (main hand + armor)
        state.add("equipped", summarizeEquipment(bot));

        // Nearby blocks (top 10 by count)
        state.add("nearbyBlocks", summarizeNearbyBlocks(bot));

        return state;
    }

    private static JsonObject summarizeInventory(FakePlayer bot) {
        JsonObject inv = new JsonObject();
        var inventory = bot.getInventory();
        Map<String, Integer> itemCounts = new HashMap<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
                itemCounts.merge(id, stack.getCount(), Integer::sum);
            }
        }

        itemCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(20)
                .forEach(e -> inv.addProperty(e.getKey(), e.getValue()));

        return inv;
    }

    private static JsonObject summarizeEquipment(FakePlayer bot) {
        JsonObject equipped = new JsonObject();
        var inv = bot.getInventory();

        // Main hand
        var mainHand = inv.getItem(inv.selected);
        if (!mainHand.isEmpty()) {
            equipped.addProperty("mainhand", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(mainHand.getItem()).toString());
        }

        // Armor slots (36-39)
        String[] armorSlots = {"feet", "legs", "chest", "head"};
        for (int i = 0; i < 4; i++) {
            var stack = inv.getItem(36 + i);
            if (!stack.isEmpty()) {
                equipped.addProperty(armorSlots[i], net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString());
            }
        }

        // Offhand (slot 40)
        var offhand = inv.getItem(40);
        if (!offhand.isEmpty()) {
            equipped.addProperty("offhand", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(offhand.getItem()).toString());
        }

        return equipped;
    }

    private static JsonObject summarizeNearbyBlocks(FakePlayer bot) {
        JsonObject blocks = new JsonObject();
        BlockPos center = bot.blockPosition();
        int radius = 16;
        Map<String, Integer> blockCounts = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState state = bot.level().getBlockState(p);
                    if (!state.isAir()) {
                        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(state.getBlock()).toString();
                        blockCounts.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        blockCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> blocks.addProperty(e.getKey(), e.getValue()));

        return blocks;
    }
}
