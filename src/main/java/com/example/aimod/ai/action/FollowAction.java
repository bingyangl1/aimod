package com.example.aimod.ai.action;

import com.example.aimod.entity.AIBotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

public class FollowAction extends Action {
    private String playerName;
    private Player targetPlayer;

    public FollowAction(String playerName) {
        super("Follow player " + playerName);
        this.playerName = playerName;
        this.targetPlayer = null;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        if (targetPlayer == null || !targetPlayer.isAlive()) {
            findPlayer(bot);
        }
        return targetPlayer != null && targetPlayer.isAlive();
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            if (canExecute(bot)) {
                status = ActionStatus.IN_PROGRESS;
                // 设置导航到玩家位置
                bot.getNavigation().moveTo(targetPlayer, 1.0);
            } else {
                status = ActionStatus.FAILED;
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            // 检查是否到达玩家附近
            if (targetPlayer != null) {
                double distance = bot.distanceToSqr(targetPlayer);
                if (distance < 4.0) { // 2 blocks
                    bot.getNavigation().stop();
                    status = ActionStatus.COMPLETED;
                }
            }
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private void findPlayer(AIBotEntity bot) {
        if (bot.level() instanceof ServerLevel serverLevel) {
            List<ServerPlayer> players = serverLevel.getPlayers(player -> 
                player.getName().getString().equalsIgnoreCase(playerName));
            if (!players.isEmpty()) {
                targetPlayer = players.get(0);
            }
        }
    }

    public String getPlayerName() {
        return playerName;
    }
}