package com.example.aimod.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Movement cost calculator inspired by Baritone's ActionCosts (1.21.1 branch).
 * 
 * Cost constants (in ticks, matching Baritone):
 *   WALK       = 20/4.317 = 4.633
 *   SPRINT     = 20/5.612 = 3.564
 *   SNEAK      = 20/1.3   = 15.385
 *   WATER      = 20/2.2   = 9.091
 *   JUMP       = ~4.95 ticks (from ActionCosts)
 *   BREAK      = varies by hardness + tool
 */
public final class MoveCost {
    // Baritone ActionCosts (1.21.1)
    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;       // 4.633
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;     // 3.564
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK / WALK_ONE_BLOCK; // 0.769
    public static final double WALK_IN_WATER = 20.0 / 2.2;          // 9.091
    public static final double DIAGONAL_MULTIPLIER = Math.sqrt(2.0); // 1.414
    public static final double JUMP_ONE_BLOCK = 4.95;                // from ActionCosts
    public static final double FALL_1_BLOCK = 3.0;                   // ~3 ticks
    public static final double BREAK_BASE = 6.0;                     // base break penalty
    public static final double BREAK_HARDNESS_MULT = 3.0;            // per hardness point
    public static final double VOID_COST = 1000000.0;                // Baritone COST_INF
    public static final double WATER_COST = WALK_IN_WATER;           // 9.091

    private MoveCost() {}

    /**
     * Calculate cost to move from (x,y,z) to (x+dx, y+dy, z+dz).
     * Returns VOID_COST if the move is impossible.
     */
    public static double costOf(ServerLevel level, int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;

        // Bounds check
        if (ny < -64 || ny > 320) return VOID_COST;

        BlockPos feetPos = new BlockPos(nx, ny, nz);
        BlockPos headPos = new BlockPos(nx, ny + 1, nz);
        BlockPos belowPos = new BlockPos(nx, ny - 1, nz);

        BlockState feet = level.getBlockState(feetPos);
        BlockState head = level.getBlockState(headPos);
        BlockState below = level.getBlockState(belowPos);

        boolean feetPassable = canWalkThrough(level, feetPos, feet);
        boolean headPassable = canWalkThrough(level, headPos, head);
        boolean belowSolid = isSolid(level, belowPos, below);

        // Head must be clear
        if (!headPassable) return VOID_COST;

        // Check for lava — avoid entirely
        if (isLava(feet) || isLava(below)) return VOID_COST;

        double baseCost;

        if (dy == 0) {
            // Horizontal move
            if (feetPassable) {
                if (!belowSolid) {
                    // No floor — check 2 blocks below for fall
                    BlockPos floor2Pos = new BlockPos(nx, ny - 2, nz);
                    BlockState floor2 = level.getBlockState(floor2Pos);
                    if (isSolid(level, floor2Pos, floor2)) {
                        baseCost = FALL_1_BLOCK;
                    } else {
                        return VOID_COST;
                    }
                } else if (isWater(feet)) {
                    baseCost = WATER_COST;
                } else {
                    baseCost = WALK_ONE_BLOCK;
                }
            } else {
                // Block in the way — try to break it
                if (isDestructible(feet)) {
                    if (!belowSolid) return VOID_COST;
                    baseCost = WALK_ONE_BLOCK + breakCost(feet);
                } else {
                    return VOID_COST;
                }
            }
            // Diagonal penalty
            if (dx != 0 && dz != 0) {
                baseCost *= DIAGONAL_MULTIPLIER;
            }
            return baseCost;
        }

        if (dy == 1) {
            // Ascend: step up
            if (!feetPassable) {
                if (isDestructible(feet)) {
                    baseCost = JUMP_ONE_BLOCK + breakCost(feet);
                } else {
                    return VOID_COST;
                }
            } else {
                baseCost = JUMP_ONE_BLOCK;
            }
            // Need solid at (nx, ny-1, nz) or (nx, ny, nz) after breaking
            BlockPos stepPos = new BlockPos(nx, ny, nz);
            BlockState stepBlock = level.getBlockState(stepPos);
            if (!belowSolid && !isSolid(level, stepPos, stepBlock)) {
                return VOID_COST;
            }
            return baseCost;
        }

        if (dy == -1) {
            // Descend
            if (!feetPassable) {
                if (isDestructible(feet)) {
                    baseCost = FALL_1_BLOCK + breakCost(feet);
                } else {
                    return VOID_COST;
                }
            } else {
                baseCost = FALL_1_BLOCK;
            }
            // Need solid floor at destination
            BlockPos destFloor = new BlockPos(nx, ny - 1, nz);
            if (!isSolid(level, destFloor, level.getBlockState(destFloor))) {
                return VOID_COST;
            }
            return baseCost;
        }

        return VOID_COST;
    }

    private static double breakCost(BlockState state) {
        float hardness = state.getDestroySpeed(null, BlockPos.ZERO);
        if (hardness < 0) return 200;  // Unbreakable
        return BREAK_BASE + hardness * BREAK_HARDNESS_MULT;
    }

    /**
     * Can entities walk through this block?
     * Baritone 1.21.1 uses PathComputationType.WALKABLE for walkability checks.
     */
    private static boolean canWalkThrough(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        // Water/lava are walkable (with penalty) but not "solid"
        if (isWater(state) || isLava(state)) return true;
        // Non-collision blocks (grass, flowers, torches, etc.)
        return state.isPathfindable(PathComputationType.LAND);
    }

    /**
     * Is this block solid for standing on?
     * Baritone uses Block.isShapeFullBlock(state.getCollisionShape(null, null))
     */
    private static boolean isSolid(ServerLevel level, BlockPos pos, BlockState state) {
        return Block.isShapeFullBlock(state.getCollisionShape(level, pos));
    }

    private static boolean isDestructible(BlockState state) {
        float hardness = state.getDestroySpeed(null, BlockPos.ZERO);
        return hardness >= 0;
    }

    private static boolean isWater(BlockState state) {
        return state.is(Blocks.WATER);
    }

    private static boolean isLava(BlockState state) {
        return state.is(Blocks.LAVA);
    }

    /**
     * Movement offsets: 4 cardinal + 4 diagonal + ascend + descend.
     */
    public static final int[][] OFFSETS = {
        // Cardinal (flat)
        { 1, 0, 0}, {-1, 0, 0}, { 0, 0, 1}, { 0, 0,-1},
        // Diagonal (flat)
        { 1, 0, 1}, {-1, 0, 1}, { 1, 0,-1}, {-1, 0,-1},
        // Ascend / Descend
        { 0, 1, 0}, { 0,-1, 0}
    };
}