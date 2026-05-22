package com.example.aimod.client.model;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import com.example.aimod.entity.AIBotEntity;

public class AIBotModel<T extends AIBotEntity> extends HumanoidModel<T> {
    public AIBotModel(ModelPart root) {
        super(root);
    }
}