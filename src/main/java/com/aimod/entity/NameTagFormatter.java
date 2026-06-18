package com.aimod.entity;

import com.aimod.ai.llm.BotAIStateMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shared name tag formatting logic for both AIBotEntity and FakePlayer.
 * Builds a two-line display name: bot name (green) + state/task/progress.
 */
public class NameTagFormatter {

    public static Component buildDisplayName(String botName, BotAIStateMachine sm) {
        if (!com.aimod.config.ModConfig.getShowTaskAboveHead()) {
            return Component.literal(botName);
        }

        MutableComponent name = Component.literal(botName).withStyle(ChatFormatting.GREEN);

        if (sm == null) return name;

        var state = sm.getCurrent();
        if (state == BotAIStateMachine.State.IDLE) {
            return name;
        }

        MutableComponent statusLine = Component.empty();

        // Format: [x/y 当前步骤] 任务描述
        if (sm.getActionsTotal() > 0) {
            String stepDesc = sm.getCurrentActionDesc();
            if (stepDesc != null && !stepDesc.isBlank()) {
                String truncatedStep = stepDesc.length() > 16 ? stepDesc.substring(0, 16) + "…" : stepDesc;
                // Escape % to prevent String.format misinterpretation
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
