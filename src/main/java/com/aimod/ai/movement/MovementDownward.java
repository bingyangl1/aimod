package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Downward movement: mine the block below to descend one level.
 */
public class MovementDownward extends BotMovement {

    private int breakProgress;
    private int cooldown;

    public MovementDownward(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dy = dest.getY() - src.getY();
        if (dy >= 0) return Double.POSITIVE_INFINITY;
        if (Math.abs(dest.getX() - src.getX()) > 1 || Math.abs(dest.getZ() - src.getZ()) > 1) return Double.POSITIVE_INFINITY;

        BlockPos destFloor = dest.below();
        BlockState feetState = level.getBlockState(dest);
        BlockState floorState = level.getBlockState(destFloor);
        if (!MoveCost.canWalkThrough(level, dest, feetState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkOn(level, destFloor, floorState)) return Double.POSITIVE_INFINITY;

        BlockPos blockToBreak = src.below();
        BlockState breakState = level.getBlockState(blockToBreak);
        if (breakState.isAir()) return Double.POSITIVE_INFINITY;
        if (breakState.getDestroySpeed(level, blockToBreak) < 0) return Double.POSITIVE_INFINITY;

        double hardness = breakState.getDestroySpeed(level, blockToBreak);
        return Math.max(1.0, hardness * 1.5) + MoveCost.WALK_ONE_BLOCK + MoveCost.BREAK_BASE;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockState breakState = level.getBlockState(src.below());
            if (breakState.isAir()) {
                status = Status.RUNNING;
                return true;
            }
            if (breakState.getDestroySpeed(level, src.below()) < 0) {
                status = Status.FAILED;
                return false;
            }
            status = Status.PREPPING;
            breakProgress = 0;
        }
        return status == Status.PREPPING || status == Status.RUNNING;
    }

    @Override
    public boolean update(FakePlayer bot) {
        if (status == Status.PENDING) canExecute(bot);
        if (status == Status.FAILED || status == Status.COMPLETE) return true;

        ServerLevel level = (ServerLevel) bot.level();

        if (status == Status.PREPPING) {
            if (cooldown > 0) { cooldown--; return false; }

            BlockPos blockToBreak = src.below();
            BlockState breakState = level.getBlockState(blockToBreak);
            if (breakState.isAir()) { status = Status.RUNNING; return false; }

            breakProgress++;
            int breakTime = Math.max(1, (int) (breakState.getDestroySpeed(level, blockToBreak) * 20));
            if (breakProgress >= breakTime) {
                level.destroyBlock(blockToBreak, true, bot);
                status = Status.RUNNING;
                cooldown = 5;
            }
            return false;
        }

        if (status == Status.RUNNING) {
            if (bot.getY() <= dest.getY() + 0.1 && bot.onGround()) {
                bot.setPos(bot.getX(), dest.getY(), bot.getZ());
                bot.setDeltaMovement(0, 0, 0);
                status = Status.COMPLETE;
                return true;
            }
            double moveY = bot.onGround() ? 0 : Math.max(bot.getDeltaMovement().y - 0.08, -0.5);
            bot.setDeltaMovement(0, moveY, 0);
            bot.move(MoverType.SELF, bot.getDeltaMovement());
        }
        return false;
    }
}