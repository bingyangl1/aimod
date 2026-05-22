package com.example.aimod.ai.action;

import com.example.aimod.entity.AIBotEntity;
import net.minecraft.network.chat.Component;

public class SayAction extends Action {
    private final String message;

    public SayAction(String message) {
        super("Say: " + message);
        this.message = message == null ? "" : message;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        return !message.isBlank();
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status != ActionStatus.PENDING) {
            return;
        }
        if (bot.level().getServer() != null) {
            bot.level().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("[AI Bot] " + message),
                    false);
            status = ActionStatus.COMPLETED;
        } else {
            status = ActionStatus.FAILED;
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
