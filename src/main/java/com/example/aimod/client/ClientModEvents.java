package com.example.aimod.client;

import com.example.aimod.AIMod;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.entity.ModEntities;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = AIMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AI_BOT.get(), AIBotRenderer::new);
    }

    /** Simple humanoid renderer for the AI bot using the player model. */
    public static class AIBotRenderer extends HumanoidMobRenderer<AIBotEntity, HumanoidModel<AIBotEntity>> {
        public AIBotRenderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        }

        @Override
        public net.minecraft.resources.ResourceLocation getTextureLocation(AIBotEntity entity) {
            return net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/entity/player/skin/steve.png");
        }
    }
}