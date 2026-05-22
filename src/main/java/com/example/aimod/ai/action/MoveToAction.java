package com.example.aimod.ai.action;

import com.example.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;

public class MoveToAction extends Action {
    private BlockPos targetPos;
    private double speed;

    public MoveToAction(BlockPos targetPos, double speed) {
        super("Move to " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.speed = speed;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            double distSqr = navigateTo(bot, targetPos, speed);
            if (distSqr < 2.0) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                return true;
            }
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public BlockPos getTargetPos() { return targetPos; }
}