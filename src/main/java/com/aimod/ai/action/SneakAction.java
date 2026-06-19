package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

/**
 * Sneak action: make the bot sneak/crouch.
 * Useful for edge bridging, preventing falls, and accessing certain blocks.
 */
public class SneakAction extends Action {

    private final boolean sneak; // true = start sneaking, false = stop sneaking
    private int duration;
    private static final int MAX_DURATION = 200; // 10 seconds max

    public SneakAction(boolean sneak) {
        super(sneak ? "Start sneaking" : "Stop sneaking");
        this.sneak = sneak;
        this.duration = 0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            duration = 0;
            bot.setShiftKeyDown(sneak);
            DevLog.info("SNEAK_ACTION", "sneak={}", sneak);
        }

        if (status == ActionStatus.IN_PROGRESS) {
            duration++;
            if (!sneak || duration >= MAX_DURATION) {
                status = ActionStatus.COMPLETED;
                bot.setShiftKeyDown(false); // 无条件重置潜行状态
                DevLog.info("SNEAK_DONE", "sneak={}, duration={}", sneak, duration);
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
