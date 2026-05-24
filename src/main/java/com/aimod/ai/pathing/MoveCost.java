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

public final class MoveCost {
    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK / WALK_ONE_BLOCK;
    public static final double WALK_IN_WATER = 20.0 / 2.2;
    public static final double DIAGONAL_MULTIPLIER = Math.sqrt(2.0);
    public static final double JUMP_ONE_BLOCK = 4.95;
    public static final double FALL_1_BLOCK = 3.0;
    public static final double FALL_N_BLOCK(int n) { return n * FALL_1_BLOCK; }
    public static final double BREAK_BASE = 6.0;
    public static final double BREAK_HARDNESS_MULT = 3.0;
    public static final double VOID_COST = 1000000.0;
    public static final double WATER_COST = WALK_IN_WATER;
    public static final int MAX_FALL_BLOCKS = 4;
    public static final double FALL_DAMAGE_THRESHOLD = 3.0;
    private MoveCost() {}
    public static final int[][] OFFSETS = {
        { 1, 0, 0, 0}, {-1, 0, 0, 0}, { 0, 0, 1, 0}, { 0, 0,-1, 0},
        { 1, 0, 1, 1}, {-1, 0, 1, 1}, { 1, 0,-1, 1}, {-1, 0,-1, 1},
        { 1, 1, 0, 2}, {-1, 1, 0, 2}, { 0, 1, 1, 2}, { 0, 1,-1, 2},
        { 1,-1, 0, 3}, {-1,-1, 0, 3}, { 0,-1, 1, 3}, { 0,-1,-1, 3},
    };
    public static double costOf(CalculationContext ctx, int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx; int ny = y + dy; int nz = z + dz;
        if (ny < -64 || ny > 319) return VOID_COST;
        BlockPos destFeet = new BlockPos(nx, ny, nz);
        BlockPos destHead = new BlockPos(nx, ny + 1, nz);
        BlockState feetState = ctx.getBlockState(destFeet);
        BlockState headState = ctx.getBlockState(destHead);
        if (!canWalkThrough(ctx, destHead, headState)) return VOID_COST;
        if (isLava(feetState) || isLava(ctx.getBlockState(destFeet.below()))) return VOID_COST;
        boolean fp = canWalkThrough(ctx, destFeet, feetState);
        if (dy == 0) return costH(ctx, nx, ny, nz, dx, dz, fp, feetState);
        else if (dy > 0) return costA(ctx, x, y, z, nx, ny, nz, dx, dz, fp, feetState);
        else return costD(ctx, x, y, z, nx, ny, nz, dx, dz, fp, feetState);
    }
    private static double costH(CalculationContext ctx, int nx, int ny, int nz, int dx, int dz, boolean fp, BlockState fs) {
        BlockPos bp = new BlockPos(nx, ny - 1, nz);
        BlockState bs = ctx.getBlockState(bp);
        double bc;
        if (fp) {
            if (canWalkOn(ctx, bp, bs)) { bc = isWater(fs) ? WATER_COST : (ctx.allowSprint() ? SPRINT_ONE_BLOCK : WALK_ONE_BLOCK); }
            else { int fb = findFD(ctx, nx, ny, nz); if (fb < 0) return VOID_COST; bc = fb == 0 ? FALL_1_BLOCK : FALL_N_BLOCK(fb + 1); }
        } else {
            if (!ctx.allowBreak()) return VOID_COST;
            if (isDestructible(fs) && !avoidBreaking(ctx, new BlockPos(nx, ny, nz), fs)) {
                if (!canWalkOn(ctx, bp, bs)) return VOID_COST; bc = WALK_ONE_BLOCK + bct(ctx, fs);
            } else return VOID_COST;
        }
        if (dx != 0 && dz != 0) {
            boolean a1 = canWalkThrough(ctx, new BlockPos(nx-dx, ny, nz), ctx.getBlockState(new BlockPos(nx-dx, ny, nz)));
            boolean a2 = canWalkThrough(ctx, new BlockPos(nx, ny, nz-dz), ctx.getBlockState(new BlockPos(nx, ny, nz-dz)));
            if (!a1 && !a2) return VOID_COST; bc *= DIAGONAL_MULTIPLIER;
        } return bc;
    }
    private static double costA(CalculationContext ctx, int x, int y, int z, int nx, int ny, int nz, int dx, int dz, boolean fp, BlockState fs) {
        BlockPos sp = new BlockPos(nx, ny - 1, nz);
        if (!canWalkOn(ctx, sp, ctx.getBlockState(sp))) return VOID_COST;
        if (!fp) {
            if (!ctx.allowBreak()) return VOID_COST;
            if (isDestructible(fs) && !avoidBreaking(ctx, new BlockPos(nx, ny, nz), fs)) return JUMP_ONE_BLOCK + bct(ctx, fs);
            return VOID_COST;
        }
        BlockPos as = new BlockPos(x, y + 1, z);
        if (!canWalkThrough(ctx, as, ctx.getBlockState(as))) return VOID_COST;
        return JUMP_ONE_BLOCK;
    }
    private static double costD(CalculationContext ctx, int x, int y, int z, int nx, int ny, int nz, int dx, int dz, boolean fp, BlockState fs) {
        if (!fp) {
            if (!ctx.allowBreak()) return VOID_COST;
            if (isDestructible(fs) && !avoidBreaking(ctx, new BlockPos(nx, ny, nz), fs)) {
                BlockPos df = new BlockPos(nx, ny - 1, nz);
                if (!canWalkOn(ctx, df, ctx.getBlockState(df))) return VOID_COST;
                return FALL_1_BLOCK + bct(ctx, fs);
            } return VOID_COST;
        }
        BlockPos df = new BlockPos(nx, ny - 1, nz);
        if (!canWalkOn(ctx, df, ctx.getBlockState(df))) return VOID_COST;
        return FALL_1_BLOCK;
    }
    private static int findFD(CalculationContext ctx, int nx, int sy, int nz) {
        for (int i = 1; i <= ctx.getMaxFallBlocks(); i++) {
            BlockPos cp = new BlockPos(nx, sy - i - 1, nz);
            if (canWalkOn(ctx, cp, ctx.getBlockState(cp))) return i - 1;
        } return -1;
    }
    private static double bct(CalculationContext ctx, BlockState state) {
        double speed = ctx.getToolSet().getStrVsBlock(state);
        if (speed <= 0) return 200;
        return 1.0 / speed + BREAK_BASE;
    }
    public static boolean canWalkOn(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (Block.isShapeFullBlock(state.getCollisionShape(ctx.getLevel(), pos))) return true;
        if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) return true;
        if (state.getBlock() instanceof StairBlock) return true;
        return false;
    }
    public static boolean canWalkThrough(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (isWater(state) || isLava(state)) return true;
        return state.isPathfindable(PathComputationType.LAND);
    }
    public static boolean avoidBreaking(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (state.is(Blocks.ICE)) return true;
        if (state.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) return true;
        if (isLava(ctx.getBlockState(pos.north())) || isLava(ctx.getBlockState(pos.south())) || isLava(ctx.getBlockState(pos.east())) || isLava(ctx.getBlockState(pos.west())) || isLava(ctx.getBlockState(pos.above()))) return true;
        return false;
    }
    public static double costOf(ServerLevel level, int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx; int ny = y + dy; int nz = z + dz;
        if (ny < -64 || ny > 319) return VOID_COST;
        BlockPos destFeet = new BlockPos(nx, ny, nz);
        BlockPos destHead = new BlockPos(nx, ny + 1, nz);
        BlockState feetState = level.getBlockState(destFeet);
        BlockState headState = level.getBlockState(destHead);
        if (!canWalkThrough(level, destHead, headState)) return VOID_COST;
        if (isLava(feetState) || isLava(level.getBlockState(destFeet.below()))) return VOID_COST;
        boolean fp = canWalkThrough(level, destFeet, feetState);
        if (dy == 0) return costHL(level, nx, ny, nz, dx, dz, fp, feetState);
        else if (dy > 0) return costAL(level, x, y, z, nx, ny, nz, dx, dz, fp, feetState);
        else return costDL(level, x, y, z, nx, ny, nz, dx, dz, fp, feetState);
    }
    private static double costHL(ServerLevel l, int nx, int ny, int nz, int dx, int dz, boolean fp, BlockState fs) {
        BlockPos bp = new BlockPos(nx, ny - 1, nz);
        BlockState bs = l.getBlockState(bp);
        double bc;
        if (fp) {
            if (canWalkOn(l, bp, bs)) bc = isWater(fs) ? WATER_COST : WALK_ONE_BLOCK;
            else { int fb = findFL(l, nx, ny, nz); if (fb < 0) return VOID_COST; bc = fb == 0 ? FALL_1_BLOCK : FALL_N_BLOCK(fb + 1); }
        } else {
            if (isDestructible(fs) && !avoidBreaking(l, new BlockPos(nx, ny, nz), fs)) {
                if (!canWalkOn(l, bp, bs)) return VOID_COST; bc = WALK_ONE_BLOCK + breakCost(fs);
            } else return VOID_COST;
        }
        if (dx != 0 && dz != 0) {
            boolean a1 = canWalkThrough(l, new BlockPos(nx-dx, ny, nz), l.getBlockState(new BlockPos(nx-dx, ny, nz)));
            boolean a2 = canWalkThrough(l, new BlockPos(nx, ny, nz-dz), l.getBlockState(new BlockPos(nx, ny, nz-dz)));
            if (!a1 && !a2) return VOID_COST; bc *= DIAGONAL_MULTIPLIER;
        } return bc;
    }
    private static double costAL(ServerLevel l, int x, int y, int z, int nx, int ny, int nz, int dx, int dz, boolean fp, BlockState fs) {
        BlockPos sp = new BlockPos(nx, ny - 1, nz);
        if (!canWalkOn(l, sp, l.getBlockState(sp))) return VOID_COST;
        if (!fp) {
            if (isDestructible(fs) && !avoidBreaking(l, new BlockPos(nx, ny, nz), fs)) return JUMP_ONE_BLOCK + breakCost(fs);
            return VOID_COST;
        }
        BlockPos as = new BlockPos(x, y + 1, z);
        if (!canWalkThrough(l, as, l.getBlockState(as))) return VOID_COST;
        return JUMP_ONE_BLOCK;
    }
    private static double costDL(ServerLevel l, int x, int y, int z, int nx, int ny, int nz, int dx, int dz, boolean fp, BlockState fs) {
        if (!fp) {
            if (isDestructible(fs) && !avoidBreaking(l, new BlockPos(nx, ny, nz), fs)) {
                BlockPos df = new BlockPos(nx, ny - 1, nz);
                if (!canWalkOn(l, df, l.getBlockState(df))) return VOID_COST;
                return FALL_1_BLOCK + breakCost(fs);
            } return VOID_COST;
        }
        BlockPos df = new BlockPos(nx, ny - 1, nz);
        if (!canWalkOn(l, df, l.getBlockState(df))) return VOID_COST;
        return FALL_1_BLOCK;
    }
    private static int findFL(ServerLevel l, int nx, int sy, int nz) {
        for (int i = 1; i <= MAX_FALL_BLOCKS; i++) {
            BlockPos cp = new BlockPos(nx, sy - i - 1, nz);
            if (canWalkOn(l, cp, l.getBlockState(cp))) return i - 1;
        } return -1;
    }
    public static boolean canWalkOn(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (Block.isShapeFullBlock(state.getCollisionShape(level, pos))) return true;
        if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) return true;
        if (state.getBlock() instanceof StairBlock) return true;
        return false;
    }
    public static boolean canWalkThrough(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (isWater(state) || isLava(state)) return true;
        return state.isPathfindable(PathComputationType.LAND);
    }
    public static boolean isWater(BlockState state) { return state.is(Blocks.WATER); }
    public static boolean isLava(BlockState state) { return state.is(Blocks.LAVA); }
    private static boolean isDestructible(BlockState state) { return state.getDestroySpeed(null, BlockPos.ZERO) >= 0; }
    private static double breakCost(BlockState state) { float h = state.getDestroySpeed(null, BlockPos.ZERO); if (h < 0) return 200; return BREAK_BASE + h * BREAK_HARDNESS_MULT; }
    public static boolean avoidBreaking(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.ICE)) return true;
        if (state.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) return true;
        if (isLava(level.getBlockState(pos.north())) || isLava(level.getBlockState(pos.south())) || isLava(level.getBlockState(pos.east())) || isLava(level.getBlockState(pos.west())) || isLava(level.getBlockState(pos.above()))) return true;
        return false;
    }
}