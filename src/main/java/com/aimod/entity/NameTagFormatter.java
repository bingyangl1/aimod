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
        statusLine.append(formatState(state));

        String desc = sm.getTaskDescription();
        if (desc != null && !desc.isBlank()) {
            String truncated = desc.length() > 20 ? desc.substring(0, 20) + "…" : desc;
            statusLine.append(Component.literal(" " + truncated).withStyle(ChatFormatting.YELLOW));
        }

        if (sm.getActionsTotal() > 0) {
            statusLine.append(Component.literal(
                    String.format(" (%d/%d)", sm.getActionsDone(), sm.getActionsTotal()))
                    .withStyle(ChatFormatting.GRAY));
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
