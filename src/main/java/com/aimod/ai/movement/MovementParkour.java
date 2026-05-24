package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Parkour jump: sprint-jump across a 1-2 block gap at the same Y level.
 */
public class MovementParkour extends BotMovement {

    private static final double REACH_DIST_SQR = 0.4;
    private int stuckTicks;
    private double lastDistSqr;
    private int gapWidth;
    private boolean jumped;

    public MovementParkour(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();

        if (dy != 0) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) + Math.abs(dz) < 2 || Math.abs(dx) > 2 || Math.abs(dz) > 2) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) > 0 && Math.abs(dz) > 0) return Double.POSITIVE_INFINITY;

        gapWidth = Math.max(Math.abs(dx), Math.abs(dz));
        if (gapWidth > 2) return Double.POSITIVE_INFINITY;

        if (!MoveCost.canWalkOn(level, src.below(), level.getBlockState(src.below()))) return Double.POSITIVE_INFINITY;

        BlockPos destFloor = dest.below();
        BlockState feetState = level.getBlockState(dest);
        BlockState floorState = level.getBlockState(destFloor);
        BlockState headState = level.getBlockState(dest.above());

        if (!MoveCost.canWalkThrough(level, dest, feetState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkThrough(level, dest.above(), headState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkOn(level, destFloor, floorState)) return Double.POSITIVE_INFINITY;

        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        for (int i = 1; i <= gapWidth; i++) {
            BlockPos gapPos = src.offset(stepX * i, 0, stepZ * i);
            if (MoveCost.canWalkOn(level, gapPos.below(), level.getBlockState(gapPos.below()))) return Double.POSITIVE_INFINITY;
        }

        return MoveCost.WALK_ONE_BLOCK * gapWidth + MoveCost.JUMP_ONE_BLOCK + 2.0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            if (!bot.onGround()) { status = Status.FAILED; return false; }
            status = Status.RUNNING;
            jumped = false;
        }
        return status == Status.RUNNING;
    }

    @Override
    public boolean update(FakePlayer bot) {
        if (status == Status.PENDING) canExecute(bot);
        if (status == Status.FAILED || status == Status.COMPLETE) return true;

        double dx = dest.getX() + 0.5 - bot.getX();
        double dy = dest.getY() - bot.getY();
        double dz = dest.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        if (distSqr <= REACH_DIST_SQR && bot.onGround()) {
            bot.setPos(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
            bot.setDeltaMovement(0, 0, 0);
            status = Status.COMPLETE;
            return true;
        }

        if (distSqr >= lastDistSqr - 0.01) { stuckTicks++; } else { stuckTicks = 0; }
        lastDistSqr = distSqr;
        if (stuckTicks > 50) { status = Status.FAILED; return true; }

        double distH = Math.sqrt(dx * dx + dz * dz);
        double moveX, moveZ, moveY;

        if (bot.onGround() && !jumped) {
            double jumpSpeed = 0.26;
            moveX = distH > 0.05 ? (dx / distH) * jumpSpeed : 0;
            moveZ = distH > 0.05 ? (dz / distH) * jumpSpeed : 0;
            moveY = 0.42;
            jumped = true;
        } else if (!bot.onGround()) {
            double airSpeed = 0.2;
            moveX = distH > 0.05 ? (dx / distH) * airSpeed : 0;
            moveZ = distH > 0.05 ? (dz / distH) * airSpeed : 0;
            moveY = Math.max(bot.getDeltaMovement().y - 0.08, -0.5);
        } else {
            double speed = 0.216;
            moveX = distH > 0.05 ? (dx / distH) * speed : 0;
            moveZ = distH > 0.05 ? (dz / distH) * speed : 0;
            moveY = 0;
        }

        Vec3 movement = new Vec3(moveX, moveY, moveZ);
        bot.setDeltaMovement(movement);
        bot.move(MoverType.SELF, movement);

        if (distH > 0.1) {
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
        }
        return false;
    }
}