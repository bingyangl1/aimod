package com.aimod.ai.chain;

import com.aimod.ai.movement.UnstuckDetector;
import com.aimod.fakeplayer.FakePlayer;

/**
 * Global stuck detection and recovery chain.
 * Wraps the existing UnstuckDetector.
 *
 * <p>Priority 50 — runs at same level as user tasks. When active,
 * the bot is considered stuck and the current navigation should be
 * re-evaluated.</p>
 */
public class UnstuckChain extends BehaviorChain {

    private final UnstuckDetector detector = new UnstuckDetector();
    private boolean active;
    private UnstuckDetector.RecoveryStrategy currentStrategy = UnstuckDetector.RecoveryStrategy.NONE;

    @Override public int priority() { return 50; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        // Don't detect stuck when bot is idle (no task or completed, not navigating)
        var task = bot.getCurrentTask();
        if ((task == null || task.isCompleted()) && !bot.getMovementController().isNavigating()) {
            detector.reset();
            return false;
        }
        currentStrategy = detector.tick(bot);
        if (currentStrategy != UnstuckDetector.RecoveryStrategy.NONE) {
            active = true;
            return true;
        }
        return false;
    }

    @Override
    public void tick(FakePlayer bot) {
        currentStrategy = detector.tick(bot);
        if (currentStrategy == UnstuckDetector.RecoveryStrategy.NONE
                || currentStrategy == UnstuckDetector.RecoveryStrategy.SKIP) {
            // All strategies exhausted or unstuck — give up
            if (currentStrategy == UnstuckDetector.RecoveryStrategy.SKIP) {
                bot.getMovementController().stop();
                bot.cancelTask();
            }
            active = false;
            detector.reset();
            return;
        }
        detector.executeRecovery(bot);
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() { active = false; detector.reset(); }
    @Override public String name() { return "Unstuck"; }
}
