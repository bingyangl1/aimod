package com.aimod.ai.pathing;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Follows a computed path (list of BlockPos waypoints).
 * Inspired by Baritone's PathExecutor (LGPL-3.0).
 * 
 * Tracks progress along the path, detects when the bot
 * reaches each waypoint, and handles re-pathing when stuck.
 */
public class PathExecutor {
    
    private static final double WAYPOINT_REACH_DIST_SQR = 2.25; // 1.5 blocks
    private static final int STUCK_TIMEOUT = 100; // 5 seconds at 20 TPS
    
    private final List<BlockPos> path;
    private int currentIndex;
    private int stuckTicks;
    private double lastDistToWaypoint;
    private boolean completed;
    private boolean failed;
    
    public PathExecutor(List<BlockPos> path) {
        this.path = path;
        this.currentIndex = 1; // Skip start position (index 0)
        this.stuckTicks = 0;
        this.lastDistToWaypoint = Double.MAX_VALUE;
        this.completed = false;
        this.failed = false;
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
        double distSqr = bot.distanceToSqr(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        
        // Reached current waypoint?
        if (distSqr < WAYPOINT_REACH_DIST_SQR) {
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
        if (distSqr >= lastDistToWaypoint - 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastDistToWaypoint = distSqr;
        
        if (stuckTicks > STUCK_TIMEOUT) {
            failed = true;
            DevLog.warn("PATH_EXECUTOR_STUCK", "step={}/{}, dist={}",
                    currentIndex, path.size(), String.format("%.1f", Math.sqrt(distSqr)));
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
    
    public boolean isCompleted() { return completed; }
    public boolean isFailed() { return failed; }
    public int getCurrentIndex() { return currentIndex; }
    public int getPathLength() { return path != null ? path.size() : 0; }
    public List<BlockPos> getPath() { return path; }

    /**
     * Get progress as a fraction (0.0 to 1.0).
     */
    public double getProgress() {
        if (path == null || path.isEmpty()) return 1.0;
        return (double) currentIndex / path.size();
    }
}