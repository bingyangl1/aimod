package com.aimod.command;

import com.aimod.ai.WaypointManager;
import com.aimod.fakeplayer.FakePlayer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Waypoint commands: /ai_bot waypoint save/list/goto/delete.
 */
public class BotCommandWaypoint implements SubCommand {

    private static WaypointManager waypointManager;

    public static void init(WaypointManager manager) {
        waypointManager = manager;
    }

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        var waypoint = Commands.literal("waypoint");

        // /ai_bot waypoint save <name> [category]
        waypoint.then(Commands.literal("save")
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> saveWaypoint(ctx, "general"))
                        .then(Commands.argument("category", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> saveWaypoint(ctx,
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "category"))))));

        // /ai_bot waypoint list [category]
        waypoint.then(Commands.literal("list")
                .executes(ctx -> listWaypoints(ctx, null))
                .then(Commands.argument("category", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> listWaypoints(ctx,
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "category")))));

        // /ai_bot waypoint goto <name>
        waypoint.then(Commands.literal("goto")
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            if (waypointManager != null) {
                                for (var wp : waypointManager.getAll()) {
                                    builder.suggest(wp.name());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(this::gotoWaypoint)));

        // /ai_bot waypoint delete <name>
        waypoint.then(Commands.literal("delete")
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            if (waypointManager != null) {
                                for (var wp : waypointManager.getAll()) {
                                    builder.suggest(wp.name());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(this::deleteWaypoint)));

        root.then(waypoint);
    }

    private static int saveWaypoint(CommandContext<CommandSourceStack> ctx, String category) {
        if (waypointManager == null) {
            ctx.getSource().sendFailure(Component.literal("Waypoint system not initialized"));
            return 0;
        }
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
        var player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        BlockPos pos = player.blockPosition();
        waypointManager.save(name, pos, category);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Waypoint '%s' saved at (%d, %d, %d) [%s]",
                        name, pos.getX(), pos.getY(), pos.getZ(), category)), true);
        return 1;
    }

    private static int listWaypoints(CommandContext<CommandSourceStack> ctx, String category) {
        if (waypointManager == null) {
            ctx.getSource().sendFailure(Component.literal("Waypoint system not initialized"));
            return 0;
        }

        var waypoints = category != null ? waypointManager.getByCategory(category) : new java.util.ArrayList<>(waypointManager.getAll());
        if (waypoints.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No waypoints found"), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("=== Waypoints ===\n");
        for (var wp : waypoints) {
            sb.append(String.format("  %s: (%d, %d, %d) [%s]\n",
                    wp.name(), wp.pos().getX(), wp.pos().getY(), wp.pos().getZ(), wp.category()));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private int gotoWaypoint(CommandContext<CommandSourceStack> ctx) {
        if (waypointManager == null) {
            ctx.getSource().sendFailure(Component.literal("Waypoint system not initialized"));
            return 0;
        }
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
        var wp = waypointManager.get(name);
        if (wp == null) {
            ctx.getSource().sendFailure(Component.literal("Waypoint not found: " + name));
            return 0;
        }

        FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
        if (bot == null) return 0;

        String command = String.format("goto %d %d %d", wp.pos().getX(), wp.pos().getY(), wp.pos().getZ());
        bot.assignTask(command, ctx.getSource().getPlayer());
        ctx.getSource().sendSuccess(() -> Component.literal("Navigating to waypoint: " + wp.name()), true);
        return 1;
    }

    private int deleteWaypoint(CommandContext<CommandSourceStack> ctx) {
        if (waypointManager == null) {
            ctx.getSource().sendFailure(Component.literal("Waypoint system not initialized"));
            return 0;
        }
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
        if (waypointManager.delete(name)) {
            ctx.getSource().sendSuccess(() -> Component.literal("Waypoint deleted: " + name), true);
        } else {
            ctx.getSource().sendFailure(Component.literal("Waypoint not found: " + name));
        }
        return 1;
    }
}
