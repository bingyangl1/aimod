package com.example.aimod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.entity.ModEntities;
import com.example.aimod.command.BotCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AIMod.MODID)
public class AIMod {
    public static final String MODID = "aimod";
    private static final Logger LOGGER = LogManager.getLogger();

    public AIMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerAttributes);
        
        ModEntities.register(modEventBus);
        
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        
        modContainer.registerConfig(ModConfig.Type.COMMON, com.example.aimod.config.ModConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("AI Mod initializing");
    }

    private void registerAttributes(final EntityAttributeCreationEvent event) {
        event.put(ModEntities.AI_BOT.get(), AIBotEntity.createAttributes().build());
    }

    private void registerCommands(final RegisterCommandsEvent event) {
        BotCommand.register(event.getDispatcher());
    }
}
