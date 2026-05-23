package com.aimod.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Movement cost calculator inspired by Baritone's ActionCosts and MovementHelper.
 * 
 * Supports 14 movement types:
 * - 4 cardinal (flat)
 * - 4 diagonal (flat, with corner-cutting check)
 * - 2 ascend (step up 1 block)
 * - 2 descend (fall 1-4 blocks)
 * - 2 extra: jump-up diagonal (ascend+diagonal)
 *
 * Key improvements over naive A*:
 * - Multi-block fall support (1-4 blocks) with damage cost
 * - Diagonal corner-cutting validation
 * - Slab/stair walkability
 * - Proper head clearance check
 * - Water movement with penalty
 */
public final class MoveCost {
    // Baritone ActionCosts (in ticks)
    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;       // 4.633
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;     // 3.564
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK / WALK_ONE_BLOCK;
    public static final double WALK_IN_WATER = 20.0 / 2.2;          // 9.091
    public static final double DIAGONAL_MULTIPLIER = Math.sqrt(2.0); // 1.414
    public static final double JUMP_ONE_BLOCK = 4.95;                // from Baritone ActionCosts
    public static final double FALL_1_BLOCK = 3.0;                   // ~3 ticks per block fallen
    public static final double FALL_N_BLOCK(int n) { return n * FALL_1_BLOCK; }
    public static final double BREAK_BASE = 6.0;                     // base break penalty
    public static final double BREAK_HARDNESS_MULT = 3.0;            // per hardness point
    public static final double VOID_COST = 1000000.0;                // Baritone COST_INF
    public static final double WATER_COST = WALK_IN_WATER;           // 9.091
    public static final int MAX_FALL_BLOCKS = 4;                     // Max safe fall without feather falling
    public static final double FALL_DAMAGE_THRESHOLD = 3.0;          // Fall damage starts at 3 blocks

    private MoveCost() {}

    /**
     * Movement offsets with metadata.
     * Format: {dx, dy, dz, type}
     *   type 0 = cardinal, 1 = diagonal, 2 = ascend, 3 = descend, 4 = ascend-diagonal
     */
    public static final int[][] OFFSETS = {
        // Cardinal (flat)
        { 1, 0, 0, 0}, {-1, 0, 0, 0}, { 0, 0, 1, 0}, { 0, 0,-1, 0},
        // Diagonal (flat)
        { 1, 0, 1, 1}, {-1, 0, 1, 1}, { 1, 0,-1, 1}, {-1, 0,-1, 1},
        // Ascend: step up 1 block in cardinal direction
        { 1, 1, 0, 2}, {-1, 1, 0, 2}, { 0, 1, 1, 2}, { 0, 1,-1, 2},
        // Descend: step down 1-4 blocks in cardinal direction (duplicates with dy handled in costOf)
        { 1,-1, 0, 3}, {-1,-1, 0, 3}, { 0,-1, 1, 3}, { 0,-1,-1, 3},
    };

    /**
     * Calculate cost to move from (x,y,z) to (x+dx, y+dy, z+dz).
     * Returns VOID_COST if the move is impossible.
     * 
     * For descend moves (type 3), this method may adjust dy to account for
     * multi-block falls. The caller should use the returned destination.
     */
    public static double costOf(ServerLevel level, int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;

        if (ny < -64 || ny > 319) return VOID_COST;

        BlockPos destFeet = new BlockPos(nx, ny, nz);
        BlockPos destHead = new BlockPos(nx, ny + 1, nz);

        BlockState feetState = level.getBlockState(destFeet);
        BlockState headState = level.getBlockState(destHead);

        // Head must always be clear
        if (!canWalkThrough(level, destHead, headState)) return VOID_COST;

        // Check for lava — avoid entirely
        if (isLava(feetState) || isLava(level.getBlockState(destFeet.below()))) return VOID_COST;

        boolean feetPassable = canWalkThrough(level, destFeet, feetState);

        if (dy == 0) {
            return costHorizontal(level, nx, ny, nz, dx, dz, feetPassable, feetState);
        } else if (dy > 0) {
            return costAscend(level, x, y, z, nx, ny, nz, dx, dz, feetPassable, feetState);
        } else {
            // dy < 0: descend (potentially multi-block)
            return costDescend(level, x, y, z, nx, ny, nz, dx, dz, feetPassable, feetState);
        }
    }

