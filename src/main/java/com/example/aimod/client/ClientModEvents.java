package com.example.aimod.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import com.example.aimod.AIMod;
import com.example.aimod.entity.ModEntities;
import com.example.aimod.client.renderer.AIBotRenderer;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = AIMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AI_BOT.get(), AIBotRenderer::new);
    }
}
