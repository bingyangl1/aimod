package com.example.aimod.ai.action;

import com.example.aimod.fakeplayer.FakePlayer;
import net.minecraft.network.chat.Component;

public class SayAction extends Action {
    private final String message;

    public SayAction(String message) {
        super("Say: " + message);
        this.message = message == null ? "" : message;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return !message.isBlank();
    }

    @Override
    public void execute(FakePlayer bot) {
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
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
