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

import java.util.List;

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
    private PathExecutor nextPathExecutor;
    private BlockPos pathGoal;
    private boolean directMovement;
    private BotMovement activeMovement;

    /** Look ahead for next-path precomputation (in ticks). */
    private static final int PLANNING_LOOKAHEAD_TICKS = 40; // 2 seconds
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

        // Use valid positions for arrival detection
        boolean arrived = false;
        BlockPos feet = bot.blockPosition();
        if (activeMovement != null && activeMovement.isAtDestination(bot)) {
            arrived = true;
        } else {
            double distSqr = bot.distanceToSqr(
                    navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5);
            arrived = distSqr < ARRIVE_DIST_SQR;
        }
        if (arrived) {
            // Check if we have a next path to splice into
            if (nextPathExecutor != null && !nextPathExecutor.isCompleted()) {
                pathExecutor = nextPathExecutor;
                nextPathExecutor = null;
                DevLog.info("NAV_SPLICE", "switched to next path segment");
                // Don't stop — continue with next path
            } else {
                stop();
                DevLog.info("NAV_ARRIVED", "target={}", navTarget.toShortString());
                return;
            }
        }

        // Smart sprint logic
        updateSprinting();

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
            // Try splicing if we have a next path
            if (nextPathExecutor != null) {
                PathExecutor spliced = pathExecutor.trySplice(bot);
                if (spliced != pathExecutor) {
                    DevLog.info("NAV_SPLICE_EARLY", "splicing to next path");
                    pathExecutor = spliced;
                    nextPathExecutor = null;
                }
            }

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

            // Precompute next path when approaching end of current path
            if (pathExecutor != null) {
                double progress = pathExecutor.getProgress();
                if (progress > 0.7 && nextPathExecutor == null
                        && bot.level() instanceof ServerLevel serverLevel) {
                    requestNextPath(serverLevel);
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

    /**
     * Request a next-path segment for incremental pathfinding.
     * Computes a path from the current destination to the same navTarget.
     */
    private void requestNextPath(ServerLevel level) {
        if (nextPathExecutor != null) return; // Already queued
        if (pathExecutor == null || pathExecutor.isCompleted()) return;

        // Find start position: ~5 steps ahead of current index
        List<BlockPos> path = pathExecutor.getPath();
        int lookaheadIdx = Math.min(pathExecutor.getCurrentIndex() + 5, path.size() - 1);
        BlockPos start = lookaheadIdx >= 0 ? path.get(lookaheadIdx) : path.get(0);
        if (start == null || navTarget == null) return;

        CalculationContext ctx = new CalculationContext(level, bot);
        ctx.preloadRegion(start, 20);
        asyncPathfinder.requestPath(ctx, start, navTarget, result -> {
            if (!navigating || navTarget == null) return;
            if (result.isFound() && result.getLength() >= 2) {
                PathExecutor next = new PathExecutor(result.getPath());
                if (pathExecutor != null) {
                    pathExecutor.setNextPath(next);
                }
                nextPathExecutor = next;
                DevLog.info("NAV_NEXT_PATH_READY", "length={}", result.getLength());
            }
        });
    }

    /**
     * Decide whether the bot should sprint this tick.
     * Conditions: on ground, has forward motion, food > 6, headroom clear,
     * path ahead is flat or descending, not in water.
     */
    private void updateSprinting() {
        if (!navigating) {
            bot.setSprinting(false);
            return;
        }

        // Must be on ground with forward velocity
        Vec3 vel = bot.getDeltaMovement();
        if (!bot.onGround() || (Math.abs(vel.x) < 0.01 && Math.abs(vel.z) < 0.01)) {
            bot.setSprinting(false);
            return;
        }

        // Sufficient food
        if (bot.getFoodData() != null && bot.getFoodData().getFoodLevel() <= 6) {
            bot.setSprinting(false);
            return;
        }

        // Not in water
        if (bot.isInWater() || bot.isInLava()) {
            bot.setSprinting(false);
            return;
        }

        // Headroom check: look ahead in movement direction
        if (bot.level() instanceof ServerLevel level) {
            BlockPos feet = bot.blockPosition();
            // Check blocks at head level in movement direction
            int dx = vel.x > 0.05 ? 1 : vel.x < -0.05 ? -1 : 0;
            int dz = vel.z > 0.05 ? 1 : vel.z < -0.05 ? -1 : 0;
            if (dx != 0 || dz != 0) {
                for (int i = 1; i <= 3; i++) {
                    BlockPos headLevel = feet.offset(dx * i, 1, dz * i);
                    if (!level.getBlockState(headLevel).isAir() &&
                            !level.getBlockState(headLevel).canBeReplaced()) {
                        bot.setSprinting(false);
                        return;
                    }
                    BlockPos headLevel2 = feet.offset(dx * i, 2, dz * i);
                    if (!level.getBlockState(headLevel2).isAir() &&
                            !level.getBlockState(headLevel2).canBeReplaced()) {
                        bot.setSprinting(false);
                        return;
                    }
                }
            }

            // Path lookahead: check flat/descending terrain
            if (pathExecutor != null && !pathExecutor.isCompleted()) {
                List<BlockPos> path = pathExecutor.getPath();
                int idx = pathExecutor.getCurrentIndex();
                boolean allFlatOrDesc = true;
                for (int i = idx + 1; i < Math.min(idx + 4, path.size()); i++) {
                    if (i > 0 && path.get(i).getY() > path.get(i - 1).getY() + 1) {
                        allFlatOrDesc = false;
                        break;
                    }
                }
                if (!allFlatOrDesc) {
                    bot.setSprinting(false);
                    return;
                }
            }
        }

        bot.setSprinting(true);
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
