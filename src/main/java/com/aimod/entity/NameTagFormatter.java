package com.aimod.entity;

import com.aimod.ai.llm.BotAIStateMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shared name tag formatting logic for both AIBotEntity and FakePlayer.
 *
 * <p>Name tag (above head): two-line display — bot name (green) + state/task/progress.
 * <p>Tab list: single-line compact display — bot name + status icon + progress.
 *
 * <p>Status icons:
 * <ul>
 *   <li>💤 IDLE — 空闲</li>
 *   <li>🧠 PLANNING — 规划中</li>
 *   <li>⛏ EXECUTING — 执行中</li>
 *   <li>🔄 REPLAN — 重新规划</li>
 *   <li>⏸ PAUSED — 已暂停</li>
 *   <li>✅ COMPLETED — 完成</li>
 *   <li>❌ FAILED — 失败</li>
 * </ul>
 */
public class NameTagFormatter {

    /** Build display name for name tag (above head) — two lines. */
    public static Component buildDisplayName(String botName, BotAIStateMachine sm) {
        if (!com.aimod.config.ModConfig.getShowTaskAboveHead()) {
            return Component.literal(botName);
        }

        MutableComponent name = Component.literal(botName).withStyle(ChatFormatting.GREEN);

        if (sm == null) return name;

        var state = sm.getCurrent();
        if (state == BotAIStateMachine.State.IDLE) {
            return name.append(Component.literal(" 空闲").withStyle(ChatFormatting.GRAY)); // 空闲
        }

        MutableComponent statusLine = Component.empty();

        // Format: [x/y 当前步骤] 任务描述
        if (sm.getActionsTotal() > 0) {
            String stepDesc = sm.getCurrentActionDesc();
            if (stepDesc != null && !stepDesc.isBlank()) {
                String truncatedStep = stepDesc.length() > 16 ? stepDesc.substring(0, 16) + "…" : stepDesc;
                truncatedStep = truncatedStep.replace("%", "%%");
                statusLine.append(Component.literal(
                        String.format("[%d/%d %s]", sm.getActionsDone() + 1, sm.getActionsTotal(), truncatedStep))
                        .withStyle(ChatFormatting.AQUA));
            } else {
                statusLine.append(Component.literal(
                        String.format("[%d/%d]", sm.getActionsDone() + 1, sm.getActionsTotal()))
                        .withStyle(ChatFormatting.AQUA));
            }
        } else {
            statusLine.append(formatState(state));
        }

        String desc = sm.getTaskDescription();
        if (desc != null && !desc.isBlank()) {
            String truncated = desc.length() > 20 ? desc.substring(0, 20) + "…" : desc;
            statusLine.append(Component.literal(" " + truncated).withStyle(ChatFormatting.YELLOW));
        }

        return name.append(Component.literal("\n")).append(statusLine);
    }

    /**
     * Build compact display name for Tab list — single line.
     * Format: [Bot] name ⛏ 3/24 挖矿
     */
    public static Component buildTabListName(String botName, BotAIStateMachine sm) {
        MutableComponent name = Component.literal("[Bot] " + botName).withStyle(ChatFormatting.GREEN);

        if (sm == null) return name;

        var state = sm.getCurrent();
        String icon = getStateIcon(state);
        ChatFormatting iconColor = getStateColor(state);

        // Idle: just show icon
        if (state == BotAIStateMachine.State.IDLE) {
            return name.append(Component.literal(" " + icon).withStyle(iconColor));
        }

        // Active: show icon + progress
        MutableComponent status = Component.literal(" " + icon).withStyle(iconColor);

        if (sm.getActionsTotal() > 0) {
            status.append(Component.literal(
                    String.format(" %d/%d", sm.getActionsDone() + 1, sm.getActionsTotal()))
                    .withStyle(ChatFormatting.AQUA));
        }

        // Add compact task description
        String desc = sm.getTaskDescription();
        if (desc != null && !desc.isBlank()) {
            String truncated = desc.length() > 12 ? desc.substring(0, 12) + "…" : desc;
            status.append(Component.literal(" " + truncated).withStyle(ChatFormatting.YELLOW));
        }

        return name.append(status);
    }

    /** Get status icon for state. */
    private static String getStateIcon(BotAIStateMachine.State state) {
        return switch (state) {
            case IDLE      -> "空闲";     // 空闲
            case PLANNING  -> "规划中"; // 规划中
            case EXECUTING -> "执行中"; // 执行中
            case REPLAN    -> "重规划"; // 重规划
            case PAUSED    -> "已暂停"; // 已暂停
            case COMPLETED -> "完成";       // 完成
            case FAILED    -> "失败";       // 失败
            default        -> "";
        };
    }

    /** Get color for state. */
    private static ChatFormatting getStateColor(BotAIStateMachine.State state) {
        return switch (state) {
            case IDLE      -> ChatFormatting.GRAY;
            case PLANNING  -> ChatFormatting.AQUA;
            case EXECUTING -> ChatFormatting.WHITE;
            case REPLAN    -> ChatFormatting.LIGHT_PURPLE;
            case PAUSED    -> ChatFormatting.GOLD;
            case COMPLETED -> ChatFormatting.GREEN;
            case FAILED    -> ChatFormatting.RED;
            default        -> ChatFormatting.WHITE;
        };
    }

    /** Format state as Chinese text (for name tag). */
    public static MutableComponent formatState(BotAIStateMachine.State state) {
        return switch (state) {
            case PLANNING  -> Component.literal("[规划中]").withStyle(ChatFormatting.AQUA);
            case EXECUTING -> Component.literal("[执行中]").withStyle(ChatFormatting.WHITE);
            case PAUSED    -> Component.literal("[已暂停]").withStyle(ChatFormatting.GOLD);
            case REPLAN    -> Component.literal("[重新规划]").withStyle(ChatFormatting.LIGHT_PURPLE);
            case COMPLETED -> Component.literal("[完成]").withStyle(ChatFormatting.GREEN);
            case FAILED    -> Component.literal("[失败]").withStyle(ChatFormatting.RED);
            default        -> Component.empty();
        };
    }
}
