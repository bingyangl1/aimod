package com.aimod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Shared block scanner — one world pass per tick, dispatched to all consumers.
 * Inspired by Meteor Client's BlockIterator observer pattern.
 *
 * <p>Modules register callbacks with a radius. On each tick, the iterator
 * scans a 3D volume centered on the bot and calls all matching callbacks.
 * This avoids the O(n³) per-action scan that WorldScanner would otherwise
 * perform.</p>
 */
public class BlockIterator {

    private final net.minecraft.world.entity.Entity bot;
    private final List<Callback> callbacks = new ArrayList<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    public BlockIterator(net.minecraft.world.entity.Entity bot) {
        this.bot = bot;
    }

    /**
     * Register a scanning callback.
     * @param hRadius horizontal scan radius (X/Z)
     * @param vRadius vertical scan radius (Y)
     * @param consumer receives (BlockPos, BlockState) for each block in range
     */
    public void register(int hRadius, int vRadius, BiConsumer<BlockPos, BlockState> consumer) {
        callbacks.add(new Callback(hRadius, vRadius, consumer));
    }

    /**
     * Tick the scanner. Call once per server tick from the bot's tick loop.
     */
    public void tick() {
        if (!(bot.level() instanceof ServerLevel level)) return;
        if (callbacks.isEmpty()) return;

        BlockPos center = bot.blockPosition();

        // Find the max radius across all callbacks
        int maxH = 0, maxV = 0;
        for (Callback cb : callbacks) {
            if (cb.hRadius > maxH) maxH = cb.hRadius;
            if (cb.vRadius > maxV) maxV = cb.vRadius;
        }

        // Single 3D scan
        for (int dx = -maxH; dx <= maxH; dx++) {
            for (int dz = -maxH; dz <= maxH; dz++) {
                double distXZ = dx * dx + dz * dz;
                if (distXZ > maxH * maxH) continue;
                for (int dy = -maxV; dy <= maxV; dy++) {
                    mutablePos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(mutablePos);
                    if (state.isAir()) continue;

                    for (Callback cb : callbacks) {
                        if (Math.abs(dy) <= cb.vRadius && distXZ <= cb.hRadius * cb.hRadius) {
                            cb.consumer.accept(mutablePos, state);
                        }
                    }
                }
            }
        }
    }

    public int callbackCount() { return callbacks.size(); }

    // ── internal ──

    private static class Callback {
        final int hRadius, vRadius;
        final BiConsumer<BlockPos, BlockState> consumer;
        Callback(int h, int v, BiConsumer<BlockPos, BlockState> c) { hRadius = h; vRadius = v; consumer = c; }
    }
}
