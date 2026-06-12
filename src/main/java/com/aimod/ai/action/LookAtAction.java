package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

/**
 * Look at position action: make the bot look at a specific position.
 * Useful for aiming, interacting with specific blocks, etc.
 */
public class LookAtAction extends Action {

    private final double x, y, z;

    public LookAtAction(double x, double y, double z) {
        super("Look at " + (int) x + ", " + (int) y + ", " + (int) z);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            bot.lookAt(x, y, z);
            status = ActionStatus.COMPLETED;
            DevLog.info("LOOK_AT", "target={}, {}, {}", (int) x, (int) y, (int) z);
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
