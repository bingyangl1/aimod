package com.example.aimod.ai.action;

import com.example.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;

public abstract class Action {
    protected ActionStatus status;
    protected String description;

    public Action(String description) {
        this.description = description;
        this.status = ActionStatus.PENDING;
    }

    public abstract boolean canExecute(FakePlayer bot);
    public abstract void execute(FakePlayer bot);
    public abstract boolean isComplete(FakePlayer bot);

    public ActionStatus getStatus() { return status; }
    public void setStatus(ActionStatus status) { this.status = status; }
    public String getDescription() { return description; }

    protected double navigateTo(FakePlayer bot, BlockPos target, double speed) {
        double dx = target.getX() + 0.5 - bot.getX();
        double dy = target.getY() - bot.getY();
        double dz = target.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;
        double dist = Math.sqrt(distSqr);
        if (dist > 0.1) {
            double moveX = (dx / dist) * speed * 0.05;
            double moveZ = (dz / dist) * speed * 0.05;
            bot.setDeltaMovement(moveX, bot.getDeltaMovement().y, moveZ);
            if (dy > 0.5 && dy <= 1.5 && bot.onGround()) { bot.jumpFromGround(); }
        }
        return distSqr;
    }

    protected void stopNavigation(FakePlayer bot) {
        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
    }

    public enum ActionStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
}