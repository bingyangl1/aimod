package com.example.aimod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.entity.ModEntities;
import com.example.aimod.util.DevLog;

public class BotCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai_bot")
                .then(Commands.literal("spawn")
                        .executes(BotCommand::spawnBot))
                .then(Commands.literal("task")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(BotCommand::assignTask)))
                .then(Commands.literal("status")
                        .executes(BotCommand::showStatus)));
    }

    private static int spawnBot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() instanceof Player player) {
            ServerLevel level = source.getLevel();
            BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
            
            AIBotEntity bot = ModEntities.AI_BOT.get().create(level);
            if (bot != null) {
                bot.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                level.addFreshEntity(bot);

                DevLog.info("CMD_SPAWN", "player={}, bot={}, hasFakePlayer={}, pos={}",
                        player.getName().getString(), bot.getStringUUID(), bot.hasFakePlayer(), pos.toShortString());

                if (bot.hasFakePlayer()) {
                    source.sendSuccess(() -> Component.literal("AI Bot (with FakePlayer) spawned at " + pos.toShortString()), true);
                } else {
                    source.sendSuccess(() -> Component.literal("AI Bot spawned at " + pos.toShortString()), true);
                }
            } else {
                DevLog.warn("CMD_SPAWN_FAIL", "player={}, pos={}", player.getName().getString(), pos.toShortString());
                source.sendFailure(Component.literal("Failed to spawn AI Bot"));
            }
        }
        return 1;
    }

    private static int assignTask(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String command = StringArgumentType.getString(context, "command");
        
        if (source.getEntity() instanceof Player player) {
            ServerLevel level = source.getLevel();
            DevLog.info("CMD_TASK", "player={}, command={}",
                    player.getName().getString(), DevLog.compact(command));
            AIBotEntity nearestBot = findNearestBot(level, player);
            if (nearestBot == null) {
                nearestBot = spawnBotNearPlayer(level, player);
                if (nearestBot != null) {
                    DevLog.info("CMD_TASK_AUTOSPAWN", "player={}, bot={}",
                            player.getName().getString(), nearestBot.getStringUUID());
                    source.sendSuccess(() -> Component.literal("No AI Bot nearby; spawned one for this task."), true);
                }
            }
            
            if (nearestBot != null) {
                DevLog.info("CMD_TASK_ASSIGN", "player={}, bot={}, command={}",
                        player.getName().getString(), nearestBot.getStringUUID(), DevLog.compact(command));
                nearestBot.assignTask(command, player);
                source.sendSuccess(() -> Component.literal("Task assigned: " + command + " (processing...)"), true);
            } else {
                DevLog.warn("CMD_TASK_FAIL", "player={}, reason=bot_create_failed, command={}",
                        player.getName().getString(), DevLog.compact(command));
                source.sendFailure(Component.literal("Failed to create an AI Bot for this task."));
            }
        }
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() instanceof Player player) {
            ServerLevel level = source.getLevel();
            AIBotEntity nearestBot = findNearestBot(level, player);
            
            if (nearestBot != null) {
                StringBuilder status = new StringBuilder();
                status.append("Task: ").append(
                        nearestBot.getCurrentTask() != null
                                ? nearestBot.getCurrentTask().getDescription() + " (" + nearestBot.getCurrentTask().getStatus() + ", action " +
                                nearestBot.getCurrentTask().getCurrentActionIndex() + "/" +
                                nearestBot.getCurrentTask().getActionCount() + ")"
                                : "No task assigned"
                );
                status.append("\nFakePlayer: ").append(nearestBot.hasFakePlayer() ? "Active" : "Standby (lazy init)");
                if (nearestBot.hasFakePlayer()) {
                    status.append(" (").append(nearestBot.getFakePlayer().getName().getString()).append(")");
                }
                DevLog.info("CMD_STATUS", "player={}, bot={}, status={}",
                        player.getName().getString(), nearestBot.getStringUUID(), status);
                final String finalStatus = status.toString();
                source.sendSuccess(() -> Component.literal("Bot status:\n" + finalStatus), true);
            } else {
                DevLog.info("CMD_STATUS", "player={}, status=no_bot_nearby", player.getName().getString());
                source.sendFailure(Component.literal("No AI Bot found nearby."));
            }
        }
        return 1;
    }

    private static AIBotEntity findNearestBot(ServerLevel level, Player player) {
        return level.getEntitiesOfClass(AIBotEntity.class, 
                player.getBoundingBox().inflate(16.0), 
                bot -> true)
                .stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
                .orElse(null);
    }

    private static AIBotEntity spawnBotNearPlayer(ServerLevel level, Player player) {
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
        AIBotEntity bot = ModEntities.AI_BOT.get().create(level);
        if (bot == null) {
            DevLog.warn("BOT_AUTOSPAWN_FAIL", "player={}, pos={}", player.getName().getString(), pos.toShortString());
            return null;
        }
        bot.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        level.addFreshEntity(bot);

        DevLog.info("BOT_AUTOSPAWN", "player={}, bot={}, hasFakePlayer={}, pos={}",
                player.getName().getString(), bot.getStringUUID(), bot.hasFakePlayer(), pos.toShortString());
        return bot;
    }
}
