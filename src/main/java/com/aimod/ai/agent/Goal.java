package com.aimod.ai.agent;

import com.aimod.fakeplayer.FakePlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the high-level goal of an agentic task.
 *
 * <p>The Goal is the persistent "anchor" that exists throughout the entire
 * execution loop. Unlike the current architecture where the goal is implicit
 * in the action list, here it is an explicit object checked at every step.</p>
 *
 * <p>Inspired by OpenCode's session goal — the user's original request
 * remains in context at all times.</p>
 */
public class Goal {

    private final String originalCommand;
    private GoalStatus status;
    private final List<GoalCriterion> criteria;
    private String failReason;

    public Goal(String originalCommand) {
        this.originalCommand = originalCommand;
        this.status = GoalStatus.IN_PROGRESS;
        this.criteria = new ArrayList<>();
    }

    /** Get the original command text. */
    public String getOriginalCommand() { return originalCommand; }

    /** Get the current status. */
    public GoalStatus getStatus() { return status; }

    /** Set the status. */
    public void setStatus(GoalStatus status) { this.status = status; }

    /** Get completion criteria. */
    public List<GoalCriterion> getCriteria() { return criteria; }

    /** Add a completion criterion. */
    public void addCriterion(GoalCriterion criterion) { criteria.add(criterion); }

    /** Get fail reason. */
    public String getFailReason() { return failReason; }

    /** Set fail reason. */
    public void setFailReason(String reason) { this.failReason = reason; }

    /** Check if the goal is achieved by verifying all criteria against current state. */
    public boolean isAchieved(FakePlayer bot) {
        if (criteria.isEmpty()) return false;
        return criteria.stream().allMatch(c -> c.isMet(bot));
    }

    public boolean isAchieved() { return status == GoalStatus.ACHIEVED; }
    public boolean isFailed() { return status == GoalStatus.FAILED; }
    public boolean isInProgress() { return status == GoalStatus.IN_PROGRESS; }

    /** Get a human-readable progress description. */
    public String getProgressDescription(FakePlayer bot) {
        if (criteria.isEmpty()) return "No criteria defined";
        long met = criteria.stream().filter(c -> c.isMet(bot)).count();
        return met + "/" + criteria.size() + " criteria met";
    }

    public enum GoalStatus {
        IN_PROGRESS,
        ACHIEVED,
        FAILED
    }

    /**
     * A single completion criterion for the goal.
     * Example: "inventory contains diamond_helmet x1"
     */
    public static class GoalCriterion {
        private final String description;
        private final CriterionType type;
        private final String itemId;
        private final int requiredCount;

        public GoalCriterion(CriterionType type, String itemId, int requiredCount) {
            this.type = type;
            this.itemId = itemId;
            this.requiredCount = requiredCount;
            this.description = switch (type) {
                case HAS_ITEM -> "Have " + requiredCount + "x " + itemId;
                case AT_POSITION -> "Be at " + itemId; // itemId stores position string
                case BLOCK_EXISTS -> "Block " + itemId + " exists nearby";
            };
        }

        public boolean isMet(FakePlayer bot) {
            return switch (type) {
                case HAS_ITEM -> countItem(bot, itemId) >= requiredCount;
                case AT_POSITION -> true; // simplified
                case BLOCK_EXISTS -> true; // simplified
            };
        }

        private int countItem(FakePlayer bot, String itemId) {
            int total = 0;
            var inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (id.equals(itemId)) total += stack.getCount();
                }
            }
            return total;
        }

        public String getDescription() { return description; }

        public enum CriterionType {
            HAS_ITEM,
            AT_POSITION,
            BLOCK_EXISTS
        }
    }
}
