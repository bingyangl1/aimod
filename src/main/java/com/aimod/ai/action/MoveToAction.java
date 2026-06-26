package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;

/**
 * Move to a target position using A* pathfinding.
 *
 * <p>Unlike the previous implementation which used direct movement (moveToward),
 * this version uses the async A* pathfinder which can automatically:
 * <ul>
 *   <li>Dig tunnels to reach underground targets</li>
 *   <li>Pillar up to reach elevated targets</li>
 *   <li>Path around obstacles and lava</li>
 *   <li>Navigate through complex terrain</li>
 * </ul>
 */
public class MoveToAction extends Action {
    private static final int STUCK_TIMEOUT = 200; // 10 seconds

    private final BlockPos targetPos;
    private final double speed;
    private boolean pathfindingStarted;
    private int stuckTicks;

    public MoveToAction(BlockPos targetPos, double speed) {
        super("Move to " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.speed = speed;
        this.pathfindingStarted = false;
        this.stuckTicks = 0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            pathfindingStarted = false;
            stuckTicks = 0;
        }

        if (status == ActionStatus.IN_PROGRESS && !pathfindingStarted) {
            // Use A* pathfinding — supports digging, pillaring, and obstacle avoidance
            navigateWithPathfinding(bot, targetPos);
            pathfindingStarted = true;
            DevLog.info("MOVE_TO_PATHFIND", "target={}", targetPos.toShortString());
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            // Check if arrived
            if (bot.getMovementController().hasArrived()) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("MOVE_TO_ARRIVED", "target={}", targetPos.toShortString());
                return true;
            }

            // Check distance — close enough counts as arrived
            double dx = targetPos.getX() + 0.5 - bot.getX();
            double dy = targetPos.getY() - bot.getY();
            double dz = targetPos.getZ() + 0.5 - bot.getZ();
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr < 4.0) { // within 2 blocks
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("MOVE_TO_CLOSE_ENOUGH", "target={}, dist={}",
                        targetPos.toShortString(), String.format("%.1f", Math.sqrt(distSqr)));
                return true;
            }

            // Stuck detection
            if (bot.getMovementController().isStuck()) {
                stuckTicks++;
                if (stuckTicks > STUCK_TIMEOUT) {
                    DevLog.warn("MOVE_TO_STUCK", "target={}, stuckTicks={}", targetPos.toShortString(), stuckTicks);
                    stopNavigation(bot);
                    status = ActionStatus.FAILED;
                    setFailReason("Stuck trying to reach " + targetPos.toShortString());
                    return true;
                }
            } else {
                stuckTicks = 0;
            }

            // Re-trigger pathfinding if it completed but we're not there yet
            // (e.g., path was partial, or something changed)
            if (!bot.getMovementController().isNavigating() && distSqr > 4.0) {
                navigateWithPathfinding(bot, targetPos);
                DevLog.info("MOVE_TO_REPATH", "target={}", targetPos.toShortString());
            }
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public BlockPos getTargetPos() { return targetPos; }
}