package com.aimod.command;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Admin commands: spawn, select, remove, save, load, list, delete, inventory.
 */
public class BotCommandAdmin implements SubCommand {

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("spawn")
                .executes(BotCommandAdmin::spawnBot)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommandAdmin::spawnBotNamed)))
            .then(Commands.literal("select")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandAdmin::selectBot)))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommandAdmin::removeBot)))
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("desc", StringArgumentType.greedyString())
                                .executes(BotCommandAdmin::saveBot))
                        .executes(BotCommandAdmin::saveBot)))
            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommandAdmin::loadBot)))
            .then(Commands.literal("list")
                .executes(BotCommandAdmin::listBots))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommandAdmin::deleteBot)))
            .then(Commands.literal("inventory")
                .executes(BotCommandAdmin::openInventory)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandAdmin::openInventoryNamed)));
    }

    private static int spawnBot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() instanceof Player player) {
            var manager = BotCommand.getManager();
            if (manager == null) {
                source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized"));
                return 1;
            }
            FakePlayer bot = BotCommand.spawnBotNearPlayer(source, player, null);
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
            var manager = BotCommand.getManager();
            if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 1; }
            if (manager.getByName(name) != null) {
                source.sendFailure(Component.literal("Bot '" + name + "' already exists"));
                return 1;
            }
            FakePlayer bot = BotCommand.spawnBotNearPlayer(source, player, name);
            if (bot != null) source.sendSuccess(() -> Component.translatable("commands.ai_bot.spawn.success", bot.getName().getString()), true);
            else source.sendFailure(Component.translatable("commands.ai_bot.spawn.failure"));
        }
        return 1;
    }

    private static int selectBot(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        var manager = BotCommand.getManager();
        if (manager != null && manager.getByName(name) != null) {
            BotCommand.setSelectedBotName(name);
            ctx.getSource().sendSuccess(() -> Component.literal("Selected bot: " + name), true);
        } else {
            ctx.getSource().sendFailure(Component.translatable("commands.ai_bot.no_bot_named", name));
        }
        return 1;
    }

    private static int removeBot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        var manager = BotCommand.getManager();
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

    private static int saveBot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        String desc = null;
        try { desc = StringArgumentType.getString(context, "desc"); } catch (IllegalArgumentException ignored) {}
        var manager = BotCommand.getManager();

        if (manager == null) {
            source.sendFailure(Component.literal("Not initialized"));
            return 0;
        }

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
        var manager = BotCommand.getManager();

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
        var manager = BotCommand.getManager();

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
        var manager = BotCommand.getManager();

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

    private static int openInventory(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() instanceof Player player && player instanceof ServerPlayer sp) {
            FakePlayer bot = BotCommand.findOrSpawnBot(source, player);
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
            var manager = BotCommand.getManager();
            if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
            FakePlayer bot = manager.getByName(name);
            if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot_named", name)); return 0; }
            com.aimod.client.BotStatusScreen.open(sp, bot);
            source.sendSuccess(() -> Component.translatable("commands.ai_bot.inventory.opened", bot.getName().getString()), true);
            return 1;
        }
        return 0;
    }
}
