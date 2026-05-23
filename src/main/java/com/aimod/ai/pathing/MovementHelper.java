package com.aimod.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Static helpers for block analysis and movement feasibility.
 * Adapted from Baritone's MovementHelper (LGPL-3.0).
 * 
 * Centralizes all block classification logic:
 * - canWalkOn: solid ground?
 * - canWalkThrough: passable?
 * - isWater/isLava: fluid detection
 * - getMiningDurationTicks: accurate break time
 * - avoidBreaking: should we avoid breaking this block?
 */
public final class MovementHelper {
    private MovementHelper() {}

    // ========== Block Classification ==========

    /**
     * Can the bot walk on this block? (i.e., is it solid ground?)
     * Baritone uses Block.isShapeFullBlock(collisionShape) for this.
     */
    public static boolean canWalkOn(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        // Full collision shape = solid
        return Block.isShapeFullBlock(state.getCollisionShape(level, pos));
    }

    /**
     * Can entities walk through this block?
     * Baritone uses PathComputationType.LAND for land movement.
     */
    public static boolean canWalkThrough(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (isWater(state) || isLava(state)) return true;
        return state.isPathfindable(PathComputationType.LAND);
    }

    /**
     * Is this block a water source or flowing water?
     */
    public static boolean isWater(BlockState state) {
        return state.is(Blocks.WATER);
    }

    public static boolean isLava(BlockState state) {
        return state.is(Blocks.LAVA);
    }

    public static boolean isLiquid(BlockState state) {
        return isWater(state) || isLava(state);
    }

    /**
     * Is this a bottom slab? (Half block you can walk on)
     */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
    }

    /**
     * Is this a ladder or vine? (Climbable block)
     */
    public static boolean isClimbable(BlockState state) {
        return state.getBlock() instanceof LadderBlock || state.getBlock() instanceof VineBlock;
    }

    // ========== Break Cost Calculation ==========

    /**
     * Get the mining duration in ticks for a block, considering the bot's best tool.
     * Returns COST_INF if the block is unbreakable.
     * 
     * @param toolSet The bot's tool set for calculating break speed
     * @param state The block state to break
     * @param includeFalling Whether to account for falling blocks above
     */
    public static double getMiningDurationTicks(ToolSet toolSet, BlockState state, boolean includeFalling) {
        double speed = toolSet.getStrVsBlock(state);
        if (speed <= 0) return MoveCost.VOID_COST; // Unbreakable
        double ticks = 1.0 / speed;
        
        // Add a base penalty for the action of mining (not just the speed)
        ticks += MoveCost.BREAK_BASE;
        
        return ticks;
    }

    // ========== Block Avoidance ==========

    /**
     * Should we avoid breaking this block?
     * - Ice: becomes water, disrupts path
     * - Infested blocks: spawn silverfish
     * - Blocks adjacent to lava: would cause lava flow
     */
    public static boolean avoidBreaking(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ICE) return true;
        if (block instanceof InfestedBlock) return true;
        
        // Check adjacent blocks for lava
        if (isAdjacentToLava(level, pos)) return true;
        
        // Check for falling blocks above
        BlockState above = level.getBlockState(pos.above());
        if (above.getBlock() instanceof FallingBlock) {
            BlockState belowAbove = level.getBlockState(pos.above().below());
            if (belowAbove.isAir()) return true; // Falling block would fall
        }
        
        return false;
    }

    private static boolean isAdjacentToLava(ServerLevel level, BlockPos pos) {
        return isLava(level.getBlockState(pos.north()))
            || isLava(level.getBlockState(pos.south()))
            || isLava(level.getBlockState(pos.east()))
            || isLava(level.getBlockState(pos.west()))
            || isLava(level.getBlockState(pos.above()));
    }

    // ========== Water/Lava Depth ==========

    /**
     * Is this water flowing (not a source block)?
     */
    public static boolean isFlowing(ServerLevel level, BlockPos pos, BlockState state) {
        if (!isWater(state)) return false;
        // In 1.21.1, check fluid state for level
        return state.getFluidState().isSource() == false;
    }
}