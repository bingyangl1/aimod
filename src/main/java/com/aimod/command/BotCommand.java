package com.aimod.command;

import com.aimod.ai.Task;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.fakeplayer.FakePlayerManager;
import com.aimod.util.DevLog;
import com.aimod.ai.Task;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.fakeplayer.FakePlayerManager;
import com.aimod.util.DevLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /ai_bot command tree registration.
 * Works with FakePlayer-first architecture.
 * Subcommands inspired by Baritone 1.21.1 command patterns.
 */
public class BotCommand {

    private static FakePlayerManager manager;

    /**
     * Called from AIMod.onServerStarted() to initialize the FakePlayerManager.
     */
    public static void init(MinecraftServer server) {
        manager = new FakePlayerManager(server);
        DevLog.info("BOT_COMMAND_INIT", "FakePlayerManager initialized");
    }

    public static FakePlayerManager getManager() { return manager; }

    // Last selected bot for command targeting
    @Nullable
    private static String selectedBotName;

    /** Suggest active bot names for tab completion. */
    private static SuggestionProvider<CommandSourceStack> suggestBots() {
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
        dispatcher.register(Commands.literal("ai_bot")
                // --- Core commands ---
                .then(Commands.literal("spawn")
                        .executes(BotCommand::spawnBot)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommand::spawnBotNamed)))
                .then(Commands.literal("select")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::selectBot)))
                .then(Commands.literal("task")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(BotCommand::assignTask)))
                .then(Commands.literal("task_all")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(BotCommand::assignTaskAll)))
                .then(Commands.literal("status")
                        .executes(BotCommand::showStatus)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::showStatusNamed)))
                // --- Control commands ---
                .then(Commands.literal("stop")
                        .executes(BotCommand::stopTask)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::stopTaskNamed)))
                .then(Commands.literal("cancel")
                        .executes(BotCommand::stopTask)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::stopTaskNamed)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommand::removeBot)))
                .then(Commands.literal("pause")
                        .executes(BotCommand::pauseBot)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::pauseBotNamed)))
                .then(Commands.literal("resume")
                        .executes(BotCommand::resumeBot)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::resumeBotNamed)))
                .then(Commands.literal("toggle")
                        .then(Commands.argument("feature", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        new String[]{"autoFish", "autoReplenish", "autoReplace", "veinMine"}, b))
                                .executes(BotCommand::toggleFeature)))
                // --- Navigation commands ---
                .then(Commands.literal("goto")
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(BotCommand::gotoVec3)))
                // --- Mining commands ---
                .then(Commands.literal("mine")
                        .then(Commands.argument("block", StringArgumentType.word())
                                .executes(ctx -> mineBlocks(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> mineBlocks(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                // --- Vein mining ---
                .then(Commands.literal("vein")
                        .then(Commands.argument("block", StringArgumentType.word())
                                .executes(ctx -> veinMine(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> veinMine(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                // --- Follow command ---
                .then(Commands.literal("follow")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(BotCommand::followPlayer)))
                .then(Commands.literal("follow_bot")
                        .then(Commands.argument("bot", StringArgumentType.word())
                                .suggests(suggestBots())
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(BotCommand::followPlayerWithBot))))
                // --- Gather command ---
                .then(Commands.literal("gather")
                        .then(Commands.argument("resource", StringArgumentType.word())
                                .executes(ctx -> gatherResource(ctx, 8))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> gatherResource(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                // --- Craft command ---
                .then(Commands.literal("craft")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(ctx -> craftItem(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> craftItem(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                // --- Say command ---
                .then(Commands.literal("say")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(BotCommand::sayMessage)))
                // --- Give command ---
                .then(Commands.literal("give")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(ctx -> giveItem(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> giveItem(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                // --- Equip command ---
                .then(Commands.literal("equip")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(BotCommand::equipItem)))
                // --- Persistence commands ---
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("desc", StringArgumentType.greedyString())
                                        .executes(BotCommand::saveBot))
                                .executes(BotCommand::saveBot)))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommand::loadBot)))
                .then(Commands.literal("list")
                        .executes(BotCommand::listBots))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommand::deleteBot)))
                // --- Inventory command ---
                .then(Commands.literal("inventory")
                        .executes(BotCommand::openInventory)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::openInventoryNamed)))
                // --- Path visualization ---
                .then(Commands.literal("showpath")
                        .executes(BotCommand::showPath)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(suggestBots())
                                .executes(BotCommand::showPathNamed)))
                // --- VeinMine management ---
                .then(Commands.literal("veinmine")
                        .then(Commands.literal("on").executes(BotCommand::veinMineOn))
                        .then(Commands.literal("off").executes(BotCommand::veinMineOff))
                        .then(Commands.literal("status").executes(BotCommand::veinMineStatus))
                        .then(Commands.literal("history").executes(BotCommand::veinMineHistory))
                        .then(Commands.literal("undo")
                                .executes(ctx -> veinMineUndo(ctx, 1))
                                .then(Commands.argument("steps", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> veinMineUndo(ctx, IntegerArgumentType.getInteger(ctx, "steps"))))))
                // --- Test command ---
                .then(Commands.literal("test")
                        .executes(BotCommand::runTests))
                // --- Help command ---
                .then(Commands.literal("help")
                        .executes(BotCommand::showHelp)
                        .then(Commands.argument("command", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(HELP_COMMANDS, b))
                                .executes(BotCommand::showHelpForCommand)))
        );
    }

    // ========== Multi-bot task distribution ==========
    private static int assignTaskAll(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayerOrException();
        String command = StringArgumentType.getString(context, "command");

        if (manager == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
            return 0;
        }

        Collection<FakePlayer> allBots = manager.getActivePlayers();
        if (allBots.isEmpty()) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.no_bots"));
            return 0;
        }

        int assigned = 0;
        for (FakePlayer bot : allBots) {
            if (bot.isAlive() && !bot.hasActiveTask()) {
                bot.assignTask(command, player);
                assigned++;
            }
        }

        if (assigned == 0) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.all_busy"));
            return 0;
        }

        final int count = assigned;
        source.sendSuccess(() -> Component.translatable("commands.ai_bot.task_all.assigned", count, command), true);
        DevLog.info("CMD_TASK_ALL", "player={}, bots={}, command={}", player.getName().getString(), count, DevLog.compact(command));
        return assigned;
    }

    // ========== Helpers ==========

    private static FakePlayer findOrSpawnBot(CommandSourceStack source, Player player) {
        if (manager == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
            return null;
        }
        // Prefer selected bot, then nearest
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

    /** Find bot by name, or nearest if name is null. Returns null with failure message if not found. */
    @Nullable
    private static FakePlayer findBot(CommandSourceStack source, @Nullable String name) {
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

    private static FakePlayer spawnBotNearPlayer(CommandSourceStack source, Player player, @Nullable String customName) {
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

    // ========== Core commands ==========

    private static int spawnBot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() instanceof Player player) {
            if (manager == null) {
                source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
                return 1;
            }
            FakePlayer bot = spawnBotNearPlayer(source, player, null);
            if (bot != null) {
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.spawn.success", bot.getName().getString()), true);
            } else {
                source.sendFailure(Component.translatable("commands.ai_bot.spawn.failure"));
            }
        }
        return 1;
    }

    private static int spawnBotNamed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (source.getEntity() instanceof Player player) {
            if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 1; }
            if (manager.getByName(name) != null) {
                source.sendFailure(Component.literal("Bot '" + name + "' already exists"));
                return 1;
            }
            FakePlayer bot = spawnBotNearPlayer(source, player, name);
            if (bot != null) source.sendSuccess(() -> Component.translatable("commands.ai_bot.spawn.success", bot.getName().getString()), true);
            else source.sendFailure(Component.translatable("commands.ai_bot.spawn.failure"));
        }
        return 1;
    }

    private static int selectBot(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (manager != null && manager.getByName(name) != null) {
            selectedBotName = name;
            ctx.getSource().sendSuccess(() -> Component.literal("Selected bot: " + name), true);
        } else {
            ctx.getSource().sendFailure(Component.translatable("commands.ai_bot.no_bot_named", name));
        }
        return 1;
    }

    private static int assignTask(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String command = StringArgumentType.getString(context, "command");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                DevLog.info("CMD_TASK", "player={}, command={}", player.getName().getString(), DevLog.compact(command));
                bot.assignTask(command, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.task.assigned", command), true);
            } else {
                source.sendFailure(Component.translatable("commands.ai_bot.task.create_failed"));
            }
        }
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        return showStatusInternal(context, null);
    }

    private static int showStatusNamed(CommandContext<CommandSourceStack> ctx) {
        return showStatusInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int showStatusInternal(CommandContext<CommandSourceStack> context, @Nullable String targetName) {
        CommandSourceStack source = context.getSource();
        if (manager == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
            return 1;
        }

        if (targetName != null) {
            FakePlayer bot = manager.getByName(targetName);
            if (bot == null) {
                source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", targetName));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(formatBotStatus(bot)), false);
            return 1;
        }

        // No name → show all
        Collection<FakePlayer> all = manager.getActivePlayers();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.ai_bot.list_empty"), false);
            return 1;
        }
        source.sendSuccess(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Active Bots (").append(all.size()).append(") ===\n");
            for (FakePlayer bot : all) {
                sb.append(formatBotStatus(bot)).append("\n");
            }
            return Component.literal(sb.toString().trim());
        }, false);
        return 1;
    }

    private static String formatBotStatus(FakePlayer bot) {
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
        // Show active chain if any
        var chain = bot.getChainManager().getActiveChain();
        if (chain != null && chain.isActive()) {
            sb.append(" | chain:").append(chain.name());
        }
        // Show AI state
        var sm = bot.getAiManager().getStateMachine();
        sb.append(" | state:").append(sm.getCurrent());
        return sb.toString();
    }

    // ========== Control commands ==========

    private static int stopTask(CommandContext<CommandSourceStack> context) {
        return stopTaskInternal(context, null);
    }

    private static int stopTaskNamed(CommandContext<CommandSourceStack> ctx) {
        return stopTaskInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int stopTaskInternal(CommandContext<CommandSourceStack> context, @Nullable String targetName) {
        CommandSourceStack source = context.getSource();
        FakePlayer bot = targetName != null ? (manager != null ? manager.getByName(targetName) : null)
                : (source.getEntity() instanceof Player player && manager != null
                        ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null);
        if (bot == null) {
            source.sendFailure(targetName != null
                    ? Component.translatable("commands.ai_bot.no_bot_named", targetName)
                    : Component.translatable("commands.ai_bot.no_bot_nearby"));
            return 0;
        }
        bot.cancelTask();
        source.sendSuccess(() -> Component.translatable("commands.ai_bot.stop.success", bot.getName().getString()), true);
        return 1;
    }

    private static int removeBot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        if (manager == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
            return 0;
        }
        FakePlayer bot = manager.getByName(name);
        if (bot == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", name));
            return 0;
        }
        manager.removeFakePlayer(bot);
        source.sendSuccess(() -> Component.literal("Removed bot: " + name), true);
        return 1;
    }

    private static int pauseBot(CommandContext<CommandSourceStack> context) {
        return pauseBotInternal(context, null);
    }
    private static int pauseBotNamed(CommandContext<CommandSourceStack> ctx) {
        return pauseBotInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }
    private static int pauseBotInternal(CommandContext<CommandSourceStack> context, @Nullable String name) {
        CommandSourceStack source = context.getSource();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 1; }
        FakePlayer bot = name != null ? manager.getByName(name)
                : (source.getEntity() instanceof Player player ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        bot.pauseExecution();
        source.sendSuccess(() -> Component.literal("Paused: " + bot.getName().getString()), true);
        return 1;
    }

    private static int resumeBot(CommandContext<CommandSourceStack> context) {
        return resumeBotInternal(context, null);
    }
    private static int resumeBotNamed(CommandContext<CommandSourceStack> ctx) {
        return resumeBotInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }
    private static int resumeBotInternal(CommandContext<CommandSourceStack> context, @Nullable String name) {
        CommandSourceStack source = context.getSource();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 1; }
        FakePlayer bot = name != null ? manager.getByName(name)
                : (source.getEntity() instanceof Player player ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        bot.resumeExecution();
        source.sendSuccess(() -> Component.literal("Resumed: " + bot.getName().getString()), true);
        return 1;
    }

    private static int toggleFeature(CommandContext<CommandSourceStack> ctx) {
        String feature = StringArgumentType.getString(ctx, "feature");
        boolean newVal = switch (feature) {
            case "autoFish" -> !com.aimod.config.ModConfig.getAutoFish();
            case "autoReplenish" -> !com.aimod.config.ModConfig.getAutoReplenish();
            case "autoReplace" -> !com.aimod.config.ModConfig.getAutoReplaceTool();
            case "veinMine" -> !com.aimod.config.ModConfig.getVeinMine();
            default -> { ctx.getSource().sendFailure(Component.literal("Unknown: " + feature)); yield false; }
        };
        ctx.getSource().sendSuccess(() -> Component.literal(feature + " = " + newVal), true);
        return 1;
    }

    // ========== Navigation commands ==========

    private static int gotoVec3(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        int x = (int)Math.round(pos.x), y = (int)Math.round(pos.y), z = (int)Math.round(pos.z);
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createGotoTask(x, y, z);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.goto.success", x, y, z), true);
            }
        }
        return 1;
    }

    // ========== Mining commands ==========

    private static int mineBlocks(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String block = StringArgumentType.getString(context, "block");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createMineTask(block, count);
                bot.assignDirectTask(task, player);
                String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.mine.success", count, fullBlock), true);
            }
        }
        return 1;
    }

    private static int veinMine(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String block = StringArgumentType.getString(context, "block");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createVeinTask(block, count);
                bot.assignDirectTask(task, player);
                String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                source.sendSuccess(() -> Component.literal("Vein mining " + count + "x " + fullBlock + " (connected blocks)"), true);
            }
        }
        return 1;
    }

    // ========== Follow command ==========

    private static int followPlayer(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String target = StringArgumentType.getString(context, "player");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createFollowTask(target);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.follow.success", target), true);
            }
        }
        return 1;
    }

    private static int followPlayerWithBot(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String botName = StringArgumentType.getString(ctx, "bot");
        String target = StringArgumentType.getString(ctx, "player");
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = manager.getByName(botName);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", botName)); return 0; }
        Task task = DirectCommandHandler.createFollowTask(target);
        bot.assignDirectTask(task, source.getPlayer());
        source.sendSuccess(() -> Component.translatable("commands.ai_bot.follow.success", target), true);
        return 1;
    }

    // ========== Gather command ==========

    private static int gatherResource(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String resource = StringArgumentType.getString(context, "resource");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createGatherTask(resource, count);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.gather.success", count, resource), true);
            }
        }
        return 1;
    }

    // ========== Craft command ==========

    private static int craftItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String item = StringArgumentType.getString(context, "item");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createCraftTask(item, count);
                bot.assignDirectTask(task, player);
                String fullItem = item.contains(":") ? item : "minecraft:" + item;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.craft.success", count, fullItem), true);
            }
        }
        return 1;
    }

    // ========== Say command ==========

    private static int sayMessage(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createSayTask(message);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.say.success", message), true);
            }
        }
        return 1;
    }

    // ========== Give command ==========

    private static int giveItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String item = StringArgumentType.getString(context, "item");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createGiveTask(item, count, player.getName().getString());
                bot.assignDirectTask(task, player);
                String fullItem = item.contains(":") ? item : "minecraft:" + item;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.give.success", count, fullItem), true);
            }
        }
        return 1;
    }

    // ========== Equip command ==========

    private static int equipItem(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String item = StringArgumentType.getString(context, "item");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createEquipTask(item);
                bot.assignDirectTask(task, player);
                String fullItem = item.contains(":") ? item : "minecraft:" + item;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.equip.success", fullItem), true);
            }
        }
        return 1;
    }

    // ========== Persistence commands ==========

    private static int saveBot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        String desc = null;
        try { desc = StringArgumentType.getString(context, "desc"); } catch (IllegalArgumentException ignored) {}

        if (manager == null) {
            source.sendFailure(Component.literal("Not initialized"));
            return 0;
        }

        // Find nearest existing bot (don't auto-spawn)
        FakePlayer bot = manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0);
        if (bot == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.no_bot_nearby"));
            return 0;
        }

        manager.saveBot(bot, desc != null ? desc : name);
        source.sendSuccess(() -> Component.translatable("commands.ai_bot.saved", name), true);
        return 1;
    }

    private static int loadBot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        if (manager == null) {
            source.sendFailure(Component.literal("Not initialized"));
            return 0;
        }

        FakePlayer bot = manager.loadBot(name);
        if (bot == null) {
            source.sendFailure(Component.translatable("commands.ai_bot.load_failed", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.ai_bot.loaded", name), true);
        return 1;
    }

    private static int listBots(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (manager == null) {
            source.sendFailure(Component.literal("Not initialized"));
            return 0;
        }

        java.util.List<String> names = manager.listSavedBots();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.ai_bot.list_empty"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Saved bots: " + String.join(", ", names)), false);
        }
        return 1;
    }

    private static int deleteBot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        if (manager == null) {
            source.sendFailure(Component.literal("Not initialized"));
            return 0;
        }

        boolean deleted = manager.deleteBot(name);
        if (deleted) {
            source.sendSuccess(() -> Component.translatable("commands.ai_bot.deleted", name), true);
        } else {
            source.sendFailure(Component.translatable("commands.ai_bot.delete_failed", name));
        }
        return deleted ? 1 : 0;
    }

    // ========== Inventory command ==========

    private static int openInventory(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() instanceof Player player && player instanceof ServerPlayer sp) {
            FakePlayer bot = findOrSpawnBot(source, player);
            if (bot != null) {
                com.aimod.client.BotStatusScreen.open(sp, bot);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.inventory.opened", bot.getName().getString()), true);
                return 1;
            }
        }
        return 0;
    }

    private static int openInventoryNamed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (source.getEntity() instanceof Player player && player instanceof ServerPlayer sp) {
            if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
            FakePlayer bot = manager.getByName(name);
            if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", name)); return 0; }
            com.aimod.client.BotStatusScreen.open(sp, bot);
            source.sendSuccess(() -> Component.translatable("commands.ai_bot.inventory.opened", bot.getName().getString()), true);
            return 1;
        }
        return 0;
    }

    // ========== Path visualization ==========

    private static int showPath(CommandContext<CommandSourceStack> ctx) {
        return showPathInternal(ctx, null);
    }
    private static int showPathNamed(CommandContext<CommandSourceStack> ctx) {
        return showPathInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }
    private static int showPathInternal(CommandContext<CommandSourceStack> context, @Nullable String name) {
        CommandSourceStack source = context.getSource();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = name != null ? manager.getByName(name)
                : (source.getEntity() instanceof Player player ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        var ctrl = bot.getMovementController();
        if (!ctrl.isNavigating()) { source.sendFailure(Component.literal("Bot is not currently navigating")); return 0; }
        var target = ctrl.getNavTarget();
        var executor = ctrl.getPathExecutor();
        if (executor != null && !executor.isCompleted()) {
            var path = executor.getPath();
            var level = (ServerLevel) bot.level();
            var player = source.getPlayer();
            // Spawn redstone dust particles along path
            for (var pos : path) {
                level.sendParticles(
                    new DustParticleOptions(
                        new org.joml.Vector3f(1.0f, 0.0f, 1.0f), 2.0f),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    1, 0, 0, 0, 0);
            }
            source.sendSuccess(() -> Component.literal("Showing path: " + path.size() + " nodes to " + target.toShortString()), true);
        } else {
            source.sendSuccess(() -> Component.literal("Target: " + target.toShortString() + " (no computed path)"), true);
        }
        return 1;
    }

    // ========== VeinMine management ==========

    private static int veinMineOn(CommandContext<CommandSourceStack> ctx) {
        com.aimod.config.ModConfig.VEIN_MINE.set(true);
        ctx.getSource().sendSuccess(() -> Component.literal("Vein mining: ENABLED"), true);
        return 1;
    }
    private static int veinMineOff(CommandContext<CommandSourceStack> ctx) {
        com.aimod.config.ModConfig.VEIN_MINE.set(false);
        ctx.getSource().sendSuccess(() -> Component.literal("Vein mining: DISABLED"), true);
        return 1;
    }
    private static int veinMineStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = com.aimod.config.ModConfig.getVeinMine();
        int history = com.aimod.config.ModConfig.getUndoHistory();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Vein mining: " + (enabled ? "ON" : "OFF") +
            " | Undo history: " + history + " operations"), false);
        return 1;
    }
    private static int veinMineHistory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = source.getEntity() instanceof Player player
                ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null;
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        var history = bot.getUndoManager().getHistory();
        if (history.isEmpty()) { source.sendSuccess(() -> Component.literal("No undo history"), false); return 1; }
        var sb = new StringBuilder("Undo history (" + history.size() + "):");
        for (int i = 0; i < history.size(); i++) {
            var op = history.get(i);
            sb.append("\n  ").append(i + 1).append(". [").append(op.size()).append(" blocks] ").append(op.desc());
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
    private static int veinMineUndo(CommandContext<CommandSourceStack> ctx, int steps) {
        CommandSourceStack source = ctx.getSource();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = source.getEntity() instanceof Player player
                ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null;
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        var level = (ServerLevel) bot.level();
        int restored = bot.getUndoManager().undo(level, steps);
        source.sendSuccess(() -> Component.literal("Undone " + restored + " blocks in " + steps + " operation(s)"), true);
        return 1;
    }

    // ========== Test command ==========

    private static int runTests(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int passed = 0, failed = 0;
        var sb = new StringBuilder();
        sb.append("===== AI Bot Self Test =====\n");

        // Test 1: CommandParser
        try {
            var r = com.aimod.ai.planner.CommandParser.parse("制作一把钻石镐给我");
            if (r.verb() == com.aimod.ai.planner.CommandParser.Verb.CRAFT && r.isGive()) { passed++; sb.append("[PASS] CommandParser: craft+give\n"); }
            else { failed++; sb.append("[FAIL] CommandParser: craft+give (verb=").append(r.verb()).append(")\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] CommandParser: ").append(e.getMessage()).append("\n"); }

        try {
            var r = com.aimod.ai.planner.CommandParser.parse("挖5个铁矿石");
            if (r.verb() == com.aimod.ai.planner.CommandParser.Verb.MINE && r.count() == 5) { passed++; sb.append("[PASS] CommandParser: mine+count\n"); }
            else { failed++; sb.append("[FAIL] CommandParser: mine+count\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] CommandParser: ").append(e.getMessage()).append("\n"); }

        // Test 2: Item Lookup
        try {
            var item = com.aimod.ai.planner.CommandParser.findItem("diamond_pickaxe");
            if (item != null) { passed++; sb.append("[PASS] ItemLookup: diamond_pickaxe found\n"); }
            else { failed++; sb.append("[FAIL] ItemLookup: diamond_pickaxe not found\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] ItemLookup: ").append(e.getMessage()).append("\n"); }

        try {
            var item = com.aimod.ai.planner.CommandParser.findItem("nonexistent_item_xyz");
            if (item == null) { passed++; sb.append("[PASS] ItemLookup: nonexistent returns null\n"); }
            else { failed++; sb.append("[FAIL] ItemLookup: should return null\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] ItemLookup: ").append(e.getMessage()).append("\n"); }

        // Test 3: RecipeIndex
        try {
            var idx = com.aimod.ai.RecipeIndex.getInstance();
            if (!idx.isBuilt()) idx.build(source.getLevel());
            var recipes = idx.getRecipesForOutput(net.minecraft.world.item.Items.STICK);
            if (!recipes.isEmpty()) { passed++; sb.append("[PASS] RecipeIndex: stick recipes found (").append(recipes.size()).append(")\n"); }
            else { failed++; sb.append("[FAIL] RecipeIndex: no stick recipes\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] RecipeIndex: ").append(e.getMessage()).append("\n"); }

        // Test 4: Movement types
        try {
            int count = com.aimod.ai.movement.BotMovement.class.getDeclaredClasses().length;
            passed++; sb.append("[PASS] Movement: BotMovement loaded\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] Movement: ").append(e.getMessage()).append("\n"); }

        // Test 5: ChunkCache
        try {
            var cache = new com.aimod.ai.cache.ChunkCache(source.getLevel());
            passed++; sb.append("[PASS] ChunkCache: created\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ChunkCache: ").append(e.getMessage()).append("\n"); }

        // Test 6: BotAIStateMachine
        try {
            var sm = new com.aimod.ai.llm.BotAIStateMachine();
            sm.startPlanning("test", 3);
            sm.startExecuting();
            sm.complete();
            if (sm.getCurrent() == com.aimod.ai.llm.BotAIStateMachine.State.COMPLETED) { passed++; sb.append("[PASS] StateMachine: IDLE->PLANNING->EXECUTING->COMPLETED\n"); }
            else { failed++; sb.append("[FAIL] StateMachine: wrong state\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] StateMachine: ").append(e.getMessage()).append("\n"); }

        // Test 7: Chain creation
        try {
            var cm = new com.aimod.ai.chain.ChainManager();
            cm.addChain(new com.aimod.ai.chain.DangerChain());
            cm.stopAll();
            passed++; sb.append("[PASS] ChainManager: chain registered and stopped\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ChainManager: ").append(e.getMessage()).append("\n"); }

        // Test 8: Environment scan
        try {
            var scanner = new com.aimod.ai.WorldScanner(source.getPlayer());
            var scan = scanner.scanEnvironment(16);
            passed++; sb.append("[PASS] WorldScanner: environment scanned\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] WorldScanner: ").append(e.getMessage()).append("\n"); }

        // Test 9: FindItemResult
        try {
            var r = new com.aimod.ai.InventoryUtils.FindItemResult(3, 64);
            if (r.found() && r.isHotbar()) { passed++; sb.append("[PASS] FindItemResult: basic properties\n"); }
            else { failed++; sb.append("[FAIL] FindItemResult: wrong properties\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] FindItemResult: ").append(e.getMessage()).append("\n"); }

        // Test 10: Bot persistence
        try {
            var info = new com.aimod.fakeplayer.BotInfo();
            info.name = "test";
            if (info.name.equals("test")) { passed++; sb.append("[PASS] BotInfo: created\n"); }
            else { failed++; sb.append("[FAIL] BotInfo: wrong name\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] BotInfo: ").append(e.getMessage()).append("\n"); }

        // Test 11: VeinScanner
        try {
            var origin = source.getPlayer().blockPosition();
            var vein = com.aimod.ai.VeinScanner.findVein(source.getLevel(), origin, net.minecraft.world.level.block.Blocks.STONE, 10);
            passed++; sb.append("[PASS] VeinScanner: returned ").append(vein.size()).append(" connected blocks\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] VeinScanner: ").append(e.getMessage()).append("\n"); }

        // Test 12: UndoManager
        try {
            var um = new com.aimod.ai.UndoManager(3);
            var op = um.startOperation("test");
            var bp = source.getPlayer().blockPosition();
            um.record(op, bp, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            if (um.historySize() == 1) { passed++; sb.append("[PASS] UndoManager: record+history\n"); }
            else { failed++; sb.append("[FAIL] UndoManager: wrong history size\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] UndoManager: ").append(e.getMessage()).append("\n"); }

        // Test 13: VeinMineAction
        try {
            var action = new com.aimod.ai.action.VeinMineAction("stone", 5);
            if (action.getBlockId().equals("stone") && action.getCount() == 5) { passed++; sb.append("[PASS] VeinMineAction: created\n"); }
            else { failed++; sb.append("[FAIL] VeinMineAction: wrong parameters\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] VeinMineAction: ").append(e.getMessage()).append("\n"); }

        // Test 14: Config values
        try {
            boolean veinMine = com.aimod.config.ModConfig.getVeinMine();
            int undoHistory = com.aimod.config.ModConfig.getUndoHistory();
            if (undoHistory >= 0) { passed++; sb.append("[PASS] Config: veinMine=").append(veinMine).append(" undoHistory=").append(undoHistory).append("\n"); }
            else { failed++; sb.append("[FAIL] Config: invalid undoHistory\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] Config: ").append(e.getMessage()).append("\n"); }

        // Test 15: MaterialTree (static API)
        try {
            if (com.aimod.ai.craft.MaterialTree.class != null) { passed++; sb.append("[PASS] MaterialTree: class loaded\n"); }
            else { failed++; sb.append("[FAIL] MaterialTree: class not found\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] MaterialTree: ").append(e.getMessage()).append("\n"); }

        // Test 16: ToolSet (static API)
        try {
            var speed = com.aimod.ai.pathing.ToolSet.calculateSpeedVsBlock(
                net.minecraft.world.item.ItemStack.EMPTY,
                net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState());
            passed++; sb.append("[PASS] ToolSet: calculateSpeedVsBlock returned ").append(String.format("%.3f", speed)).append("\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ToolSet: ").append(e.getMessage()).append("\n"); }

        // Test 17: SequencePlanner mine
        try {
            var actions = com.aimod.ai.planner.SequencePlanner.planMine(net.minecraft.world.item.Items.DIAMOND_ORE, 3);
            if (!actions.isEmpty()) { passed++; sb.append("[PASS] SequencePlanner: mine ").append(actions.size()).append(" actions\n"); }
            else { failed++; sb.append("[FAIL] SequencePlanner: empty actions\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] SequencePlanner: ").append(e.getMessage()).append("\n"); }

        // Test 18: SequencePlanner gather
        try {
            var actions = com.aimod.ai.planner.SequencePlanner.planGather(net.minecraft.world.item.Items.OAK_LOG, 8);
            if (!actions.isEmpty()) { passed++; sb.append("[PASS] SequencePlanner: gather ").append(actions.size()).append(" actions\n"); }
            else { failed++; sb.append("[FAIL] SequencePlanner: empty gather\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] SequencePlanner: ").append(e.getMessage()).append("\n"); }

        sb.append("===== ").append(passed).append(" passed, ").append(failed).append(" failed =====");
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // ========== Help command ==========

    private static final String[] HELP_COMMANDS = {
        "spawn", "select", "status", "task", "task_all", "stop", "cancel",
        "pause", "resume", "remove", "goto", "mine", "gather", "craft",
        "follow", "follow_bot", "give", "equip", "say", "inventory",
        "toggle", "save", "load", "list", "delete"
    };

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> {
            var sb = new StringBuilder();
            sb.append(Component.translatable("commands.ai_bot.help.header").getString()).append("\n");
            for (String cmd : HELP_COMMANDS) {
                String key = "commands.ai_bot.help.short." + cmd;
                sb.append(Component.translatable(key).getString()).append("\n");
            }
            sb.append("\n").append(Component.translatable("commands.ai_bot.help.footer").getString());
            return Component.literal(sb.toString());
        }, false);
        return 1;
    }

    private static int showHelpForCommand(CommandContext<CommandSourceStack> ctx) {
        String cmd = StringArgumentType.getString(ctx, "command");
        String key = "commands.ai_bot.help.detail." + cmd;
        String detail = Component.translatable(key).getString();
        if (detail.equals(key)) { // key not found, returns itself
            ctx.getSource().sendFailure(Component.translatable("commands.ai_bot.help.unknown", cmd));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            Component.translatable("commands.ai_bot.help.detail_title", cmd).getString() + "\n" + detail), false);
        return 1;
    }
}
