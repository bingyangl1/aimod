package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Multi-block fall: descend 2-4 blocks by walking off an edge.
 * Unlike MovementDescend (which steps down 1 block), this handles
 * cliffs and ravine edges by checking the fall landing zone.
 *
 * Safety: rejects falls that land in lava or void.
 */
public class MovementFall extends BotMovement {

    private static final double REACH_DIST_SQR = 1.0;
    private int stuckTicks;
    private double lastDistSqr;
    private int fallDistance; // how many blocks we expect to fall

    public MovementFall(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();

        // Must be horizontal move (dx/dz) with downward component
        if (dy >= 0) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) + Math.abs(dz) != 1) return Double.POSITIVE_INFINITY;

        fallDistance = -dy;
        if (fallDistance < 2 || fallDistance > MoveCost.MAX_FALL_BLOCKS) {
            return Double.POSITIVE_INFINITY;
        }

        // Validate landing: feet must be passable, floor must be solid
        BlockPos destFeet = dest;
        BlockPos destFloor = dest.below();
        BlockState feetState = level.getBlockState(destFeet);
        BlockState floorState = level.getBlockState(destFloor);

        if (!MoveCost.canWalkThrough(level, destFeet, feetState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkOn(level, destFloor, floorState)) return Double.POSITIVE_INFINITY;

        // Check for intermediate obstructions
        for (int i = 1; i < fallDistance; i++) {
            BlockPos mid = src.below(i);
            BlockState midState = level.getBlockState(mid);
            if (!MoveCost.canWalkThrough(level, mid, midState)) return Double.POSITIVE_INFINITY;
        }

        // Walk + fall cost
        double cost = MoveCost.WALK_ONE_BLOCK;
        cost += MoveCost.FALL_N_BLOCK(fallDistance);

        // Add fall damage cost if applicable
        if (fallDistance > (int) MoveCost.FALL_DAMAGE_THRESHOLD) {
            double damageBlocks = fallDistance - MoveCost.FALL_DAMAGE_THRESHOLD;
            cost += damageBlocks * 2.0; // penalty for damage risk
        }

        return cost;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockPos destFloor = dest.below();
            BlockState floorState = level.getBlockState(destFloor);
            BlockState feetState = level.getBlockState(dest);

            if (MoveCost.canWalkThrough(level, dest, feetState)
                    && MoveCost.canWalkOn(level, destFloor, floorState)) {
                status = Status.RUNNING;
                return true;
            }
            status = Status.FAILED;
            return false;
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

        // Stuck detection
        if (distSqr >= lastDistSqr - 0.01 && bot.getY() >= dest.getY() + 0.1) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastDistSqr = distSqr;
        if (stuckTicks > 60) {
            status = Status.FAILED;
            return true;
        }

        // Move horizontally toward dest, let gravity handle vertical
        double distH = Math.sqrt(dx * dx + dz * dz);
        double speed = 0.216;
        double moveX = distH > 0.05 ? (dx / distH) * speed : 0;
        double moveZ = distH > 0.05 ? (dz / distH) * speed : 0;
        double moveY = bot.getDeltaMovement().y;

        // Apply gravity if not on ground
        if (!bot.onGround()) {
            moveY = Math.max(moveY - 0.08, -0.5);
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