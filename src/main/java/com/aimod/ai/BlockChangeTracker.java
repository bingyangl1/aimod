package com.aimod.ai;

import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks block changes in real-time to keep the OreIndex up-to-date.
 *
 * <p>Inspired by XRay's LevelMixin which intercepts setBlock() to detect
 * block changes without re-scanning entire chunks.</p>
 *
 * <p>When a block changes:
 * <ul>
 *   <li>If it becomes a target ore → add to OreIndex</li>
 *   <li>If it was a target ore and becomes air → remove from OreIndex</li>
 *   <li>If it's in a scanned chunk → invalidate chunk for rescan</li>
 * </ul>
 */
public class BlockChangeTracker {

    private static final TagKey<Block> ORE_TAG = TagKey.create(
            BuiltInRegistries.BLOCK.key(), ResourceLocation.parse("c:ores"));

    /** Fast ore check: blocks that are known ores (cached set). */
    private static final Set<Block> ORE_BLOCKS = new HashSet<>();

    /** Whether the ore block set has been initialized. */
    private static boolean initialized = false;

    /**
     * Initialize the ore block set from the registry.
     * Call once on server start.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        BuiltInRegistries.BLOCK.forEach(block -> {
            if (block.defaultBlockState().is(ORE_TAG)) {
                ORE_BLOCKS.add(block);
            }
        });

        // Also add common ores explicitly in case tag is missing
        addIfPresent(ORE_BLOCKS, "minecraft:diamond_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_diamond_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:iron_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_iron_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:gold_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_gold_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:coal_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_coal_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:copper_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_copper_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:redstone_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_redstone_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:lapis_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_lapis_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:emerald_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:deepslate_emerald_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:nether_gold_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:nether_quartz_ore");
        addIfPresent(ORE_BLOCKS, "minecraft:ancient_debris");

        DevLog.info("BLOCK_CHANGE_TRACKER_INIT", "ore_types={}", ORE_BLOCKS.size());
    }

    /**
     * Called when a block changes in the world.
     * Updates the OreIndex accordingly.
     *
     * @param level the world level
     * @param pos   the block position
     * @param newState the new block state
     * @param oreIndex the ore index to update (null if not available)
     */
    public static void onBlockChange(Level level, BlockPos pos, BlockState newState, OreIndex oreIndex) {
        if (oreIndex == null) return;

        ChunkPos chunkPos = new ChunkPos(pos);
        Block newBlock = newState.getBlock();
        boolean isNewOre = ORE_BLOCKS.contains(newBlock) || newState.is(ORE_TAG);

        if (isNewOre) {
            // New ore placed/found — add to index
            oreIndex.addOre(chunkPos, pos.immutable());
            DevLog.info("ORE_TRACKED", "block={}, pos={}", newBlock, pos.toShortString());
        }
        // Note: We don't remove ores when they become air, because
        // the mining action already handles that. Removal happens
        // when the bot successfully mines the block.
    }

    /**
     * Called when a block is successfully mined by the bot.
     * Removes the ore from the index.
     */
    public static void onBlockMined(Level level, BlockPos pos, OreIndex oreIndex) {
        if (oreIndex == null) return;
        ChunkPos chunkPos = new ChunkPos(pos);
        oreIndex.removeOre(chunkPos, pos);
    }

    /**
     * Check if a block is an ore.
     */
    public static boolean isOre(BlockState state) {
        return ORE_BLOCKS.contains(state.getBlock()) || state.is(ORE_TAG);
    }

    /**
     * Check if a block is a common non-ore block (blacklist for fast skip).
     * These blocks make up ~99% of the world and can be skipped quickly.
     */
    public static boolean isCommonNonOre(BlockState state) {
        Block block = state.getBlock();
        return state.isAir()
                || block == Blocks.STONE
                || block == Blocks.DEEPSLATE
                || block == Blocks.DIRT
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.GRAVEL
                || block == Blocks.SAND
                || block == Blocks.NETHERRACK
                || block == Blocks.END_STONE;
    }

    private static void addIfPresent(Set<Block> set, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block != Blocks.AIR) {
                set.add(block);
            }
        }
    }
}
