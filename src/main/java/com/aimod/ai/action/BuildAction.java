package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build action — construct structures from blueprint files.
 *
 * <p>Supports a simple JSON blueprint format:
 * <pre>
 * {
 *   "name": "small_house",
 *   "blocks": [
 *     {"x": 0, "y": 0, "z": 0, "block": "minecraft:stone"},
 *     {"x": 1, "y": 0, "z": 0, "block": "minecraft:stone"},
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>Inspired by Baritone's BuilderProcess. Places blocks from a blueprint
 * file, navigating to each position and placing the correct block.</p>
 */
public class BuildAction extends Action {

    private static final int PLACE_TIMEOUT = 100; // ticks per block

    private final List<BlueprintBlock> blueprint;
    private final BlockPos origin;
    private int currentIndex;
    private int placeTicks;

    public BuildAction(List<BlueprintBlock> blueprint, BlockPos origin) {
        super("Build structure (" + blueprint.size() + " blocks)");
        this.blueprint = blueprint;
        this.origin = origin;
        this.currentIndex = 0;
        this.placeTicks = 0;
    }

    /**
     * Load a blueprint from a JSON file.
     */
    public static BuildAction fromFile(Path file, BlockPos origin) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        JsonArray blocks = json.getAsJsonArray("blocks");

        List<BlueprintBlock> blueprint = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject block = blocks.get(i).getAsJsonObject();
            int x = block.get("x").getAsInt();
            int y = block.get("y").getAsInt();
            int z = block.get("z").getAsInt();
            String blockId = block.get("block").getAsString();
            blueprint.add(new BlueprintBlock(x, y, z, blockId));
        }

        return new BuildAction(blueprint, origin);
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return bot.level() instanceof ServerLevel && !blueprint.isEmpty();
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            currentIndex = 0;
            DevLog.info("BUILD_START", "blocks={}", blueprint.size());
        }

        if (status == ActionStatus.IN_PROGRESS) {
            if (currentIndex >= blueprint.size()) {
                status = ActionStatus.COMPLETED;
                DevLog.info("BUILD_DONE", "placed={}", currentIndex);
                return;
            }

            BlueprintBlock target = blueprint.get(currentIndex);
            BlockPos worldPos = origin.offset(target.x(), target.y(), target.z());
            ServerLevel level = (ServerLevel) bot.level();

            // Check if block is already placed
            BlockState currentState = level.getBlockState(worldPos);
            Block targetBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(target.blockId()));
            if (targetBlock != Blocks.AIR && currentState.getBlock() == targetBlock) {
                currentIndex++;
                placeTicks = 0;
                return;
            }

            // Navigate to position
            double dist = bot.distanceToSqr(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
            if (dist > 6.25) {
                navigateTo(bot, worldPos, 1.0);
                return;
            }

            // Place the block
            placeTicks++;
            if (placeTicks > PLACE_TIMEOUT) {
                DevLog.warn("BUILD_PLACE_TIMEOUT", "pos={}, block={}", worldPos.toShortString(), target.blockId());
                currentIndex++;
                placeTicks = 0;
                return;
            }

            // Find the block item in inventory
            var inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (stackId.equals(target.blockId())) {
                        // Place the block
                        if (targetBlock != Blocks.AIR) {
                            level.setBlock(worldPos, targetBlock.defaultBlockState(), 3);
                            stack.shrink(1);
                            DevLog.info("BUILD_PLACED", "pos={}, block={}", worldPos.toShortString(), target.blockId());
                        }
                        currentIndex++;
                        placeTicks = 0;
                        return;
                    }
                }
            }

            // Block not in inventory
            DevLog.warn("BUILD_NO_BLOCK", "block={}", target.blockId());
            status = ActionStatus.FAILED;
            setFailReason("Missing block: " + target.blockId());
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    /** A single block in the blueprint. */
    public record BlueprintBlock(int x, int y, int z, String blockId) {}
}
