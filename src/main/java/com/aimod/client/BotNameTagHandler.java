package com.aimod.client;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * Client-side event handler that replaces player name tags with custom names
 * set via entity metadata (setCustomName). This is needed because
 * Player.getDisplayName() returns the game profile name, not the custom name.
 */
@OnlyIn(Dist.CLIENT)
public class BotNameTagHandler {

    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (event.getEntity() instanceof Player player) {
            Component customName = player.getCustomName();
            if (customName != null && !customName.getString().isEmpty()) {
                event.setContent(customName);
            }
        }
    }
}
