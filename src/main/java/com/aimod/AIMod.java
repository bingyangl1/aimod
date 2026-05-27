package com.aimod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.aimod.ai.cache.ChunkCache;
import com.aimod.client.BotStatusScreen;
import com.aimod.command.BotCommand;
import com.aimod.entity.ModEntities;
import com.aimod.entity.AIBotEntity;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.fakeplayer.FakePlayerManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

@Mod(AIMod.MODID)
public class AIMod {
    public static final String MODID = "aimod";
    private static final Logger LOGGER = LogManager.getLogger();

    @Nullable
    private static ChunkCache chunkCache;

    public AIMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(this::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(this::onEntityInteract);
        ModEntities.register(modEventBus);
        com.aimod.client.ModMenuTypes.register(modEventBus);
        modEventBus.addListener(this::registerAttributes);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, com.aimod.config.ModConfig.SPEC);
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
        chunkCache = new ChunkCache(event.getServer().overworld());
        LOGGER.info("AI Mod: Server started, ChunkCache initialized");

        // Auto-load bots from previous session
        FakePlayerManager manager = BotCommand.getManager();
        if (manager != null) {
            int loaded = autoLoadBots(manager, event.getServer());
            if (loaded > 0) {
                LOGGER.info("AI Mod: Auto-loaded {} bot(s)", loaded);
            }
        }
    }

    private void onChunkLoad(final ChunkEvent.Load event) {
        if (chunkCache != null && event.getLevel() instanceof ServerLevel
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            chunkCache.queueForPacking(chunk);
        }
    }

    private void onChunkUnload(final ChunkEvent.Unload event) {
        // ChunkCache keeps packed data; unloaded chunks remain available from cache
    }

    /** Get the global chunk cache (null before server start). */
    @Nullable
    public static ChunkCache getChunkCache() { return chunkCache; }

    /**
     * Handle right-click on a bot entity to open the status screen.
     * Works for both FP mode (FakePlayer) and Dual mode (AIBotEntity).
     */
    private void onEntityInteract(final PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer viewer)) return;

        // FP mode: player right-clicked a FakePlayer directly
        if (event.getTarget() instanceof FakePlayer fp) {
            BotStatusScreen.open(viewer, fp);
            event.setCanceled(true);
            return;
        }

        // Dual mode: player right-clicked the Mob wrapper
        if (event.getTarget() instanceof AIBotEntity bot) {
            FakePlayer fp = bot.getFakePlayer();
            if (fp != null) {
                BotStatusScreen.open(viewer, fp);
                event.setCanceled(true);
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