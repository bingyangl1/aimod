package com.aimod.ai.movement;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedList;

/**
 * Detects when the bot is stuck and triggers recovery strategies.
 * Maintains a position history and checks for lack of movement.
 *
 * Recovery strategies (escalating):
 * 1. Wait a bit (sometimes temporary lag)
 * 2. Try breaking obstacle in the way
 * 3. Try random shimmy (small lateral movement)
 * 4. Try jumping
 * 5. Skip the current target
 */
public class UnstuckDetector {

    /** How many positions to remember. */
    private static final int HISTORY_SIZE = 100;
    /** Ticks of no significant movement before considered stuck. */
    private static final int STUCK_THRESHOLD_TICKS = 60; // 3 seconds
    /** Minimum distance (blocks) to count as "moved". */
    private static final double MOVED_THRESHOLD = 0.3;
    /** Ticks of stuck before escalating to next recovery strategy. */
    private static final int ESCALATION_INTERVAL = 40; // 2 seconds per strategy

    private final LinkedList<Vec3> posHistory = new LinkedList<>();
    private int stuckTicks;
    private boolean stuck;
    private RecoveryStrategy currentStrategy = RecoveryStrategy.NONE;
    private int strategyTicks;
    private int shimmyDirection; // -1, 0, 1

    public enum RecoveryStrategy {
        NONE,
        WAIT,
        JUMP,
        SHIMMY_LEFT,
        SHIMMY_RIGHT,
        SKIP
    }

    /**
     * Tick the unstuck detector. Call once per server tick.
     * @return the current recovery strategy (NONE if not stuck)
     */
    public RecoveryStrategy tick(FakePlayer bot) {
        Vec3 pos = bot.position();
        posHistory.addFirst(pos);
        if (posHistory.size() > HISTORY_SIZE) {
            posHistory.removeLast();
        }

        if (posHistory.size() < 10) {
            stuck = false;
            return RecoveryStrategy.NONE;
        }

        // Check if bot has moved significantly in the last STUCK_THRESHOLD_TICKS positions
        Vec3 oldest = posHistory.get(Math.min(STUCK_THRESHOLD_TICKS, posHistory.size() - 1));
        double moved = pos.distanceTo(oldest);

        if (moved > MOVED_THRESHOLD) {
            // Bot is moving, reset
            stuckTicks = 0;
            stuck = false;
            currentStrategy = RecoveryStrategy.NONE;
            strategyTicks = 0;
            return RecoveryStrategy.NONE;
        }

        stuckTicks++;

        if (stuckTicks < STUCK_THRESHOLD_TICKS) {
            return RecoveryStrategy.NONE;
        }

        // Bot is stuck — escalate recovery strategy
        stuck = true;
        strategyTicks++;

        if (strategyTicks >= ESCALATION_INTERVAL) {
            escalateStrategy();
            strategyTicks = 0;
            DevLog.info("UNSTUCK_ESCALATE", "strategy={}, stuckTicks={}", currentStrategy, stuckTicks);
        }

        return currentStrategy;
    }

    private void escalateStrategy() {
        currentStrategy = switch (currentStrategy) {
            case NONE -> RecoveryStrategy.WAIT;
            case WAIT -> RecoveryStrategy.JUMP;
            case JUMP -> RecoveryStrategy.SHIMMY_LEFT;
            case SHIMMY_LEFT -> RecoveryStrategy.SHIMMY_RIGHT;
            case SHIMMY_RIGHT -> RecoveryStrategy.SKIP;
            case SKIP -> RecoveryStrategy.SKIP; // terminal
        };
    }

    /**
     * Execute the current recovery strategy on the bot.
     */
    public void executeRecovery(FakePlayer bot) {
        switch (currentStrategy) {
            case WAIT -> {
                // Do nothing, just wait
                bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
            }
            case JUMP -> {
                if (bot.onGround()) {
                    bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
                    bot.setOnGround(false);
                }
            }
            case SHIMMY_LEFT -> {
                float yaw = bot.getYRot();
                double rad = Math.toRadians(yaw - 90);
                bot.setDeltaMovement(Math.cos(rad) * 0.1, bot.getDeltaMovement().y, Math.sin(rad) * 0.1);
            }
            case SHIMMY_RIGHT -> {
                float yaw = bot.getYRot();
                double rad = Math.toRadians(yaw + 90);
                bot.setDeltaMovement(Math.cos(rad) * 0.1, bot.getDeltaMovement().y, Math.sin(rad) * 0.1);
            }
            case SKIP -> {
                // Caller should handle this by skipping the current action/target
            }
            default -> {}
        }
    }

    /** Reset the detector (e.g., when starting a new movement). */
    public void reset() {
        stuckTicks = 0;
        stuck = false;
        currentStrategy = RecoveryStrategy.NONE;
        strategyTicks = 0;
        posHistory.clear();
    }

    public boolean isStuck() { return stuck; }
    public RecoveryStrategy getCurrentStrategy() { return currentStrategy; }
    public int getStuckTicks() { return stuckTicks; }
}
