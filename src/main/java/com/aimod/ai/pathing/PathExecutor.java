package com.aimod.ai.pathing;

import com.aimod.ai.movement.BotMovement;
import com.aimod.ai.pathing.CalculationContext;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/**
 * Follows a computed path (list of BlockPos waypoints).
 * Inspired by Baritone's PathExecutor (LGPL-3.0).
 * 
 * Tracks progress along the path using each movement's valid positions
 * for accurate arrival detection. Also supports path splicing (joining
 * with a next-path segment) and runtime revalidation.
 */
public class PathExecutor {
    
    private static final double WAYPOINT_REACH_DIST_SQR = 2.25; // 1.5 blocks
    private static final int STUCK_TIMEOUT = 100; // 5 seconds at 20 TPS
    
    private final List<BlockPos> path;
    /** Valid positions for each waypoint (computed lazily from movement pairs). */
    private final List<Set<BlockPos>> validPositions;
    private int currentIndex;
    private int stuckTicks;
    private double lastDistToWaypoint;
    private boolean completed;
    private boolean failed;
    /** Next path for splicing (incremental pathfinding). */
    private PathExecutor nextPath;
    /** Ticks between path revalidation checks (0 = disabled). */
    private int revalidateTick = 0;
    
    public PathExecutor(List<BlockPos> path) {
        this.path = path;
        this.validPositions = computeValidPositions(path);
        this.currentIndex = 1; // Skip start position (index 0)
        this.stuckTicks = 0;
        this.lastDistToWaypoint = Double.MAX_VALUE;
        this.completed = false;
        this.failed = false;
    }
    