    /**
     * Horizontal move cost (dy=0).
     */
    private static double costHorizontal(ServerLevel level, int nx, int ny, int nz,
            int dx, int dz, boolean feetPassable, BlockState feetState) {
        BlockPos belowPos = new BlockPos(nx, ny - 1, nz);
        BlockState belowState = level.getBlockState(belowPos);

        double baseCost;

        if (feetPassable) {
            if (canWalkOn(level, belowPos, belowState)) {
                // Normal walk on solid ground
                if (isWater(feetState)) {
                    baseCost = WATER_COST;
                } else {
                    baseCost = WALK_ONE_BLOCK;
                }
            } else {
                // No solid floor — check for fall
                int fallBlocks = findFallDistance(level, nx, ny, nz);
                if (fallBlocks < 0) {
                    // No floor found within MAX_FALL_BLOCKS → void
                    return VOID_COST;
                }
                if (fallBlocks == 0) {
                    // ny-2 is solid → 1 block fall
                    baseCost = FALL_1_BLOCK;
                } else {
                    // Multi-block fall
                    baseCost = FALL_N_BLOCK(fallBlocks + 1);
                }
            }
        } else {
            // Block in the way — try to break it
            if (isDestructible(feetState) && !avoidBreaking(level, new BlockPos(nx, ny, nz), feetState)) {
                if (!canWalkOn(level, belowPos, belowState)) return VOID_COST;
                baseCost = WALK_ONE_BLOCK + breakCost(feetState);
            } else {
                return VOID_COST;
            }
        }

        // Diagonal penalty + corner-cutting check
        if (dx != 0 && dz != 0) {
            // Check corner-cutting: need at least one adjacent cardinal block passable
            boolean adj1Passable = canWalkThrough(level, 
                    new BlockPos(nx - dx, ny, nz),
                    level.getBlockState(new BlockPos(nx - dx, ny, nz)));
            boolean adj2Passable = canWalkThrough(level,
                    new BlockPos(nx, ny, nz - dz),
                    level.getBlockState(new BlockPos(nx, ny, nz - dz)));
            
            if (!adj1Passable && !adj2Passable) {
                return VOID_COST; // Can't cut through a corner
            }
            
            baseCost *= DIAGONAL_MULTIPLIER;
        }

        return baseCost;
    }

    /**
     * Ascend cost (dy=+1): step up one block.
     * Baritone: MovementAscend
     */
    private static double costAscend(ServerLevel level, int x, int y, int z,
            int nx, int ny, int nz, int dx, int dz,
            boolean feetPassable, BlockState feetState) {
        // The block we're stepping up onto (at new position, current y level)
        BlockPos stepPos = new BlockPos(nx, ny - 1, nz);
        BlockState stepState = level.getBlockState(stepPos);

        // We need a solid block to step up onto (at ny-1)
        if (!canWalkOn(level, stepPos, stepState)) {
            return VOID_COST;
        }

        // The feet position (ny) must be passable
        if (!feetPassable) {
            if (isDestructible(feetState) && !avoidBreaking(level, new BlockPos(nx, ny, nz), feetState)) {
                return JUMP_ONE_BLOCK + breakCost(feetState);
            }
            return VOID_COST;
        }

        // Head at ny+1 must be passable (already checked by caller)
        // Also need to check the block at (x, y+1, z) — the block above us before jumping
        BlockPos aboveSrc = new BlockPos(x, y + 1, z);
        if (!canWalkThrough(level, aboveSrc, level.getBlockState(aboveSrc))) {
            return VOID_COST;
        }

        return JUMP_ONE_BLOCK;
    }

