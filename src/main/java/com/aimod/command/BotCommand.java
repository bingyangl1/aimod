package com.aimod.command;

import com.aimod.ai.Task;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.fakeplayer.FakePlayerManager;
import com.aimod.util.DevLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * /ai_bot command tree registration.
 * Coordinates subcommand handlers and provides shared helper methods.
 */
public class BotCommand {

    private static FakePlayerManager manager;
    @Nullable
    private static String selectedBotName;

    /**
     * Called from AIMod.onServerStarted() to initialize the FakePlayerManager.
     */
    public static void init(MinecraftServer server) {
        manager = new FakePlayerManager(server);
        DevLog.info("BOT_COMMAND_INIT", "FakePlayerManager initialized");
    }

    public static FakePlayerManager getManager() { return manager; }

    @Nullable
    public static String getSelectedBotName() { return selectedBotName; }
    public static void setSelectedBotName(@Nullable String name) { selectedBotName = name; }

    /** Suggest active bot names for tab completion. */
    public static SuggestionProvider<CommandSourceStack> suggestBots() {
        return (ctx, builder) -> {
            if (manager != null) {
                var names = manager.getActivePlayers().stream()
                        .map(p -> p.getName().getString()).toList();
                return SharedSuggestionProvider.suggest(names, builder);
            }
            return builder.buildFuture();
        };
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ai_bot");

        // Register all subcommand groups
        new BotCommandTask().register(root);
        new BotCommandAction().register(root);
        new BotCommandAdmin().register(root);
        new BotCommandConfig().register(root);
        new BotCommandTestCmd().register(root);
        new BotCommandWaypoint().register(root);
        new BotCommandHelp().register(root);

        dispatcher.register(root);
    }

    // ========== Shared helper methods ==========

    /**
     * Find selected/nearest bot, or auto-spawn one.
     */
    public static FakePlayer findOrSpawnBot(CommandSourceStack source, Player player) {
        if (manager == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
            return null;
        }
        FakePlayer bot = null;
        if (selectedBotName != null) bot = manager.getByName(selectedBotName);
        if (bot == null) bot = manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0);
        if (bot == null) {
            bot = spawnBotNearPlayer(source, player, null);
            if (bot != null) {
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.auto_spawn"), true);
            }
        }
        return bot;
    }

    /**
     * Find bot by name, or nearest if name is null.
     */
    @Nullable
    public static FakePlayer findBot(CommandSourceStack source, @Nullable String name) {
        if (manager == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
            return null;
        }
        if (name != null) {
            FakePlayer bot = manager.getByName(name);
            if (bot == null) {
                source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", name));
            }
            return bot;
        }
        Player player = source.getPlayer();
        if (player == null) return null;
        FakePlayer bot = manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0);
        if (bot == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.no_bot_nearby"));
        }
        return bot;
    }

    /**
     * Spawn a bot near the player.
     */
    public static FakePlayer spawnBotNearPlayer(CommandSourceStack source, Player player, @Nullable String customName) {
        if (manager == null) return null;
        ServerLevel level = source.getLevel();
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
        Vec3 spawnPos = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        String name = customName != null ? customName : "AI_Bot_" + (manager.getActiveCount() + 1);
        FakePlayer bot = manager.createFakePlayer(name, level, spawnPos);
        if (bot != null) {
            DevLog.info("BOT_SPAWN", "player={}, bot={}, pos={}", player.getName().getString(), bot.getStringUUID(), pos.toShortString());
        }
        return bot;
    }

    /**
     * Format a bot's status string.
     */
    public static String formatBotStatus(FakePlayer bot) {
        StringBuilder sb = new StringBuilder();
        sb.append(Component.translatable("commands.ai_bot.status.bot_name", bot.getName().getString()).getString());
        if (bot.getCurrentTask() != null) {
            Task t = bot.getCurrentTask();
            sb.append(" | ").append(t.getDescription()).append(" (").append(t.getStatus())
              .append(", ").append(t.getCurrentActionIndex()).append("/").append(t.getActionCount()).append(")");
        } else {
            sb.append(" | ").append(Component.translatable("commands.ai_bot.status.no_task").getString());
        }
        sb.append(" | ").append(bot.isPaused() ? "PAUSED" : "running");
        var chain = bot.getChainManager().getActiveChain();
        if (chain != null && chain.isActive()) {
            sb.append(" | chain:").append(chain.name());
        }
        var sm = bot.getAiManager().getStateMachine();
        sb.append(" | state:").append(sm.getCurrent());
        sb.append("\n  Memory: ").append(bot.getMemoryStore().getStats());
        return sb.toString();
    }
}
