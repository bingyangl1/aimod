package com.aimod.ai.movement;

import com.aimod.ai.pathing.AsyncPathfinder;
import com.aimod.ai.pathing.CalculationContext;
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
 *
 * Features:
 * - A* pathfinding with async computation (non-blocking)
 * - Thread-safe pathfinding via CalculationContext (block state pre-snapshot)
 * - Automatic fallback to direct movement when no path found
 * - Stuck detection and recovery via UnstuckDetector
 * - BotMovement subclasses for typed movement execution (traverse, pillar, etc.)
 */
public class MovementController {

    private final FakePlayer bot;
    private final AsyncPathfinder asyncPathfinder;
    private final UnstuckDetector unstuckDetector;

    private BlockPos navTarget;
    private boolean navigating;
    private PathExecutor pathExecutor;
    private BlockPos pathGoal;
    private boolean directMovement;
    private BotMovement activeMovement;

    private static final double ARRIVE_DIST_SQR = 2.0;

    public MovementController(FakePlayer bot) {
        this.bot = bot;
        this.asyncPathfinder = new AsyncPathfinder();
        this.unstuckDetector = new UnstuckDetector();
    }

    /**
     * Navigate to a target position using A* pathfinding.
     * Creates a CalculationContext snapshot on the server thread, then dispatches
     * async pathfinding on a background thread.
     */
    public void navigateTo(BlockPos target) {
        if (target.equals(navTarget) && navigating) return;

        navTarget = target.immutable();
        navigating = true;
        directMovement = false;
        activeMovement = null;
        unstuckDetector.reset();

        if (bot.level() instanceof ServerLevel serverLevel) {
            BlockPos botPos = bot.blockPosition();
            CalculationContext ctx = new CalculationContext(serverLevel, bot);
            ctx.preloadRegion(botPos, 20);
            asyncPathfinder.requestPath(ctx, botPos, navTarget, this::onPathComputed);
        } else {
            directMovement = true;
        }
    }

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

    public void stop() {
        navigating = false;
        navTarget = null;
        pathExecutor = null;
        pathGoal = null;
        directMovement = false;
        activeMovement = null;
        asyncPathfinder.cancel();
        unstuckDetector.reset();
        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
    }

    public void tick() {
        asyncPathfinder.tick();
        if (!navigating || navTarget == null) return;

        double distSqr = bot.distanceToSqr(
                navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5);
        if (distSqr < ARRIVE_DIST_SQR) {
            stop();
            DevLog.info("NAV_ARRIVED", "target={}", navTarget.toShortString());
            return;
        }

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

        // Follow computed path using BotMovement types
        if (pathExecutor != null && !pathExecutor.isCompleted() && !pathExecutor.isFailed()) {
            BlockPos next = pathExecutor.tick(bot);
            if (next != null) {
                if (activeMovement == null
                        || activeMovement.getStatus() == BotMovement.Status.COMPLETE
                        || activeMovement.getStatus() == BotMovement.Status.FAILED
                        || !activeMovement.getDest().equals(next)) {
                    activeMovement = BotMovement.create(bot.blockPosition(), next);
                }
                if (activeMovement != null) {
                    boolean done = activeMovement.update(bot);
                    if (done && activeMovement.getStatus() == BotMovement.Status.FAILED) {
                        moveToward(next, 1.0);
                    }
                } else {
                    moveToward(next, 1.0);
                }
            }
            return;
        }

        if (directMovement || asyncPathfinder.isComputing()) {
            moveToward(navTarget, 1.0);
        }
    }

    public boolean isNavigating() { return navigating; }
    public boolean hasArrived() { return !navigating && navTarget != null; }
    public boolean isStuck() { return unstuckDetector.isStuck(); }
    public BlockPos getNavTarget() { return navTarget; }
    public PathExecutor getPathExecutor() { return pathExecutor; }
    public BotMovement getActiveMovement() { return activeMovement; }

    public double getDistSqrToTarget() {
        if (navTarget == null) return -1;
        return bot.distanceToSqr(navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5);
    }

    private void onPathComputed(PathResult result) {
        if (!navigating || navTarget == null) return;
        if (result.isFound() && result.getLength() >= 2) {
            pathExecutor = new PathExecutor(result.getPath());
            pathGoal = navTarget;
            directMovement = false;
            DevLog.info("NAV_PATH_READY", "length={}", result.getLength());
        } else {
            directMovement = true;
            DevLog.info("NAV_DIRECT_FALLBACK", "target={}", navTarget.toShortString());
        }
    }
}
