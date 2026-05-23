package com.aimod.ai.pathing;

import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs pathfinding in a background thread so the server tick thread is never blocked.
 * Inspired by Baritone's PathingBehavior.findPathInNewThread().
 *
 * Usage:
 * <pre>
 *   AsyncPathfinder async = new AsyncPathfinder();
 *   async.requestPath(level, start, goal, result -> {
 *       // Called on the server thread after pathfinding completes
 *       if (result.isFound()) { ... }
 *   });
 *   // In tick loop:
 *   async.tick(); // delivers completed results to callbacks
 * </pre>
 */
public class AsyncPathfinder {

    /** The current in-progress pathfinding computation. */
    private volatile Pathfinder inProgress;
    /** Result waiting to be delivered on the server thread. */
    private final AtomicReference<PathResult> pendingResult = new AtomicReference<>();
    /** Callback for the pending result. */
    private volatile Consumer<PathResult> pendingCallback;
    /** Whether a pathfinding request is active. */
    private volatile boolean computing;

    /**
     * Request a path computation. If one is already in progress, it is cancelled first.
     *
     * @param level    the server level
     * @param start    start position
     * @param goal     goal position
     * @param callback called on the server thread (via tick()) when computation finishes
     */
    public void requestPath(ServerLevel level, BlockPos start, BlockPos goal, Consumer<PathResult> callback) {
        cancel();

        pendingCallback = callback;
        computing = true;

        Pathfinder pathfinder = new Pathfinder(level, start, goal);
        inProgress = pathfinder;

        Thread thread = new Thread(() -> {
            try {
                DevLog.info("ASYNC_PATH_START", "from={}, to={}", start.toShortString(), goal.toShortString());
                PathResult result = pathfinder.findPath();
                pendingResult.set(result);
                DevLog.info("ASYNC_PATH_DONE", "found={}, length={}", result.isFound(), result.getLength());
            } catch (Exception e) {
                DevLog.error("ASYNC_PATH_ERROR", "pathfinding failed", e);
                pendingResult.set(new PathResult(java.util.Collections.emptyList(), false, 0));
            } finally {
                inProgress = null;
                computing = false;
            }
        }, "AIMod-Pathfinder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY); // Don't starve the server
        thread.start();
    }

    /**
     * Tick the async pathfinder. Call from the server tick thread.
     * Delivers any completed result to the callback.
     */
    public void tick() {
        PathResult result = pendingResult.getAndSet(null);
        if (result != null && pendingCallback != null) {
            Consumer<PathResult> cb = pendingCallback;
            pendingCallback = null;
            cb.accept(result);
        }
    }

    /**
     * Cancel any in-progress pathfinding.
     */
    public void cancel() {
        Pathfinder pf = inProgress;
        if (pf != null) {
            pf.cancel();
        }
        inProgress = null;
        pendingResult.set(null);
        pendingCallback = null;
        computing = false;
    }

    /** Whether a pathfinding computation is in progress. */
    public boolean isComputing() { return computing; }
}