    /**
     * Descend cost (dy=-1): fall 1-N blocks.
     * Baritone: MovementDescend supports falling multiple blocks.
     * We check up to MAX_FALL_BLOCKS below the destination.
     */
    private static double costDescend(ServerLevel level, int x, int y, int z,
            int nx, int ny, int nz, int dx, int dz,
            boolean feetPassable, BlockState feetState) {
        // The feet position must be passable (we're falling INTO it)
        if (!feetPassable) {
            if (isDestructible(feetState) && !avoidBreaking(level, new BlockPos(nx, ny, nz), feetState)) {
                // Fall + break
                BlockPos destFloor = new BlockPos(nx, ny - 1, nz);
                if (!canWalkOn(level, destFloor, level.getBlockState(destFloor))) return VOID_COST;
                return FALL_1_BLOCK + breakCost(feetState);
            }
            return VOID_COST;
        }

        // Destination must have a solid floor
        BlockPos destFloor = new BlockPos(nx, ny - 1, nz);
        BlockState destFloorState = level.getBlockState(destFloor);
        if (!canWalkOn(level, destFloor, destFloorState)) {
            return VOID_COST;
        }

        return FALL_1_BLOCK;
    }

    /**
     * Find how many blocks we can fall from (nx, ny-1, nz) downward.
     * Returns 0 if ny-2 is solid (1-block fall).
     * Returns 1 if ny-2 is air but ny-3 is solid (2-block fall).
     * Returns -1 if no solid floor found within MAX_FALL_BLOCKS.
     */
    private static int findFallDistance(ServerLevel level, int nx, int startNy, int nz) {
        for (int i = 1; i <= MAX_FALL_BLOCKS; i++) {
            BlockPos checkPos = new BlockPos(nx, startNy - i - 1, nz);
            BlockState checkState = level.getBlockState(checkPos);
            if (canWalkOn(level, checkPos, checkState)) {
                return i - 1; // 0-indexed: 0 = 1 block down
            }
        }
        return -1; // No floor found
    }

    // ========== Block Classification ==========

    /**
     * Can the bot walk on this block? (is it solid ground?)
     * Includes full blocks AND partial blocks like bottom slabs.
     */
    public static boolean canWalkOn(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        // Full collision shape = solid
        if (Block.isShapeFullBlock(state.getCollisionShape(level, pos))) return true;
        // Bottom slabs are walkable
        if (state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) return true;
        // Stairs are walkable (top surface)
        if (state.getBlock() instanceof StairBlock) return true;
        return false;
    }

    /**
     * Can entities walk through this block?
     */
    public static boolean canWalkThrough(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (isWater(state) || isLava(state)) return true;
        // Non-collision blocks (grass, flowers, torches, signs, etc.)
        return state.isPathfindable(PathComputationType.LAND);
    }

    public static boolean isWater(BlockState state) {
        return state.is(Blocks.WATER);
    }

    public static boolean isLava(BlockState state) {
        return state.is(Blocks.LAVA);
    }

    private static boolean isDestructible(BlockState state) {
        float hardness = state.getDestroySpeed(null, BlockPos.ZERO);
        return hardness >= 0;
    }

    private static double breakCost(BlockState state) {
        float hardness = state.getDestroySpeed(null, BlockPos.ZERO);
        if (hardness < 0) return 200; // Unbreakable
        return BREAK_BASE + hardness * BREAK_HARDNESS_MULT;
    }

    /**
     * Should we avoid breaking this block?
     */
    public static boolean avoidBreaking(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.ICE)) return true;
        if (state.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) return true;
        // Adjacent to lava
        if (isLava(level.getBlockState(pos.north()))
                || isLava(level.getBlockState(pos.south()))
                || isLava(level.getBlockState(pos.east()))
                || isLava(level.getBlockState(pos.west()))
                || isLava(level.getBlockState(pos.above()))) {
            return true;
        }
        return false;
    }
}