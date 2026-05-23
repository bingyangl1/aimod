package com.aimod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.aimod.command.BotCommand;
import com.aimod.entity.ModEntities;
import com.aimod.entity.AIBotEntity;
import com.aimod.fakeplayer.FakePlayerManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AIMod.MODID)
public class AIMod {
    public static final String MODID = "aimod";
    private static final Logger LOGGER = LogManager.getLogger();

    public AIMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        ModEntities.register(modEventBus);
        modEventBus.addListener(this::registerAttributes);
        modContainer.registerConfig(ModConfig.Type.COMMON, com.aimod.config.ModConfig.SPEC);
    }

    private void registerAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(ModEntities.AI_BOT.get(), AIBotEntity.createAttributes().build());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("AI Mod initializing - FakePlayer architecture");
    }

    private void registerCommands(final RegisterCommandsEvent event) {
        BotCommand.register(event.getDispatcher());
    }

    private void onServerStarted(final ServerStartedEvent event) {
        BotCommand.init(event.getServer());
        LOGGER.info("AI Mod: Server started");

        // Auto-load bots from previous session
        FakePlayerManager manager = BotCommand.getManager();
        if (manager != null) {
            int loaded = autoLoadBots(manager, event.getServer());
            if (loaded > 0) {
                LOGGER.info("AI Mod: Auto-loaded {} bot(s)", loaded);
            }
        }
    }

    private int autoLoadBots(FakePlayerManager manager, net.minecraft.server.MinecraftServer server) {
        var persistence = new com.aimod.fakeplayer.BotPersistence(server);
        var names = persistence.getAutoLoadList();
        int count = 0;
        for (String name : names) {
            try {
                var bot = manager.loadBot(name);
                if (bot != null) {
                    persistence.addToAutoLoad(name); // re-add for next restart
                    count++;
                    LOGGER.info("AI Mod: Auto-loaded bot '{}'", name);
                }
            } catch (Exception e) {
                LOGGER.warn("AI Mod: Failed to auto-load bot '{}': {}", name, e.getMessage());
            }
        }
        return count;
    }
}