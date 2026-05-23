package com.aimod.ai.pathing;

import net.minecraft.core.BlockPos;
import java.util.Collections;
import java.util.List;

/**
 * Result of a pathfinding calculation.
 * Contains the path (list of waypoints), success flag, and stats.
 */
public class PathResult {
    private final List<BlockPos> path;
    private final boolean found;
    private final int nodesExplored;
    
    public PathResult(List<BlockPos> path, boolean found, int nodesExplored) {
        this.path = path != null ? path : Collections.emptyList();
        this.found = found;
        this.nodesExplored = nodesExplored;
    }
    
    public static PathResult empty() {
        return new PathResult(Collections.emptyList(), false, 0);
    }
    
    /**
     * Get the path as a list of BlockPos waypoints.
     * First element is start, last element is goal (or closest to goal).
     */
    public List<BlockPos> getPath() {
        return Collections.unmodifiableList(path);
    }
    
    /**
     * Get the next waypoint to move to (index 1, since index 0 is current position).
     * Returns null if no valid next step.
     */
    public BlockPos getNextStep() {
        if (path.size() < 2) return null;
        return path.get(1);
    }
    
    public boolean isFound() {
        return found;
    }
    
    public int getLength() {
        return path.size();
    }
    
    public int getNodesExplored() {
        return nodesExplored;
    }
}