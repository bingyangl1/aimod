package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
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
    public boolean canExecute(FakePlayer bot) {
        if (targetPlayer == null || !targetPlayer.isAlive()) findPlayer(bot);
        return targetPlayer != null && targetPlayer.isAlive();
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            if (canExecute(bot)) {
                status = ActionStatus.IN_PROGRESS;
            } else {
                status = ActionStatus.FAILED;
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            if (targetPlayer == null || !targetPlayer.isAlive()) {
                // Player gone — stop following
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
            } else {
                // Continuously follow the player
                navigateTo(bot, targetPlayer.blockPosition(), 1.0);
            }
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private void findPlayer(FakePlayer bot) {
        if (bot.level() instanceof ServerLevel serverLevel) {
            List<ServerPlayer> players = serverLevel.getPlayers(p -> p.getName().getString().equalsIgnoreCase(playerName));
            if (!players.isEmpty()) targetPlayer = players.get(0);
        }
    }

    public String getPlayerName() { return playerName; }
}