package com.aimod.ai.action;

import com.aimod.ai.movement.MovementController;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;

public abstract class Action {
    protected ActionStatus status;
    protected String description;

    public Action(String description) {
        this.description = description;
        this.status = ActionStatus.PENDING;
    }

    public abstract boolean canExecute(FakePlayer bot);
    public abstract void execute(FakePlayer bot);
    public abstract boolean isComplete(FakePlayer bot);

    public ActionStatus getStatus() { return status; }
    public void setStatus(ActionStatus status) { this.status = status; }
    public String getDescription() { return description; }

    /**
     * Get the bot's MovementController for centralized movement.
     * All movement in actions should go through this controller.
     */
    protected MovementController getMovementController(FakePlayer bot) {
        return bot.getMovementController();
    }

    /**
     * Move bot toward target using the MovementController's direct movement.
     * Delegates to MovementController.moveToward() — no pathfinding, just direct movement.
     *
     * @return squared distance to target
     */
    protected double navigateTo(FakePlayer bot, BlockPos target, double speed) {
        return bot.getMovementController().moveToward(target, speed);
    }

    /**
     * Navigate to target using A* pathfinding (async).
     * The controller will compute a path in the background and follow it.
     */
    protected void navigateWithPathfinding(FakePlayer bot, BlockPos target) {
        bot.getMovementController().navigateTo(target);
    }

    protected void stopNavigation(FakePlayer bot) {
        bot.getMovementController().stop();
    }

    public enum ActionStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
}
