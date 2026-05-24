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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;
import java.util.Collection;

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
                                        new String[]{"autoFish", "autoReplenish", "autoReplace"}, b))
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
                // --- Help command ---
                .then(Commands.literal("help")
                        .executes(BotCommand::showHelp))
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

    // ========== Help command ==========

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String[] keys = {
                "commands.ai_bot.help.header",
                "commands.ai_bot.help.spawn",
                "commands.ai_bot.help.task",
                "commands.ai_bot.help.status",
                "commands.ai_bot.help.stop",
                "commands.ai_bot.help.pause",
                "commands.ai_bot.help.goto",
                "commands.ai_bot.help.mine",
                "commands.ai_bot.help.follow",
                "commands.ai_bot.help.gather",
                "commands.ai_bot.help.craft",
                "commands.ai_bot.help.say",
                "commands.ai_bot.help.give",
                "commands.ai_bot.help.equip",
                "commands.ai_bot.help.save",
                "commands.ai_bot.help.load",
                "commands.ai_bot.help.list",
                "commands.ai_bot.help.delete",
                "commands.ai_bot.help.help"
        };
        source.sendSuccess(() -> {
            net.minecraft.network.chat.MutableComponent msg = Component.empty();
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) msg.append("\n");
                msg.append(Component.translatable(keys[i]));
            }
            return msg;
        }, false);
        return 1;
    }
}
