package com.example.aimod.command;

import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.fakeplayer.FakePlayerManager;
import com.example.aimod.util.DevLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class BotCommand {
    private static FakePlayerManager manager;

    public static void init(net.minecraft.server.MinecraftServer server) {
        manager = new FakePlayerManager(server);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai_bot")
                .then(Commands.literal("spawn").executes(BotCommand::spawnBot))
                .then(Commands.literal("task")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(BotCommand::assignTask)))
                .then(Commands.literal("status").executes(BotCommand::showStatus)));
    }

    private static int spawnBot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) return 1;
        ServerLevel level = source.getLevel();
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
        String name = "AI_Bot_" + (manager != null ? manager.getActiveCount() + 1 : 1);
        FakePlayer bot = createBot(level, name, Vec3.atBottomCenterOf(pos));
        if (bot != null) {
            source.sendSuccess(() -> Component.literal("AI Bot spawned: " + bot.getName().getString()), true);
        } else {
            source.sendFailure(Component.literal("Failed to spawn AI Bot"));
        }
        return 1;
    }

    private static int assignTask(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String command = StringArgumentType.getString(context, "command");
        if (!(source.getEntity() instanceof Player player)) return 1;
        ServerLevel level = source.getLevel();
        FakePlayer bot = findNearestBot(level, player);
        if (bot == null) {
            BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
            bot = createBot(level, "AI_Bot_1", Vec3.atBottomCenterOf(pos));
            if (bot != null) source.sendSuccess(() -> Component.literal("No AI Bot nearby; spawned one."), true);
        }
        if (bot != null) {
            DevLog.info("CMD_TASK_ASSIGN", "player={}, bot={}, command={}",
                    player.getName().getString(), bot.getStringUUID(), DevLog.compact(command));
            bot.assignTask(command, player);
            source.sendSuccess(() -> Component.literal("Task assigned: " + command), true);
        } else {
            source.sendFailure(Component.literal("Failed to create an AI Bot."));
        }
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) return 1;
        FakePlayer bot = findNearestBot(source.getLevel(), player);
        if (bot != null) {
            String status = "Task: " + (bot.getCurrentTask() != null
                    ? bot.getCurrentTask().getDescription() + " (" + bot.getCurrentTask().getStatus() + ")"
                    : "No task");
            source.sendSuccess(() -> Component.literal("Bot " + bot.getName().getString() + "\n" + status), true);
        } else {
            source.sendFailure(Component.literal("No AI Bot found nearby."));
        }
        return 1;
    }

    private static FakePlayer createBot(ServerLevel level, String name, Vec3 pos) {
        if (manager == null) manager = new FakePlayerManager(level.getServer());
        FakePlayer bot = manager.createFakePlayer(name, level, pos);
        if (bot != null) DevLog.info("BOT_SPAWN", "name={}, uuid={}", name, bot.getStringUUID());
        return bot;
    }

    private static FakePlayer findNearestBot(ServerLevel level, Player player) {
        if (manager == null) return null;
        return manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0);
    }

    public static FakePlayerManager getManager() { return manager; }
}