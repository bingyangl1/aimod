package com.aimod.command;

import com.aimod.fakeplayer.FakePlayer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Config and feature commands: toggle, config, veinmine, showpath.
 */
public class BotCommandConfig implements SubCommand {

    private static final List<String> CONFIG_KEYS = List.of(
            "scanRadius", "hungerThreshold", "movementSpeed",
            "veinMine", "autoReplenish", "autoReplaceTool", "autoFish", "maxBots",
            "showTaskAboveHead", "cheapModelName"
    );

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("toggle")
                .then(Commands.argument("feature", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                new String[]{"autoFish", "autoReplenish", "autoReplace", "veinMine"}, b))
                        .executes(BotCommandConfig::toggleFeature)))
            .then(Commands.literal("config")
                .executes(BotCommandConfig::configList)
                .then(Commands.literal("list").executes(BotCommandConfig::configList))
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests(BotCommandConfig::suggestConfigKeys)
                        .executes(BotCommandConfig::configGet)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(BotCommandConfig::configSet))))
            .then(Commands.literal("veinmine")
                .then(Commands.literal("on").executes(BotCommandConfig::veinMineOn))
                .then(Commands.literal("off").executes(BotCommandConfig::veinMineOff))
                .then(Commands.literal("status").executes(BotCommandConfig::veinMineStatus))
                .then(Commands.literal("history").executes(BotCommandConfig::veinMineHistory))
                .then(Commands.literal("undo")
                        .executes(ctx -> veinMineUndo(ctx, 1))
                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> veinMineUndo(ctx, IntegerArgumentType.getInteger(ctx, "steps"))))))
            .then(Commands.literal("showpath")
                .executes(BotCommandConfig::showPath)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandConfig::showPathNamed)))
            .then(Commands.literal("metrics")
                .executes(BotCommandConfig::showMetrics)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BotCommand.suggestBots())
                        .executes(BotCommandConfig::showMetricsNamed)));
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

    private static CompletableFuture<Suggestions> suggestConfigKeys(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();
        for (String key : CONFIG_KEYS) {
            if (key.toLowerCase().startsWith(input)) builder.suggest(key);
        }
        return builder.buildFuture();
    }

    private static int configList(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        StringBuilder sb = new StringBuilder("§e=== AI Bot Config ===§r\n");
        sb.append("scanRadius: ").append(com.aimod.config.ModConfig.getDefaultScanRadius()).append(" (8-128)\n");
        sb.append("hungerThreshold: ").append(com.aimod.config.ModConfig.getHungerThreshold()).append(" (0-20)\n");
        sb.append("movementSpeed: ").append(com.aimod.config.ModConfig.getMovementSpeed()).append(" (0.1-1.0)\n");
        sb.append("veinMine: ").append(com.aimod.config.ModConfig.getVeinMine()).append("\n");
        sb.append("autoReplenish: ").append(com.aimod.config.ModConfig.getAutoReplenish()).append("\n");
        sb.append("autoReplaceTool: ").append(com.aimod.config.ModConfig.getAutoReplaceTool()).append("\n");
        sb.append("autoFish: ").append(com.aimod.config.ModConfig.getAutoFish()).append("\n");
        sb.append("maxBots: ").append(com.aimod.config.ModConfig.getMaxBots()).append(" (1-50)\n");
        sb.append("showTaskAboveHead: ").append(com.aimod.config.ModConfig.getShowTaskAboveHead()).append("\n");
        sb.append("cheapModelName: ").append(com.aimod.config.ModConfig.getCheapModelName()).append(" (replan model)\n");
        sb.append("\n§7用法: /ai_bot config <key> [value]§r");
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int configGet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String val = getConfigValue(key);
        if (val == null) {
            ctx.getSource().sendFailure(Component.literal("未知配置项: " + key + "。可用: " + String.join(", ", CONFIG_KEYS)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(key + " = " + val), false);
        return 1;
    }

    private static int configSet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String valStr = StringArgumentType.getString(ctx, "value");
        try {
            boolean ok = setConfigValue(key, valStr);
            if (!ok) {
                ctx.getSource().sendFailure(Component.literal("未知配置项: " + key));
                return 0;
            }
            String newVal = getConfigValue(key);
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + key + " 已更新为 " + newVal + "§r"), true);
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("无效值: " + valStr + " (需要数字)"));
            return 0;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("设置失败: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    @javax.annotation.Nullable
    private static String getConfigValue(String key) {
        return switch (key) {
            case "scanRadius" -> String.valueOf(com.aimod.config.ModConfig.getDefaultScanRadius());
            case "hungerThreshold" -> String.valueOf(com.aimod.config.ModConfig.getHungerThreshold());
            case "movementSpeed" -> String.valueOf(com.aimod.config.ModConfig.getMovementSpeed());
            case "veinMine" -> String.valueOf(com.aimod.config.ModConfig.getVeinMine());
            case "autoReplenish" -> String.valueOf(com.aimod.config.ModConfig.getAutoReplenish());
            case "autoReplaceTool" -> String.valueOf(com.aimod.config.ModConfig.getAutoReplaceTool());
            case "autoFish" -> String.valueOf(com.aimod.config.ModConfig.getAutoFish());
            case "maxBots" -> String.valueOf(com.aimod.config.ModConfig.getMaxBots());
            case "showTaskAboveHead" -> String.valueOf(com.aimod.config.ModConfig.getShowTaskAboveHead());
            case "cheapModelName" -> com.aimod.config.ModConfig.getCheapModelName();
            default -> null;
        };
    }

    private static boolean setConfigValue(String key, String valStr) {
        return switch (key) {
            case "scanRadius" -> { com.aimod.config.ModConfig.setDefaultScanRadius(Integer.parseInt(valStr)); yield true; }
            case "hungerThreshold" -> { com.aimod.config.ModConfig.setHungerThreshold(Integer.parseInt(valStr)); yield true; }
            case "movementSpeed" -> { com.aimod.config.ModConfig.setMovementSpeed(Double.parseDouble(valStr)); yield true; }
            case "veinMine" -> { com.aimod.config.ModConfig.setVeinMine(Boolean.parseBoolean(valStr)); yield true; }
            case "autoReplenish" -> { com.aimod.config.ModConfig.setAutoReplenish(Boolean.parseBoolean(valStr)); yield true; }
            case "autoReplaceTool" -> { com.aimod.config.ModConfig.setAutoReplaceTool(Boolean.parseBoolean(valStr)); yield true; }
            case "autoFish" -> { com.aimod.config.ModConfig.setAutoFish(Boolean.parseBoolean(valStr)); yield true; }
            case "maxBots" -> { com.aimod.config.ModConfig.setMaxBots(Integer.parseInt(valStr)); yield true; }
            case "showTaskAboveHead" -> { com.aimod.config.ModConfig.setShowTaskAboveHead(Boolean.parseBoolean(valStr)); yield true; }
            case "cheapModelName" -> { com.aimod.config.ModConfig.setCheapModelName(valStr); yield true; }
            default -> false;
        };
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
        var manager = BotCommand.getManager();
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
        var manager = BotCommand.getManager();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = source.getEntity() instanceof Player player
                ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null;
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        var level = (ServerLevel) bot.level();
        int restored = bot.getUndoManager().undo(level, steps);
        source.sendSuccess(() -> Component.literal("Undone " + restored + " blocks in " + steps + " operation(s)"), true);
        return 1;
    }

    // ========== Metrics ==========

    private static int showMetrics(CommandContext<CommandSourceStack> ctx) {
        return showMetricsInternal(ctx, null);
    }

    private static int showMetricsNamed(CommandContext<CommandSourceStack> ctx) {
        return showMetricsInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int showMetricsInternal(CommandContext<CommandSourceStack> context, @javax.annotation.Nullable String name) {
        CommandSourceStack source = context.getSource();
        var manager = BotCommand.getManager();
        if (manager == null) { source.sendFailure(Component.translatable("commands.ai_bot.task.not_initialized")); return 0; }
        FakePlayer bot = name != null ? manager.getByName(name)
                : (source.getEntity() instanceof Player player ? manager.getNearest(player.getX(), player.getY(), player.getZ(), 32.0) : null);
        if (bot == null) { source.sendFailure(Component.translatable("commands.ai_bot.no_bot")); return 0; }
        String metrics = bot.getAiManager().getMetrics().format();
        source.sendSuccess(() -> Component.literal(metrics), false);
        return 1;
    }

    // ========== Path visualization ==========

    private static int showPath(CommandContext<CommandSourceStack> ctx) {
        return showPathInternal(ctx, null);
    }

    private static int showPathNamed(CommandContext<CommandSourceStack> ctx) {
        return showPathInternal(ctx, StringArgumentType.getString(ctx, "name"));
    }

    private static int showPathInternal(CommandContext<CommandSourceStack> context, @javax.annotation.Nullable String name) {
        CommandSourceStack source = context.getSource();
        var manager = BotCommand.getManager();
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
            for (var pos : path) {
                level.sendParticles(
                    new net.minecraft.core.particles.DustParticleOptions(
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
}
