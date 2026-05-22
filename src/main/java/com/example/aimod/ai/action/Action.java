package com.example.aimod.ai.action;

import com.example.aimod.entity.AIBotEntity;
import net.minecraft.core.BlockPos;
import com.example.aimod.fakeplayer.FakePlayer;

import javax.annotation.Nullable;

public abstract class Action {
    protected ActionStatus status;
    protected String description;

    public Action(String description) {
        this.description = description;
        this.status = ActionStatus.PENDING;
    }

    public abstract boolean canExecute(AIBotEntity bot);
    public abstract void execute(AIBotEntity bot);
    public abstract boolean isComplete(AIBotEntity bot);

    public ActionStatus getStatus() {
        return status;
    }

    public void setStatus(ActionStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 获取假人玩家（如果可用）
     */
    @Nullable
    protected FakePlayer getFakePlayer(AIBotEntity bot) {
        return bot.getFakePlayer();
    }

    /**
     * 检查假人是否可用
     */
    protected boolean hasFakePlayer(AIBotEntity bot) {
        return bot.hasFakePlayer();
    }

    protected double navigateTo(AIBotEntity bot, net.minecraft.core.BlockPos target, double speed) {
        double dx = target.getX() + 0.5 - bot.getX();
        double dy = target.getY() - bot.getY();
        double dz = target.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;
        bot.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        return distSqr;
    }

    protected void stopNavigation(AIBotEntity bot) {
        bot.getNavigation().stop();
    }

    public enum ActionStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
