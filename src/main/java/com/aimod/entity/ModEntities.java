package com.aimod.entity;

import com.aimod.AIMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, AIMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<AIBotEntity>> AI_BOT =
            ENTITIES.register("ai_bot", () -> EntityType.Builder.of(AIBotEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .build("ai_bot"));

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}