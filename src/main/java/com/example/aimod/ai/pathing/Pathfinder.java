package com.example.aimod.ai.pathing;

import com.example.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/**
 * A* pathfinder inspired by Baritone's AStarPathFinder.
 * 
 * Key design decisions from Baritone:
 * - Uses HashMap<Long, PathNode> for O(1) node lookup by position hash
 * - PriorityQueue (binary heap) as open set
 * - Time-bounded search: returns best path found within timeout
 * - Euclidean distance heuristic (admissible for 3D movement)
 * - Supports 10 movement types: 4 cardinal, 4 diagonal, ascend, descend
 * 
 * Simplified from Baritone:
 * - No parkour jumps (4-block jumps)
 * - No pillar (build up)
 * - No water/lava swimming paths
 * - No precomputed block data
 */
public class Pathfinder {
    
    private static final long DEFAULT_TIMEOUT_MS = 2000;   // 2 seconds for nearby paths
    private static final int DEFAULT_MAX_RADIUS = 20;       // Max search radius
    
    private final ServerLevel level;
    private final int startX, startY, startZ;
    private final int goalX, goalY, goalZ;
    private final long timeoutMs;
    private final int maxRadius;
    
    private final HashMap<Long, PathNode> nodeMap;
    private final PriorityQueue<PathNode> openSet;
    private boolean cancelled;
    
    public Pathfinder(ServerLevel level, BlockPos start, BlockPos goal) {
        this(level, start, goal, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RADIUS);
    }
    
    public Pathfinder(ServerLevel level, BlockPos start, BlockPos goal, long timeoutMs, int maxRadius) {
        this.level = level;
        this.startX = start.getX();
        this.startY = start.getY();
        this.startZ = start.getZ();
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
        this.timeoutMs = timeoutMs;
        this.maxRadius = maxRadius;
        this.nodeMap = new HashMap<>();
        this.openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.combinedCost));
    }
    
    /**
     * Run A* search. Returns PathResult with the best path found.
     */
    public PathResult findPath() {
        long startTime = System.currentTimeMillis();
        
        PathNode startNode = getOrCreateNode(startX, startY, startZ);
        startNode.cost = 0;
        startNode.heuristic = PathNode.heuristic(startX, startY, startZ, goalX, goalY, goalZ);
        startNode.combinedCost = startNode.heuristic;
        openSet.add(startNode);
        startNode.inOpenSet = true;
        
        PathNode bestNode = startNode;
        double bestHeuristic = startNode.heuristic;
        
        int nodesExplored = 0;
        int movesConsidered = 0;
        
        while (!openSet.isEmpty() && !cancelled) {
            // Timeout check every 32 nodes
            if ((nodesExplored & 31) == 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                DevLog.info("PATHFINDER_TIMEOUT", "nodes={}, moves={}, elapsed={}ms",
                        nodesExplored, movesConsidered, System.currentTimeMillis() - startTime);
                break;
            }
            
            PathNode current = openSet.poll();
            current.inOpenSet = false;
            nodesExplored++;
            
            // Goal check
            if (current.x == goalX && current.y == goalY && current.z == goalZ) {
                DevLog.info("PATHFINDER_FOUND", "nodes={}, moves={}, cost={}, elapsed={}ms",
                        nodesExplored, movesConsidered, String.format("%.1f", current.cost),
                        System.currentTimeMillis() - startTime);
                return new PathResult(reconstructPath(current), true, nodesExplored);
            }
            
            // Track best-so-far (Baritone pattern: best path even if goal not reached)
            if (current.heuristic < bestHeuristic) {
                bestHeuristic = current.heuristic;
                bestNode = current;
            }
            
            // Radius check — don't expand nodes too far from start
            int dxFromStart = current.x - startX;
            int dzFromStart = current.z - startZ;
            if (dxFromStart * dxFromStart + dzFromStart * dzFromStart > maxRadius * maxRadius) {
                continue;
            }
            
            // Expand neighbors
            for (int[] offset : MoveCost.OFFSETS) {
                int nx = current.x + offset[0];
                int ny = current.y + offset[1];
                int nz = current.z + offset[2];
                movesConsidered++;
                
                // Bounds check
                if (ny < -64 || ny > 320) continue;
                
                // Chunk loaded check
                if (!level.isLoaded(new BlockPos(nx, ny, nz))) continue;
                
                double moveCost = MoveCost.costOf(level, current.x, current.y, current.z,
                        offset[0], offset[1], offset[2]);
                if (moveCost >= MoveCost.VOID_COST) continue;
                
                double tentativeCost = current.cost + moveCost;
                
                long hash = PathNode.hash(nx, ny, nz);
                PathNode neighbor = getOrCreateNode(nx, ny, nz);
                
                // Only update if this is a significant improvement (Baritone's MIN_IMPROVEMENT)
                if (tentativeCost + 0.01 < neighbor.cost) {
                    neighbor.previous = current;
                    neighbor.cost = tentativeCost;
                    neighbor.heuristic = PathNode.heuristic(nx, ny, nz, goalX, goalY, goalZ);
                    neighbor.combinedCost = tentativeCost + neighbor.heuristic;
                    
                    if (neighbor.inOpenSet) {
                        // Decrease-key: remove and re-add (PriorityQueue doesn't support decrease-key)
                        openSet.remove(neighbor);
                    }
                    openSet.add(neighbor);
                    neighbor.inOpenSet = true;
                }
            }
        }
        
        // Return best path found so far (Baritone pattern)
        DevLog.info("PATHFINDER_BEST_SO_FAR", "nodes={}, moves={}, bestDist={}, elapsed={}ms",
                nodesExplored, movesConsidered, String.format("%.1f", bestHeuristic),
                System.currentTimeMillis() - startTime);
        
        if (bestHeuristic < 3.0) {
            // Close enough — return partial path
            return new PathResult(reconstructPath(bestNode), true, nodesExplored);
        }
        
        return new PathResult(Collections.emptyList(), false, nodesExplored);
    }
    
    private PathNode getOrCreateNode(int x, int y, int z) {
        long hash = PathNode.hash(x, y, z);
        PathNode node = nodeMap.get(hash);
        if (node == null) {
            node = new PathNode(x, y, z);
            nodeMap.put(hash, node);
        }
        return node;
    }
    
    private List<BlockPos> reconstructPath(PathNode end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        PathNode current = end;
        while (current != null) {
            path.addFirst(current.toBlockPos());
            current = current.previous;
        }
        return path;
    }
    
    public void cancel() {
        this.cancelled = true;
    }
}