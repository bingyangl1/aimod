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
    private static final int PATH_UPDATE_INTERVAL = 20; // ticks between path recalculations
    private int pathUpdateTimer;
    private net.minecraft.core.BlockPos lastPlayerPos;

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            if (targetPlayer == null || !targetPlayer.isAlive()) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
            } else {
                double dist = bot.distanceTo(targetPlayer);
                if (dist <= FOLLOW_DISTANCE) {
                    stopNavigation(bot);
                    return false; // stay, keep following
                }

                // Recalculate path periodically or when player moved significantly
                var playerBlockPos = targetPlayer.blockPosition();
                boolean playerMoved = lastPlayerPos == null || !playerBlockPos.equals(lastPlayerPos);
                boolean needsUpdate = pathUpdateTimer <= 0 || (playerMoved && pathUpdateTimer < PATH_UPDATE_INTERVAL - 5);

                if (needsUpdate) {
                    // Calculate target 2 blocks away from player in bot's direction
                    var towardsBot = bot.position().subtract(targetPlayer.position()).normalize();
                    var targetPos = targetPlayer.position().add(towardsBot.scale(FOLLOW_DISTANCE));
                    navigateWithPathfinding(bot, new net.minecraft.core.BlockPos(
                            (int)targetPos.x, (int)targetPos.y, (int)targetPos.z));
                    lastPlayerPos = playerBlockPos;
                    pathUpdateTimer = PATH_UPDATE_INTERVAL;
                }
                pathUpdateTimer--;
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