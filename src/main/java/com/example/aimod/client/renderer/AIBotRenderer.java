package com.example.aimod.client.renderer;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import com.example.aimod.AIMod;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.client.model.AIBotModel;

public class AIBotRenderer extends MobRenderer<AIBotEntity, AIBotModel<AIBotEntity>> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public AIBotRenderer(EntityRendererProvider.Context context) {
        super(context, new AIBotModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(AIBotEntity entity) {
        return TEXTURE;
    }
}
