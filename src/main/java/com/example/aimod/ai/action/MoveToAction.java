package com.example.aimod.ai.action;

import com.example.aimod.entity.AIBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

public class MoveToAction extends Action {
    private BlockPos targetPos;
    private double speed;

    public MoveToAction(BlockPos targetPos, double speed) {
        super("Move to " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.speed = speed;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        return bot.getNavigation().isDone();
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            PathNavigation navigation = bot.getNavigation();
            Path path = navigation.createPath(targetPos, 0);
            if (path != null) {
                navigation.moveTo(path, speed);
                status = ActionStatus.IN_PROGRESS;
            } else {
                status = ActionStatus.FAILED;
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            if (bot.getNavigation().isDone()) {
                status = ActionStatus.COMPLETED;
                return true;
            }
            // 检查是否到达目标附近
            double distance = bot.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
            if (distance < 2.0) {
                bot.getNavigation().stop();
                status = ActionStatus.COMPLETED;
                return true;
            }
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }
}