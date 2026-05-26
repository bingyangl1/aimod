package com.aimod.ai.memory;

import com.aimod.ai.Task;
import com.aimod.ai.action.Action;
import com.aimod.util.DevLog;

import java.util.List;

/**
 * Assembles LLM prompt context from BotMemoryStore layers.
 *
 * <p>Token budget allocation (of maxTokens):
 * - 10%: task description + current action (mandatory)
 * - 60%: recent working memory observations (mandatory but truncatable)
 * - 20%: short-term summaries (optional, relevance-sorted)
 * - 10%: long-term knowledge (optional, task-relevant only)</p>
 */
public final class ContextAssembler {

    /** Characters per token (rough English text estimate). */
    private static final double CHARS_PER_TOKEN = 3.5;

    /** Minimum chars to reserve for task context block. */
    private static final int MIN_TASK_CHARS = 200;

    private ContextAssembler() {}

    /**
     * Assemble full context for an LLM call.
     *
     * @param store    the bot's memory store
     * @param task     current task (may be null for initial planning)
     * @param maxTokens target max tokens for the assembled context
     * @return formatted context string suitable for LLM prompt
     */
    public static String assemble(BotMemoryStore store, Task task, int maxTokens) {
        int maxChars = tokensToChars(maxTokens);
        if (maxChars < 500) maxChars = 500;

        int taskBudget = (int)(maxChars * 0.10);
        int workingBudget = (int)(maxChars * 0.60);
        int summaryBudget = (int)(maxChars * 0.20);
        int knowledgeBudget = (int)(maxChars * 0.10);

        StringBuilder ctx = new StringBuilder(maxChars);

        // 1. Task context (mandatory, up to 10%)
        String taskBlock = assembleTaskBlock(task, taskBudget);
        if (!taskBlock.isEmpty()) {
            ctx.append("## Current Task\n").append(taskBlock).append("\n\n");
        }

        // 2. Recent working memory (mandatory, up to 60%)
        String workingBlock = assembleWorkingMemory(store, workingBudget);
        if (!workingBlock.isEmpty()) {
            ctx.append("## Recent Observations\n").append(workingBlock).append("\n\n");
        }

        // 3. Short-term summaries (optional, up to 20%)
        if (summaryBudget > 200) {
            String summaryBlock = assembleSummaries(store, summaryBudget);
            if (!summaryBlock.isEmpty()) {
                ctx.append("## Activity Summary\n").append(summaryBlock).append("\n\n");
            }
        }

        // 4. Long-term knowledge (optional, up to 10%)
        if (knowledgeBudget > 200) {
            String knowledgeBlock = assembleKnowledge(store, task, knowledgeBudget);
            if (!knowledgeBlock.isEmpty()) {
                ctx.append("## Known Resources\n").append(knowledgeBlock).append("\n\n");
            }
        }

        int estimatedTokens = charsToTokens(ctx.length());
        DevLog.info("CONTEXT_ASSEMBLE", "chars={}, estTokens={}, budget={}",
                ctx.length(), estimatedTokens, maxTokens);

        if (ctx.length() > maxChars) {
            DevLog.warn("CONTEXT_OVER_BUDGET", "chars={}, max={}", ctx.length(), maxChars);
            return ctx.substring(0, maxChars - 3) + "...";
        }
        return ctx.toString();
    }

    /**
     * Estimate total tokens current memory would consume.
     * Used to decide whether to trigger compaction.
     */
    public static int estimateTotalTokens(BotMemoryStore store) {
        int tokens = 0;
        var recent = store.getRecent(store.workingMemorySize());
        for (var obs : recent) tokens += obs.estimateTokens();
        for (var sum : store.getShortTermSummaries()) tokens += charsToTokens(sum.length());
        return tokens;
    }

    // --------------- Block Assemblers ---------------

    private static String assembleTaskBlock(Task task, int maxChars) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(task.getDescription()).append("\n");
        sb.append("Progress: ").append(task.getCurrentActionIndex() + 1)
          .append("/").append(task.getActionCount()).append("\n");
        Action current = task.getCurrentAction();
        if (current != null) {
            sb.append("Current action: ").append(current.getDescription()).append("\n");
            sb.append("Status: ").append(current.getStatus()).append("\n");
        }
        String result = sb.toString();
        if (result.length() > maxChars) {
            return result.substring(0, Math.max(MIN_TASK_CHARS, maxChars) - 3) + "...";
        }
        return result;
    }

    private static String assembleWorkingMemory(BotMemoryStore store, int maxChars) {
        // Take recent observations, newest first, until we hit the char budget
        var recent = store.getRecent(store.workingMemorySize());
        if (recent.isEmpty()) return "";

        // Always include latest observation in full
        StringBuilder sb = new StringBuilder();
        int used = 0;

        // Latest observation (full detail)
        WorldObservation latest = recent.get(0);
        String latestBlock = latest.toContextBlock(maxChars / 2);
        sb.append(latestBlock);
        used += latestBlock.length();

        // Previous observations (compact form)
        for (int i = 1; i < recent.size() && i < 5; i++) {
            String compact = recent.get(i).toCompactString();
            if (used + compact.length() + 2 > maxChars) break;
            sb.append("\n  ").append(compact);
            used += compact.length() + 3;
        }

        return sb.toString();
    }

    private static String assembleSummaries(BotMemoryStore store, int maxChars) {
        List<String> summaries = store.getShortTermSummaries();
        if (summaries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int used = 0;
        // Show most recent summaries first
        for (int i = summaries.size() - 1; i >= 0 && used < maxChars; i--) {
            String s = summaries.get(i);
            if (used + s.length() + 2 > maxChars) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append("- ").append(s);
            used += s.length() + 3;
        }
        return sb.toString();
    }

    private static String assembleKnowledge(BotMemoryStore store, Task task, int maxChars) {
        // Only inject long-term knowledge if task involves gathering/mining
        if (task == null) return "";
        String desc = task.getDescription().toLowerCase();
        boolean needsResources = desc.contains("mine") || desc.contains("gather")
                || desc.contains("collect") || desc.contains("get") || desc.contains("find")
                || desc.contains("挖") || desc.contains("采集") || desc.contains("收集")
                || desc.contains("找");

        if (!needsResources) return "";

        // Get nearby known resources from the latest observation
        var recent = store.getRecent(1);
        if (recent.isEmpty()) return "";
        var resources = store.queryNearbyResources(recent.get(0).botPosition, 128);
        if (resources.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (var rl : resources) {
            if (rl.mined) continue;
            String line = rl.toString() + "\n";
            if (used + line.length() > maxChars) break;
            sb.append("- ").append(line);
            used += line.length() + 2;
        }
        return sb.toString();
    }

    // --------------- Token Math ---------------

    public static int charsToTokens(int chars) {
        return Math.max(1, (int)(chars / CHARS_PER_TOKEN));
    }

    public static int tokensToChars(int tokens) {
        return (int)(tokens * CHARS_PER_TOKEN);
    }
}
