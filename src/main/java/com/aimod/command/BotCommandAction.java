package com.aimod.command;

import com.aimod.ai.Task;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Action execution commands: goto, mine, vein, follow, gather, craft, say, give, equip.
 */
public class BotCommandAction implements SubCommand {

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("goto")
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(BotCommandAction::gotoVec3)))
            .then(Commands.literal("mine")
                .then(Commands.argument("block", StringArgumentType.word())
                        .executes(ctx -> mineBlocks(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> mineBlocks(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("vein")
                .then(Commands.argument("block", StringArgumentType.word())
                        .executes(ctx -> veinMine(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> veinMine(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("follow")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(BotCommandAction::followPlayer)))
            .then(Commands.literal("follow_bot")
                .then(Commands.argument("bot", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(BotCommandAction::followPlayerWithBot))))
            .then(Commands.literal("gather")
                .then(Commands.argument("resource", StringArgumentType.word())
                        .executes(ctx -> gatherResource(ctx, 8))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> gatherResource(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("craft")
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(ctx -> craftItem(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> craftItem(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("say")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(BotCommandAction::sayMessage)))
            .then(Commands.literal("give")
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(ctx -> giveItem(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> giveItem(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
            .then(Commands.literal("equip")
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(BotCommandAction::equipItem)));
    }

    private static int gotoVec3(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        int x = (int)Math.round(pos.x), y = (int)Math.round(pos.y), z = (int)Math.round(pos.z);
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createGotoTask(x, y, z);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.goto.success", x, y, z), true);
            }
        }
        return 1;
    }

    private static int mineBlocks(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String block = StringArgumentType.getString(context, "block");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
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
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createVeinTask(block, count);
                bot.assignDirectTask(task, player);
                String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                source.sendSuccess(() -> Component.literal("Vein mining " + count + "x " + fullBlock + " (connected blocks)"), true);
            }
        }
        return 1;
    }

    private static int followPlayer(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String target = StringArgumentType.getString(context, "player");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
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
        var manager = BotCommand.getManager();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = manager.getByName(botName);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", botName)); return 0; }
        Task task = DirectCommandHandler.createFollowTask(target);
        bot.assignDirectTask(task, source.getPlayer());
        source.sendSuccess(() -> Component.translatable("commands.ai_bot.follow.success", target), true);
        return 1;
    }

    private static int gatherResource(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String resource = StringArgumentType.getString(context, "resource");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createGatherTask(resource, count);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.gather.success", count, resource), true);
            }
        }
        return 1;
    }

    private static int craftItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String item = StringArgumentType.getString(context, "item");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createCraftTask(item, count);
                bot.assignDirectTask(task, player);
                String fullItem = item.contains(":") ? item : "minecraft:" + item;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.craft.success", count, fullItem), true);
            }
        }
        return 1;
    }

    private static int sayMessage(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createSayTask(message);
                bot.assignDirectTask(task, player);
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.say.success", message), true);
            }
        }
        return 1;
    }

    private static int giveItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        String item = StringArgumentType.getString(context, "item");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createGiveTask(item, count, player.getName().getString());
                bot.assignDirectTask(task, player);
                String fullItem = item.contains(":") ? item : "minecraft:" + item;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.give.success", count, fullItem), true);
            }
        }
        return 1;
    }

    private static int equipItem(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String item = StringArgumentType.getString(context, "item");
        if (source.getEntity() instanceof Player player) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
            if (bot != null) {
                Task task = DirectCommandHandler.createEquipTask(item);
                bot.assignDirectTask(task, player);
                String fullItem = item.contains(":") ? item : "minecraft:" + item;
                source.sendSuccess(() -> Component.translatable("commands.ai_bot.equip.success", fullItem), true);
            }
        }
        return 1;
    }
}
