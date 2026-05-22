package com.example.aimod.ai.action;

import com.example.aimod.fakeplayer.FakePlayer;

public class WaitAction extends Action {
    private final int totalTicks;
    private int elapsedTicks;

    public WaitAction(int ticks) {
        super("Wait " + ticks + " ticks");
        this.totalTicks = Math.max(1, ticks);
        this.elapsedTicks = 0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
        }
        if (status == ActionStatus.IN_PROGRESS) {
            elapsedTicks++;
            if (elapsedTicks >= totalTicks) {
                status = ActionStatus.COMPLETED;
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
