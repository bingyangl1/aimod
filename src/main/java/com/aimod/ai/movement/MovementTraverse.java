package com.aimod.ai.movement;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.ai.pathing.MoveCost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Flat-ground movement: walk from src to dest on the same Y level (or step up/down 1).
 * This is the most common movement type, used for horizontal traversal.
 *
 * If the destination has no solid floor, a bridge block is placed below it.
 */
public class MovementTraverse extends BotMovement {

    private static final double REACH_DIST_SQR = 0.25; // 0.5 blocks
    private int stuckTicks;
    private double lastDistSqr;

    public MovementTraverse(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        return MoveCost.costOf(level, src.getX(), src.getY(), src.getZ(),
                dest.getX() - src.getX(), dest.getY() - src.getY(), dest.getZ() - src.getZ());
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockPos floorBelowDest = dest.below();
            BlockState floorState = level.getBlockState(floorBelowDest);
            if (MoveCost.canWalkOn(level, floorBelowDest, floorState)) {
                status = Status.RUNNING;
                return true;
            }
            // Need to bridge
            status = Status.PREPPING;
            return true;
        }
        return status == Status.RUNNING || status == Status.PREPPING;
    }

    @Override
    public boolean update(FakePlayer bot) {
        if (status == Status.PENDING) {
            canExecute(bot);
        }

        // Prepping: place bridge block if needed
        if (status == Status.PREPPING) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockPos floorBelowDest = dest.below();
            BlockState floorState = level.getBlockState(floorBelowDest);
            if (!MoveCost.canWalkOn(level, floorBelowDest, floorState)) {
                var throwaway = findThrowawayBlock(bot);
                if (!throwaway.isEmpty() && throwaway.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                    level.setBlock(floorBelowDest, bi.getBlock().defaultBlockState(), 3);
                    throwaway.shrink(1);
                }
            }
            status = Status.RUNNING;
            return false;
        }

        // Running: move toward destination
        if (status == Status.RUNNING) {
            double dx = dest.getX() + 0.5 - bot.getX();
            double dy = dest.getY() - bot.getY();
            double dz = dest.getZ() + 0.5 - bot.getZ();
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (distSqr <= REACH_DIST_SQR) {
                bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
                status = Status.COMPLETE;
                return true;
            }

            // Stuck detection
            if (distSqr >= lastDistSqr - 0.01) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            lastDistSqr = distSqr;

            if (stuckTicks > 60) {
                status = Status.FAILED;
                return true;
            }

            double dist = Math.sqrt(distSqr);
            double speed = 0.216; // ~4.317 blocks/sec at 20 TPS
            double moveX = (dx / dist) * speed;
            double moveZ = (dz / dist) * speed;
            double moveY;

            if (dy > 0.3 && dy <= 1.5 && bot.onGround()) {
                moveY = 0.42; // step-up jump
            } else if (!bot.onGround()) {
                moveY = bot.getDeltaMovement().y;
            } else {
                moveY = dy < -0.5 ? -0.4 : 0;
            }

            Vec3 movement = new Vec3(moveX, moveY, moveZ);
            bot.setDeltaMovement(movement);
            bot.move(MoverType.SELF, movement);

            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
        }

        return false;
    }

    private static net.minecraft.world.item.ItemStack findThrowawayBlock(FakePlayer bot) {
        var inventory = bot.getInventory();
        for (var pref : new net.minecraft.world.item.Item[]{
                net.minecraft.world.item.Items.DIRT,
                net.minecraft.world.item.Items.COBBLESTONE,
                net.minecraft.world.item.Items.COARSE_DIRT,
                net.minecraft.world.item.Items.STONE}) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                var stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == pref) return stack;
            }
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                if (!key.contains("_log") && !key.contains("_stem")) return stack;
            }
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
