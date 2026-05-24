package com.aimod.ai.chain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Static danger detection utilities.
 * Inspired by AI-Player's CliffDetector + LavaDetector.
 */
public final class DangerZone {
    private DangerZone() {}

    /**
     * Check if there's a dangerous drop ahead.
     * @param entity the bot
     * @param maxDrop maximum safe drop distance (default 3)
     * @param lookAhead blocks to look ahead (default 3)
     */
    public static boolean isCliffAhead(LivingEntity entity, int maxDrop, int lookAhead) {
        Vec3 look = entity.getLookAngle();
        Level level = entity.level();
        BlockPos ahead = entity.blockPosition().offset(
                (int)(look.x * lookAhead), 0, (int)(look.z * lookAhead));

        // Scan downward for solid ground
        for (int y = ahead.getY(); y > ahead.getY() - maxDrop - 2; y--) {
            BlockPos check = new BlockPos(ahead.getX(), y, ahead.getZ());
            BlockState state = level.getBlockState(check);
            if (state.isSolid()) {
                return Math.abs(y - entity.blockPosition().getY()) > maxDrop;
            }
        }
        return true; // No solid ground within scan range = cliff
    }

    /** Cliff check with defaults: maxDrop=3, lookAhead=3 */
    public static boolean isCliffAhead(LivingEntity entity) {
        return isCliffAhead(entity, 3, 3);
    }

    /**
     * Check if lava is nearby within the given radius.
     */
    public static boolean isLavaNearby(LivingEntity entity, int radius) {
        BlockPos center = entity.blockPosition();
        Level level = entity.level();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -radius; dy <= radius; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() == Blocks.LAVA || state.getFluidState().is(Fluids.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Lava check with default radius=3 */
    public static boolean isLavaNearby(LivingEntity entity) {
        return isLavaNearby(entity, 3);
    }

    /** Check if lava is near a specific position. */
    public static boolean isLavaNearbyAt(LivingEntity entity, BlockPos center, int radius) {
        Level level = entity.level();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos pos = center.offset(dx, 0, dz);
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() == Blocks.LAVA || state.getFluidState().is(Fluids.LAVA)) return true;
                if (level.getBlockState(pos.below()).getBlock() == Blocks.LAVA) return true;
            }
        }
        return false;
    }

    /**
     * Check if the entity is in deep water (2+ blocks deep).
     */
    public static boolean isDeepWater(LivingEntity entity) {
        if (!entity.isInWater()) return false;
        BlockPos pos = entity.blockPosition();
        Level level = entity.level();
        // Check if there's water below
        return level.getBlockState(pos.below()).getFluidState().isSource()
                || level.getBlockState(pos.below(2)).getFluidState().isSource();
    }

    /**
     * Find the nearest safe direction (no lava, no cliff, solid ground).
     * Returns null if all directions are unsafe.
     */
    public static Direction findSafeDirection(LivingEntity entity) {
        BlockPos pos = entity.blockPosition();
        Level level = entity.level();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos target = pos.relative(dir);
            BlockPos below = target.below();
            BlockState there = level.getBlockState(target);
            BlockState belowThere = level.getBlockState(below);
            if (there.getBlock() == Blocks.LAVA || there.getFluidState().isSource()) continue;
            if (belowThere.getBlock() == Blocks.LAVA) continue;
            if (!belowThere.isSolid()) continue;
            return dir;
        }
        return null;
    }
}
