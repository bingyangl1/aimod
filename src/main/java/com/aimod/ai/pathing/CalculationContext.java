package com.aimod.ai.pathing;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of world state and bot capabilities for pathfinding.
 * Inspired by Baritone CalculationContext (LGPL-3.0).
 *
 * Problem: A* pathfinder runs on background thread, but ServerLevel.getBlockState()
 * is not thread-safe. This class captures all block states into a thread-safe snapshot.
 */
public final class CalculationContext {

    private final ServerLevel level;
    private final Map<Long, BlockState> blockStateCache;
    private final ToolSet toolSet;
    private final boolean hasThrowaway;
    private final boolean hasWaterBucket;
    private final boolean allowBreak;
    private final boolean allowPlace;
    private final boolean allowSprint;
    private final boolean allowWaterBucketFall;
    private final int maxFallBlocks;
    private final double fallDamageThreshold;

    public CalculationContext(ServerLevel level, FakePlayer bot) {
        this(level, bot, defaultConfig());
    }

    public CalculationContext(ServerLevel level, FakePlayer bot, Config config) {
        this.level = level;
        this.blockStateCache = new HashMap<>();
        this.toolSet = new ToolSet(bot);

        var inventory = bot.getInventory();
        boolean foundThrowaway = false;
        boolean foundWaterBucket = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) foundThrowaway = true;
            if (stack.getItem() == net.minecraft.world.item.Items.WATER_BUCKET) foundWaterBucket = true;
            if (foundThrowaway && foundWaterBucket) break;
        }
        this.hasThrowaway = foundThrowaway;
        this.hasWaterBucket = foundWaterBucket;
        this.allowBreak = config.allowBreak;
        this.allowPlace = config.allowPlace;
        this.allowSprint = config.allowSprint;
        this.allowWaterBucketFall = config.allowWaterBucketFall;
        this.maxFallBlocks = config.maxFallBlocks;
        this.fallDamageThreshold = config.fallDamageThreshold;
    }

    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockState(int x, int y, int z) {
        long hash = blockPosHash(x, y, z);
        BlockState cached = blockStateCache.get(hash);
        if (cached != null) return cached;
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        blockStateCache.put(hash, state);
        return state;
    }

    public void preloadRegion(BlockPos center, int radius) {
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    long hash = blockPosHash(x, y, z);
                    if (!blockStateCache.containsKey(hash)) {
                        blockStateCache.put(hash, level.getBlockState(new BlockPos(x, y, z)));
                    }
                }
            }
        }
    }

    public boolean isLoaded(BlockPos pos) { return level.isLoaded(pos); }
    public ToolSet getToolSet() { return toolSet; }
    public boolean hasThrowaway() { return hasThrowaway; }
    public boolean hasWaterBucket() { return hasWaterBucket; }
    public boolean allowBreak() { return allowBreak; }
    public boolean allowPlace() { return allowPlace; }
    public boolean allowSprint() { return allowSprint; }
    public boolean allowWaterBucketFall() { return allowWaterBucketFall; }
    public int getMaxFallBlocks() { return maxFallBlocks; }
    public double getFallDamageThreshold() { return fallDamageThreshold; }
    public ServerLevel getLevel() { return level; }

    private static long blockPosHash(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static class Config {
        public final boolean allowBreak;
        public final boolean allowPlace;
        public final boolean allowSprint;
        public final boolean allowWaterBucketFall;
        public final int maxFallBlocks;
        public final double fallDamageThreshold;

        public Config(boolean allowBreak, boolean allowPlace, boolean allowSprint,
                      boolean allowWaterBucketFall, int maxFallBlocks, double fallDamageThreshold) {
            this.allowBreak = allowBreak;
            this.allowPlace = allowPlace;
            this.allowSprint = allowSprint;
            this.allowWaterBucketFall = allowWaterBucketFall;
            this.maxFallBlocks = maxFallBlocks;
            this.fallDamageThreshold = fallDamageThreshold;
        }
    }

    public static Config defaultConfig() {
        return new Config(true, true, true, true, 4, 3.0);
    }
}
