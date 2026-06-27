package com.aimod.ai.pathing;

import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Segmented pathfinder for long-distance navigation.
 *
 * <p>Instead of computing a single A* path for the entire distance
 * (which can timeout for very long paths), this breaks the path into
 * segments of configurable length and computes each segment independently.</p>
 *
 * <p>Inspired by Baritone's segmented calculation approach.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Compute direct line from start to goal</li>
 *   <li>Break into segments of SEGMENT_LENGTH blocks</li>
 *   <li>Compute A* for each segment</li>
 *   <li>Concatenate all segment paths</li>
 * </ol>
 */
public class SegmentedPathfinder {

    private static final int SEGMENT_LENGTH = 32; // blocks per segment
    private static final int MAX_SEGMENTS = 16;   // max 512 blocks total
    private static final long SEGMENT_TIMEOUT_MS = 500; // timeout per segment

    private final ServerLevel level;

    public SegmentedPathfinder(ServerLevel level) {
        this.level = level;
    }

    /**
     * Find a path from start to goal using segmented A*.
     * Returns null if no path found.
     */
    public PathResult findPath(BlockPos start, BlockPos goal) {
        double totalDist = Math.sqrt(start.distSqr(goal));

        // If short enough, use regular pathfinder
        if (totalDist <= SEGMENT_LENGTH) {
            Pathfinder pf = new Pathfinder(level, start, goal, 2000, 20);
            return pf.findPath();
        }

        // Break into segments
        List<BlockPos> waypoints = computeWaypoints(start, goal);
        List<BlockPos> fullPath = new ArrayList<>();
        fullPath.add(start);

        int nodesExplored = 0;
        BlockPos segmentStart = start;

        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos segmentEnd = waypoints.get(i);

            // Skip if already at waypoint
            if (segmentStart.distSqr(segmentEnd) < 4) {
                continue;
            }

            Pathfinder pf = new Pathfinder(level, segmentStart, segmentEnd, SEGMENT_TIMEOUT_MS, 20);
            PathResult segmentResult = pf.findPath();
            nodesExplored += segmentResult.getNodesExplored();

            if (segmentResult.isFound()) {
                List<BlockPos> segmentPath = segmentResult.getPath();
                // Skip first point (it's the same as last point of previous segment)
                for (int j = 1; j < segmentPath.size(); j++) {
                    fullPath.add(segmentPath.get(j));
                }
                segmentStart = segmentEnd;
                DevLog.info("SEGMENT_OK", "segment={}/{}, from={}, to={}, length={}",
                        i + 1, waypoints.size(),
                        segmentStart.toShortString(), segmentEnd.toShortString(),
                        segmentPath.size());
            } else {
                DevLog.warn("SEGMENT_FAIL", "segment={}/{}, from={}, to={}",
                        i + 1, waypoints.size(),
                        segmentStart.toShortString(), segmentEnd.toShortString());
                // Try to continue with next segment from current position
                segmentStart = fullPath.get(fullPath.size() - 1);
            }
        }

        if (fullPath.size() < 2) {
            return new PathResult(List.of(), false, nodesExplored);
        }

        DevLog.info("SEGMENTED_PATH_DONE", "segments={}, totalPoints={}, nodes={}",
                waypoints.size(), fullPath.size(), nodesExplored);
        return new PathResult(fullPath, true, nodesExplored);
    }

    /**
     * Compute waypoints along the direct line from start to goal.
     */
    private List<BlockPos> computeWaypoints(BlockPos start, BlockPos goal) {
        List<BlockPos> waypoints = new ArrayList<>();

        double dx = goal.getX() - start.getX();
        double dy = goal.getY() - start.getY();
        double dz = goal.getZ() - start.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        int numSegments = Math.min(MAX_SEGMENTS, (int) Math.ceil(dist / SEGMENT_LENGTH));

        for (int i = 1; i <= numSegments; i++) {
            double t = (double) i / numSegments;
            int wx = start.getX() + (int) (dx * t);
            int wy = start.getY() + (int) (dy * t);
            int wz = start.getZ() + (int) (dz * t);
            waypoints.add(new BlockPos(wx, wy, wz));
        }

        // Last waypoint is the goal
        if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(goal)) {
            waypoints.add(goal);
        }

        return waypoints;
    }
}
