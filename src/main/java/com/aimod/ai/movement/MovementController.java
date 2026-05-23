package com.aimod.ai.movement;

import com.aimod.ai.pathing.AsyncPathfinder;
import com.aimod.ai.pathing.PathExecutor;
import com.aimod.ai.pathing.PathResult;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * Centralized movement controller for FakePlayer.
 * Replaces the duplicated navigateTo() logic that was scattered across Action subclasses.
 *
 * Features:
 * - A* pathfinding with async computation (non-blocking)
 * - Automatic fallback to direct movement when no path found
 * - Stuck detection and recovery via UnstuckDetector
 * - Path caching and re-use
 *
 * Usage from actions:
 * <pre>
 *   controller.navigateTo(targetPos);
 *   // In isComplete():
 *   if (controller.hasArrived()) { ... }
 * </pre>
 */
public class MovementController {

    private final FakePlayer bot;
    private final AsyncPathfinder asyncPathfinder;
    private final UnstuckDetector unstuckDetector;

    // Current navigation target
    private BlockPos navTarget;
    private boolean navigating;

    // Path execution
    private PathExecutor pathExecutor;
    private BlockPos pathGoal; // The goal the current path was computed for

    // Direct movement fallback (when no path found)
    private boolean directMovement;

    // Arrival tracking
    private static final double ARRIVE_DIST_SQR = 2.0;

    public MovementController(FakePlayer bot) {
        this.bot = bot;
        this.asyncPathfinder = new AsyncPathfinder();
        this.unstuckDetector = new UnstuckDetector();
    }

    // ── Navigation API ─────────────────────────────────────────────────

    /**
     * Navigate to a target position using A* pathfinding.
     * If pathfinding is already in progress for a different target, it is cancelled.
     * Falls back to direct movement if no path is found.
     */
    public void navigateTo(BlockPos target) {
        if (target.equals(navTarget) && navigating) {
            return; // Already navigating to this target
        }

        navTarget = target.immutable();
        navigating = true;
        directMovement = false;
        unstuckDetector.reset();

        // Request async pathfinding
        if (bot.level() instanceof ServerLevel serverLevel) {
            BlockPos botPos = bot.blockPosition();
            asyncPathfinder.requestPath(serverLevel, botPos, navTarget, result -> {
                onPathComputed(result);
            });
        } else {
            // Fallback: direct movement
            directMovement = true;
        }
    }

    /**
     * Move directly toward a position without pathfinding.
     * Use for short-distance movement or when pathfinding is not needed.
     *
     * @return the squared distance to the target
     */
    public double moveToward(BlockPos target, double speedBlocksPerSec) {
        double dx = target.getX() + 0.5 - bot.getX();
        double dy = target.getY() - bot.getY();
        double dz = target.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;
        double dist = Math.sqrt(distSqr);

        if (dist > 0.15) {
            double stepPerTick = speedBlocksPerSec * 4.317 / 20.0;
            double step = Math.min(stepPerTick, dist);
            double moveX = (dx / dist) * step;
            double moveZ = (dz / dist) * step;
            double moveY;

            if (dy > 0.3 && dy <= 1.5 && bot.onGround()) {
                moveY = 0.42;
            } else if (!bot.onGround()) {
                moveY = bot.getDeltaMovement().y;
            } else {
                moveY = dy < -0.5 ? -0.4 : 0;
            }

            Vec3 movement = new Vec3(moveX, moveY, moveZ);
            bot.setDeltaMovement(movement);
            bot.move(MoverType.SELF, movement);

            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
        }

        return distSqr;
    }

    /**
     * Stop all navigation and movement.
     */
    public void stop() {
        navigating = false;
        navTarget = null;
        pathExecutor = null;
        pathGoal = null;
        directMovement = false;
        asyncPathfinder.cancel();
        unstuckDetector.reset();
        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
    }

    /**
     * Tick the movement controller. Call from FakePlayer.tick().
     */
    public void tick() {
        // Deliver async pathfinding results
        asyncPathfinder.tick();

        if (!navigating || navTarget == null) {
            return;
        }

        // Check arrival
        double distSqr = bot.distanceToSqr(
                navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5);
        if (distSqr < ARRIVE_DIST_SQR) {
            stop();
            DevLog.info("NAV_ARRIVED", "target={}", navTarget.toShortString());
            return;
        }

        // Tick unstuck detector
        UnstuckDetector.RecoveryStrategy recovery = unstuckDetector.tick(bot);
        if (recovery != UnstuckDetector.RecoveryStrategy.NONE) {
            if (recovery == UnstuckDetector.RecoveryStrategy.SKIP) {
                DevLog.warn("NAV_STUCK_SKIP", "target={}", navTarget.toShortString());
                stop();
                return;
            }
            unstuckDetector.executeRecovery(bot);
            return;
        }

        // Follow computed path
        if (pathExecutor != null && !pathExecutor.isCompleted() && !pathExecutor.isFailed()) {
            BlockPos next = pathExecutor.tick(bot);
            if (next != null) {
                moveToward(next, 1.0);
            }
            return;
        }

        // Direct movement fallback
        if (directMovement || asyncPathfinder.isComputing()) {
            moveToward(navTarget, 1.0);
        }
    }

    // ── Queries ────────────────────────────────────────────────────────

    /** Whether currently navigating to a target. */
    public boolean isNavigating() { return navigating; }

    /** Whether the bot has arrived at the navigation target. */
    public boolean hasArrived() { return !navigating && navTarget != null; }

    /** Whether the bot is stuck. */
    public boolean isStuck() { return unstuckDetector.isStuck(); }

    /** Get the current navigation target, or null. */
    public BlockPos getNavTarget() { return navTarget; }

    /** Get the squared distance to the navigation target, or -1 if not navigating. */
    public double getDistSqrToTarget() {
        if (navTarget == null) return -1;
        return bot.distanceToSqr(navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5);
    }

    /** Get the path executor (for progress info). */
    public PathExecutor getPathExecutor() { return pathExecutor; }

    // ── Internal ───────────────────────────────────────────────────────

    private void onPathComputed(PathResult result) {
        if (!navigating || navTarget == null) {
            return; // Navigation was cancelled while computing
        }

        if (result.isFound() && result.getLength() >= 2) {
            pathExecutor = new PathExecutor(result.getPath());
            pathGoal = navTarget;
            directMovement = false;
            DevLog.info("NAV_PATH_READY", "length={}", result.getLength());
        } else {
            // No path found — fall back to direct movement
            directMovement = true;
            DevLog.info("NAV_DIRECT_FALLBACK", "target={}", navTarget.toShortString());
        }
    }
}
