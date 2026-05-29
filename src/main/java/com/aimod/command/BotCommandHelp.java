package com.aimod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * Help command: /ai_bot help [command].
 */
public class BotCommandHelp implements SubCommand {

    private static final String[] HELP_COMMANDS = {
        "spawn", "select", "status", "task", "task_all", "stop", "cancel",
        "pause", "resume", "remove", "goto", "mine", "gather", "craft",
        "follow", "follow_bot", "give", "equip", "say", "inventory",
        "toggle", "save", "load", "list", "delete"
    };

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("help")
                .executes(BotCommandHelp::showHelp)
                .then(Commands.argument("command", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(HELP_COMMANDS, b))
                        .executes(BotCommandHelp::showHelpForCommand)));
    }

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
        if (detail.equals(key)) {
            ctx.getSource().sendFailure(Component.translatable("commands.ai_bot.help.unknown", cmd));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            Component.translatable("commands.ai_bot.help.detail_title", cmd).getString() + "\n" + detail), false);
        return 1;
    }
}
