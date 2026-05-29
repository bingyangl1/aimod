package com.aimod.command;

import com.aimod.ai.Task;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Task management commands: task, task_all, stop, cancel, pause, resume, status.
 */
public class BotCommandTask implements SubCommand {

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("task")
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(BotCommandTask::assignTask)))
            .then(Commands.literal("task_all")
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(BotCommandTask::assignTaskAll)))
            .then(Commands.literal("status")
                .executes(BotCommandTask::showStatus)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandTask::showStatusNamed)))
            .then(Commands.literal("stop")
                .executes(BotCommandTask::stopTask)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandTask::stopTaskNamed)))
            .then(Commands.literal("cancel")
                .executes(BotCommandTask::stopTask)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandTask::stopTaskNamed)))
            .then(Commands.literal("pause")
                .executes(BotCommandTask::pauseBot)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandTask::pauseBotNamed)))
            .then(Commands.literal("resume")
                .executes(BotCommandTask::resumeBot)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandTask::resumeBotNamed)));
    }

    private static int assignTask(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String command = StringArgumentType.getString(context, "command");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
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

    private static int assignTaskAll(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayerOrException();
        String command = StringArgumentType.getString(context, "command");
        var manager = BotCommand.getManager();

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

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        return showStatusInternal(context, null);
    }

    private static int showStatusNamed(CommandContext<CommandSourceStack> ctx) {
        return showStatusInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int showStatusInternal(CommandContext<CommandSourceStack> context, @Nullable String targetName) {
        CommandSourceStack source = context.getSource();
        var manager = BotCommand.getManager();

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
            source.sendSuccess(() -> Component.literal(BotCommand.formatBotStatus(bot)), false);
            return 1;
        }

        Collection<FakePlayer> all = manager.getActivePlayers();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.ai_bot.list_empty"), false);
            return 1;
        }
        source.sendSuccess(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Active Bots (").append(all.size()).append(") ===\n");
            for (FakePlayer bot : all) {
                sb.append(BotCommand.formatBotStatus(bot)).append("\n");
            }
            return Component.literal(sb.toString().trim());
        }, false);
        return 1;
    }

    private static int stopTask(CommandContext<CommandSourceStack> context) {
        return stopTaskInternal(context, null);
    }

    private static int stopTaskNamed(CommandContext<CommandSourceStack> ctx) {
        return stopTaskInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int stopTaskInternal(CommandContext<CommandSourceStack> context, @Nullable String targetName) {
        CommandSourceStack source = context.getSource();
        var manager = BotCommand.getManager();
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

    private static int pauseBot(CommandContext<CommandSourceStack> context) {
        return pauseBotInternal(context, null);
    }

    private static int pauseBotNamed(CommandContext<CommandSourceStack> ctx) {
        return pauseBotInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int pauseBotInternal(CommandContext<CommandSourceStack> context, @Nullable String name) {
        CommandSourceStack source = context.getSource();
        var manager = BotCommand.getManager();
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
        var manager = BotCommand.getManager();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 1; }
        FakePlayer bot = name != null ? manager.getByName(name)
                : (source.getEntity() instanceof Player player ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        bot.resumeExecution();
        source.sendSuccess(() -> Component.literal("Resumed: " + bot.getName().getString()), true);
        return 1;
    }
}
