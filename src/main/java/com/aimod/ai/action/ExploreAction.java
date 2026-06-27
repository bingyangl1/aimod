package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/**
 * Explore action — automatically explore unknown areas of the world.
 *
 * <p>Inspired by Baritone's ExploreProcess. The bot wanders to discover
 * new terrain, following a spiral pattern outward from the starting position.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Generate a random waypoint within exploration radius</li>
 *   <li>Navigate to the waypoint</li>
 *   <li>Wait briefly to allow chunk loading</li>
 *   <li>Generate next waypoint (spiral outward)</li>
 *   <li>Repeat until duration expires or area fully explored</li>
 * </ol>
 */
public class ExploreAction extends Action {

    private static final int EXPLORE_RADIUS = 100; // blocks from start
    private static final int WAYPOINT_INTERVAL = 40; // ticks between waypoints

    private final BlockPos startPos;
    private final int maxWaypoints;
    private int waypointsVisited;
    private int tickCounter;
    private BlockPos currentWaypoint;
    private final Random random = new Random();

    public ExploreAction(BlockPos startPos, int maxWaypoints) {
        super("Explore world (" + maxWaypoints + " waypoints)");
        this.startPos = startPos;
        this.maxWaypoints = maxWaypoints;
        this.waypointsVisited = 0;
        this.tickCounter = 0;
        this.currentWaypoint = null;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return bot.level() instanceof ServerLevel;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            waypointsVisited = 0;
            tickCounter = 0;
            DevLog.info("EXPLORE_START", "radius={}, maxWaypoints={}", EXPLORE_RADIUS, maxWaypoints);
        }

        if (status == ActionStatus.IN_PROGRESS) {
            if (waypointsVisited >= maxWaypoints) {
                status = ActionStatus.COMPLETED;
                DevLog.info("EXPLORE_DONE", "waypointsVisited={}", waypointsVisited);
                return;
            }

            tickCounter++;

            // Generate new waypoint if needed
            if (currentWaypoint == null || hasArrived(bot)) {
                if (currentWaypoint != null) {
                    waypointsVisited++;
                    DevLog.info("EXPLORE_WAYPOINT_REACHED", "visited={}/{}", waypointsVisited, maxWaypoints);
                }
                currentWaypoint = generateWaypoint();
                DevLog.info("EXPLORE_WAYPOINT", "target={}", currentWaypoint.toShortString());
            }

            // Navigate to waypoint
            navigateTo(bot, currentWaypoint, 1.0);

            // Timeout for each waypoint
            if (tickCounter > WAYPOINT_INTERVAL * 3) {
                DevLog.warn("EXPLORE_TIMEOUT", "waypoint={}", currentWaypoint.toShortString());
                currentWaypoint = null;
                tickCounter = 0;
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private boolean hasArrived(FakePlayer bot) {
        if (currentWaypoint == null) return false;
        double dist = bot.distanceToSqr(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5);
        return dist < 4.0;
    }

    /**
     * Generate a random waypoint within exploration radius.
     * Uses a spiral pattern to expand outward.
     */
    private BlockPos generateWaypoint() {
        // Spiral outward: radius increases with waypoints visited
        double angle = random.nextDouble() * 2 * Math.PI;
        double radius = EXPLORE_RADIUS * (0.3 + 0.7 * (double) waypointsVisited / maxWaypoints);

        int dx = (int) (Math.cos(angle) * radius);
        int dz = (int) (Math.sin(angle) * radius);

        return startPos.offset(dx, 0, dz);
    }
}
