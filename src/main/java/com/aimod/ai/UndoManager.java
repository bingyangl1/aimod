package com.aimod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedList;

/**
 * Records block changes for undo capability.
 * Default max 10 operations. Each operation may contain multiple block changes.
 */
public class UndoManager {

    public record BlockChange(BlockPos pos, BlockState before, BlockState after) {}

    public static class Operation {
        final java.util.List<BlockChange> changes = new LinkedList<>();
        final String description;
        final long timestamp;
        Operation(String desc) { description = desc; timestamp = System.currentTimeMillis(); }
        public int size() { return changes.size(); }
        public String desc() { return description; }
    }

    private final LinkedList<Operation> history = new LinkedList<>();
    private final int maxOperations;

    public UndoManager() { this(10); }
    public UndoManager(int maxOps) { this.maxOperations = maxOps; }

    /** Start recording a new operation (e.g., vein mine). */
    public Operation startOperation(String description) {
        var op = new Operation(description);
        history.addFirst(op);
        if (history.size() > maxOperations) history.removeLast();
        return op;
    }

    /** Record a single block change within the current operation. */
    public void record(Operation op, BlockPos pos, BlockState before, BlockState after) {
        op.changes.add(new BlockChange(pos, before, after));
    }

    /** Undo the most recent operation. Returns number of blocks restored. */
    public int undoLast(ServerLevel level) {
        if (history.isEmpty()) return 0;
        var op = history.removeFirst();
        int count = 0;
        // Reverse order (LIFO)
        for (int i = op.changes.size() - 1; i >= 0; i--) {
            var change = op.changes.get(i);
            level.setBlock(change.pos, change.before, 3);
            count++;
        }
        return count;
    }

    /** Undo N operations. Returns total blocks restored. */
    public int undo(ServerLevel level, int steps) {
        int total = 0;
        for (int i = 0; i < steps && !history.isEmpty(); i++) {
            total += undoLast(level);
        }
        return total;
    }

    public int historySize() { return history.size(); }
    public java.util.List<Operation> getHistory() { return history; }
}