    /**
     * Compute valid position sets for each waypoint in the path.
     * Each consecutive pair (pos[i], pos[i+1]) maps to a BotMovement.
     */
    private static List<Set<BlockPos>> computeValidPositions(List<BlockPos> path) {
        List<Set<BlockPos>> vps = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            if (i < path.size() - 1) {
                // Create the movement for this segment and get its valid positions
                BotMovement mov = BotMovement.create(path.get(i), path.get(i + 1));
                if (mov != null) {
                    vps.add(mov.calculateValidPositions());
                } else {
                    vps.add(Collections.singleton(path.get(i)));
                }
            } else {
                vps.add(Collections.singleton(path.get(i)));
            }
        }
        return vps;
    }
    
    /**
     * Tick the path executor. Returns the next position to navigate to.
     * Returns null if the path is complete, failed, or invalid.
     */
    public BlockPos tick(FakePlayer bot) {
        if (completed || failed || path == null || path.isEmpty()) {
            return null;
        }
        
        if (currentIndex >= path.size()) {
            completed = true;
            DevLog.info("PATH_EXECUTOR_DONE", "steps={}", path.size());
            return null;
        }
        
        BlockPos target = path.get(currentIndex);
        
        // Check arrival using valid positions if available
        boolean arrived = false;
        Set<BlockPos> vps = currentIndex < validPositions.size()
                ? validPositions.get(currentIndex) : null;
        if (vps != null) {
            BlockPos feet = bot.blockPosition();
            for (BlockPos vp : vps) {
                if (feet.equals(vp) || feet.closerThan(vp, 1.5)) {
                    arrived = true;
                    break;
                }
            }
        }
        if (!arrived) {
            double distSqr = bot.distanceToSqr(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            arrived = distSqr < WAYPOINT_REACH_DIST_SQR;
        }
        
        // Reached current waypoint?
        if (arrived) {
            currentIndex++;
            stuckTicks = 0;
            lastDistToWaypoint = Double.MAX_VALUE;
            
            if (currentIndex >= path.size()) {
                completed = true;
                DevLog.info("PATH_EXECUTOR_DONE", "steps={}", path.size());
                return null;
            }
            
            DevLog.info("PATH_WAYPOINT", "step={}/{}", currentIndex, path.size());
            return path.get(currentIndex);
        }
        
        // Stuck detection
        if (lastDistToWaypoint != Double.MAX_VALUE) {
            double distSqr = bot.distanceToSqr(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (distSqr >= lastDistToWaypoint - 0.01) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            lastDistToWaypoint = distSqr;
        } else {
            lastDistToWaypoint = bot.distanceToSqr(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        }
        
        if (stuckTicks > STUCK_TIMEOUT) {
            failed = true;
            DevLog.warn("PATH_EXECUTOR_STUCK", "step={}/{}, dist={}",
                    currentIndex, path.size(),
                    String.format("%.1f", Math.sqrt(lastDistToWaypoint)));
            return null;
        }
        
        return target;
    }
    
    /**
     * Get the current target waypoint.
     */
    public BlockPos getCurrentTarget() {
        if (currentIndex < path.size()) {
            return path.get(currentIndex);
        }
        return null;
    }
    
    // ---- Path splicing (incremental pathfinding) ----

    /**
     * Set the next path segment, enabling splicing.
     */
    public void setNextPath(PathExecutor next) {
        this.nextPath = next;
    }

    /**
     * Try to splice the next path into this one, or return the next
     * path if we've passed the splice point.
     */
    public PathExecutor trySplice(FakePlayer bot) {
        if (nextPath == null) return this;
        
        // Check if bot position matches the start of next path
        BlockPos feet = bot.blockPosition();
        List<BlockPos> nextPositions = nextPath.getPath();
        if (nextPositions.isEmpty()) return this;
        
        BlockPos nextStart = nextPositions.get(0);
        if (feet.distSqr(nextStart) <= 4) {
            // Bot is at the splice point — switch to next path
            DevLog.info("PATH_SPLICE", "switching to next path at {}", nextStart.toShortString());
            nextPath.currentIndex = 1;
            nextPath.stuckTicks = 0;
            return nextPath;
        }
        
        // Try to find a waypoint in the remaining path that overlaps with next path
        for (int i = currentIndex; i < path.size(); i++) {
            BlockPos wp = path.get(i);
            for (int j = 0; j < Math.min(3, nextPositions.size()); j++) {
                if (wp.distSqr(nextPositions.get(j)) <= 4) {
                    // Overlap found — drop tail of current path
                    DevLog.info("PATH_SPLICE", "overlap at current[{}] = next[{}], switching", i, j);
                    nextPath.currentIndex = j + 1;
                    nextPath.stuckTicks = 0;
                    return nextPath;
                }
            }
        }
        
        return this; // Cannot splice yet — continue with current path
    }

    // ---- Runtime path revalidation ----

    /**
     * Periodically verify the upcoming path segments are still traversable.
     * Call this from MovementController.tick() every ~20 ticks.
     *
     * @return true if the path is still valid, false if world changes invalidated it
     */
    public boolean revalidateRemainingPath(ServerLevel level, FakePlayer bot) {
        if (completed || failed) return false;
        revalidateTick++;

        // Check every 20 ticks (~1 second)
        if (revalidateTick % 20 != 0) return !failed;

        // Validate upcoming 3 movements
        int end = Math.min(currentIndex + 3, path.size());
        for (int i = currentIndex; i < end - 1; i++) {
            BlockPos src = path.get(i);
            BlockPos dest = path.get(i + 1);

            // Quick check: dest block is not unexpectedly obstructed
            if (!isFootPrintable(level, dest)) {
                DevLog.warn("PATH_INVALID", "dest {} obstructed or missing support",
                        dest.toShortString());
                failed = true;
                return false;
            }

            // Check no new wall between src and dest
            if (isBlockedDirectPath(level, src, dest)) {
                DevLog.warn("PATH_INVALID", "segment {} → {} blocked",
                        src.toShortString(), dest.toShortString());
                failed = true;
                return false;
            }
        }

        return true;
    }

    /**
     * Quick check: a position is "foot-printable" if its block is replaceable/air
     * and the block below is solid (standing or head-in-block is bad).
     */
    private static boolean isFootPrintable(ServerLevel level, BlockPos pos) {
        // The foot-level block must be passable
        if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).canBeReplaced()) {
            return false;
        }
        // The block below must exist (not void) — support check is best-effort
        BlockPos below = pos.below();
        if (below.getY() < level.getMinBuildHeight()) return false;
        return true;
    }

    /**
     * Quick check: is there a solid block between src and dest that blocks movement?
     */
    private static boolean isBlockedDirectPath(ServerLevel level, BlockPos src, BlockPos dest) {
        int dx = dest.getX() - src.getX();
        int dz = dest.getZ() - src.getZ();
        if (dx == 0 && dz == 0) return false;

        // Check head-level blocks along the segment
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            BlockPos mid = new BlockPos(
                    (int) Math.round(src.getX() + t * dx),
                    src.getY() + 1,
                    (int) Math.round(src.getZ() + t * dz)
            );
            if (!level.getBlockState(mid).isAir() && !level.getBlockState(mid).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    public void setInvalidated() { this.failed = true; }

    public boolean isCompleted() { return completed; }
    public boolean isFailed() { return failed; }
    public int getCurrentIndex() { return currentIndex; }
    public int getPathLength() { return path != null ? path.size() : 0; }
    public List<BlockPos> getPath() { return path; }
    public PathExecutor getNextPath() { return nextPath; }

    /**
     * Get progress as a fraction (0.0 to 1.0).
     */
    public double getProgress() {
        if (path == null || path.isEmpty()) return 1.0;
        return (double) currentIndex / path.size();
    }
}