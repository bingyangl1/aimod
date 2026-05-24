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
 *   AsyncPathfinder async = new AsyncPathfinder();
 *   CalculationContext ctx = new CalculationContext(level, bot);
 *   ctx.preloadRegion(start, 20); // preload on server thread
 *   async.requestPath(ctx, start, goal, result -> {
 *       if (result.isFound()) { ... }
 *   });
 *   // In tick loop:
 *   async.tick(); // delivers completed results to callbacks
 */
public class AsyncPathfinder {

    private volatile Pathfinder inProgress;
    private final AtomicReference<PathResult> pendingResult = new AtomicReference<>();
    private volatile Consumer<PathResult> pendingCallback;
    private volatile boolean computing;

    /**
     * Request a path computation using a pre-built CalculationContext (thread-safe, recommended).
     * The context should have its region preloaded on the server thread before calling this.
     *
     * @param ctx      pre-built CalculationContext with preloaded block data
     * @param start    start position
     * @param goal     goal position
     * @param callback called on the server thread (via tick()) when computation finishes
     */
    public void requestPath(CalculationContext ctx, BlockPos start, BlockPos goal, Consumer<PathResult> callback) {
        cancel();

        pendingCallback = callback;
        computing = true;

        Pathfinder pathfinder = new Pathfinder(ctx, start, goal);
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
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Legacy: request path using raw ServerLevel (server-thread only, not thread-safe for async).
     * Kept for backward compatibility with synchronous callers.
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
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public void tick() {
        PathResult result = pendingResult.getAndSet(null);
        if (result != null && pendingCallback != null) {
            Consumer<PathResult> cb = pendingCallback;
            pendingCallback = null;
            cb.accept(result);
        }
    }

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

    public boolean isComputing() { return computing; }
}