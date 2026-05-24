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

    private static final double FOLLOW_DISTANCE = 2.5;
    private static final double TOO_FAR = 6.0;

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            if (targetPlayer == null || !targetPlayer.isAlive()) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
            } else {
                double dist = bot.distanceTo(targetPlayer);
                if (dist > FOLLOW_DISTANCE) {
                    // Move toward player but stop at FOLLOW_DISTANCE blocks away
                    var playerPos = targetPlayer.blockPosition();
                    var towardsPlayer = targetPlayer.position().subtract(bot.position()).normalize();
                    var targetPos = targetPlayer.position().subtract(towardsPlayer.scale(FOLLOW_DISTANCE));
                    navigateTo(bot, new net.minecraft.core.BlockPos(
                            (int)targetPos.x, (int)targetPos.y, (int)targetPos.z), 1.0);
                } else {
                    stopNavigation(bot);
                }
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