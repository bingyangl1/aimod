package com.aimod.command;

import com.aimod.ai.OreIndex;
import com.aimod.ai.OreIndexHolder;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Debug/inspection commands: ore, cache, agent, chain, session, scan, memory.
 * Supports Chinese and English output.
 */
public class BotCommandDebug implements SubCommand {

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        registerOreCommands(root);
        registerCacheCommands(root);
        registerAgentCommands(root);
        registerChainCommands(root);
        registerSessionCommands(root);
        registerScanCommands(root);
        registerMemoryCommands(root);
    }

    // ========== Ore Index Commands ==========
    private void registerOreCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var ore = Commands.literal("ore");

        ore.then(Commands.literal("stats").executes(ctx -> {
            OreIndex idx = OreIndexHolder.getForLevel(ctx.getSource().getLevel());
            if (idx == null) {
                msg(ctx, "§cOre index not initialized");
                return 0;
            }
            msg(ctx, String.format("§6=== Ore Index ===\n§fScanned chunks: §a%d\n§fTotal ores: §a%d",
                    idx.getScannedChunkCount(), idx.getTotalOreCount()));
            return 1;
        }));

        ore.then(Commands.literal("clear").executes(ctx -> {
            OreIndex idx = OreIndexHolder.getForLevel(ctx.getSource().getLevel());
            if (idx == null) { msg(ctx, "§cOre index not initialized"); return 0; }
            int count = idx.getTotalOreCount();
            idx.clear();
            msg(ctx, "§aOre index cleared (" + count + " entries removed)");
            return 1;
        }));

        root.then(ore);
    }

    // ========== Plan Cache Commands ==========
    private void registerCacheCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var cache = Commands.literal("cache");

        cache.then(Commands.literal("stats").executes(ctx -> {
            // PlanCache is per-bot, show file info
            java.nio.file.Path cacheFile = java.nio.file.Path.of("config", "aimod", "plan_cache.json");
            if (java.nio.file.Files.exists(cacheFile)) {
                try {
                    String content = java.nio.file.Files.readString(cacheFile);
                    int count = content.split("\"command\"").length - 1;
                    msg(ctx, String.format("§6=== Plan Cache ===\n§fCached plans: §a%d\n§fFile: §7%s", count, cacheFile));
                } catch (Exception e) {
                    msg(ctx, "§cFailed to read cache: " + e.getMessage());
                }
            } else {
                msg(ctx, "§7No plan cache file found");
            }
            return 1;
        }));

        cache.then(Commands.literal("clear").executes(ctx -> {
            java.nio.file.Path cacheFile = java.nio.file.Path.of("config", "aimod", "plan_cache.json");
            if (java.nio.file.Files.exists(cacheFile)) {
                try {
                    java.nio.file.Files.delete(cacheFile);
                    msg(ctx, "§aPlan cache file deleted");
                } catch (Exception e) {
                    msg(ctx, "§cFailed to delete cache: " + e.getMessage());
                }
            } else {
                msg(ctx, "§7No plan cache file to delete");
            }
            return 1;
        }));

        root.then(cache);
    }

    // ========== Agent Loop Commands ==========
    private void registerAgentCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var agent = Commands.literal("agent");

        agent.then(Commands.literal("status").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            var runner = bot.getAgenticRunner();
            if (runner == null || !runner.isRunning()) {
                msg(ctx, "§7No active agent loop");
                return 1;
            }
            var goal = runner.getGoal();
            msg(ctx, String.format("§6=== Agent Status ===\n§fGoal: §a%s\n§fStatus: §a%s\n§fSteps: §a%d",
                    goal.getOriginalCommand(), goal.getStatus(), runner.getSessionLog().getSteps().size()));
            return 1;
        }));

        agent.then(Commands.literal("cancel").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            var runner = bot.getAgenticRunner();
            if (runner == null || !runner.isRunning()) {
                msg(ctx, "§7No active agent loop to cancel");
                return 1;
            }
            runner.cancel();
            msg(ctx, "§aAgent loop cancelled");
            return 1;
        }));

        root.then(agent);
    }

    // ========== Chain Manager Commands ==========
    private void registerChainCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var chain = Commands.literal("chain");

        chain.then(Commands.literal("status").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            var cm = bot.getChainManager();
            var active = cm.getActiveChain();
            if (active != null && active.isActive()) {
                msg(ctx, String.format("§6=== Chain Status ===\n§fActive: §a%s §f(priority: §a%d§f)", active.name(), active.priority()));
            } else {
                msg(ctx, "§7No active behavior chain");
            }
            return 1;
        }));

        chain.then(Commands.literal("list").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            var cm = bot.getChainManager();
            StringBuilder sb = new StringBuilder("§6=== Behavior Chains ===\n");
            // List all registered chains (we need to expose this from ChainManager)
            sb.append("§fDangerChain: §aP90 §7(fire/lava/drowning/fall)\n");
            sb.append("§fDefenseChain: §aP70 §7(hostile mobs)\n");
            sb.append("§fFoodChain: §aP55 §7(auto-eating)\n");
            sb.append("§fUnstuckChain: §aP50 §7(stuck recovery)\n");
            sb.append("§fPlayerDefenseChain: §aP65 §7(PvP, disabled by default)\n");
            msg(ctx, sb.toString());
            return 1;
        }));

        root.then(chain);
    }

    // ========== Session Commands ==========
    private void registerSessionCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var session = Commands.literal("session");

        session.then(Commands.literal("list").executes(ctx -> {
            java.nio.file.Path sessionsDir = java.nio.file.Path.of("config", "aimod", "sessions");
            if (!Files.isDirectory(sessionsDir)) {
                msg(ctx, "§7No sessions directory found");
                return 1;
            }
            File[] dirs = sessionsDir.toFile().listFiles(File::isDirectory);
            if (dirs == null || dirs.length == 0) {
                msg(ctx, "§7No saved sessions");
                return 1;
            }
            StringBuilder sb = new StringBuilder("§6=== Saved Sessions ===\n");
            for (File dir : dirs) {
                java.nio.file.Path summary = dir.toPath().resolve("summary.json");
                if (java.nio.file.Files.exists(summary)) {
                    try {
                        String content = java.nio.file.Files.readString(summary);
                        String goal = extractJsonString(content, "goal");
                        String achieved = extractJsonString(content, "goalAchieved");
                        sb.append(String.format("§f%s §7- %s §f[%s]\n", dir.getName(), goal, achieved.equals("true") ? "§a✓" : "§c✗"));
                    } catch (Exception e) {
                        sb.append("§f").append(dir.getName()).append(" §7(corrupted)\n");
                    }
                }
            }
            msg(ctx, sb.toString());
            return 1;
        }));

        session.then(Commands.literal("delete")
                .then(Commands.argument("session_id", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            java.nio.file.Path dir = java.nio.file.Path.of("config", "aimod", "sessions");
                            if (java.nio.file.Files.isDirectory(dir)) {
                                File[] dirs = dir.toFile().listFiles(File::isDirectory);
                                if (dirs != null) {
                                    for (File d : dirs) builder.suggest(d.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "session_id");
                            java.nio.file.Path sessionDir = java.nio.file.Path.of("config", "aimod", "sessions", id);
                            if (!java.nio.file.Files.isDirectory(sessionDir)) {
                                msg(ctx, "§cSession not found: " + id);
                                return 0;
                            }
                            deleteRecursive(sessionDir.toFile());
                            msg(ctx, "§aSession deleted: " + id);
                            return 1;
                        })));

        session.then(Commands.literal("info")
                .then(Commands.argument("session_id", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "session_id");
                            java.nio.file.Path summary = java.nio.file.Path.of("config", "aimod", "sessions", id, "summary.json");
                            if (!java.nio.file.Files.exists(summary)) {
                        msg(ctx, "§cSession not found: " + id);
                        return 0;
                    }
                    try {
                        String content = Files.readString(summary);
                        msg(ctx, "§6=== Session Info ===\n§f" + content.replace("\n", "\n§f"));
                    } catch (Exception e) {
                        msg(ctx, "§cFailed to read session: " + e.getMessage());
                    }
                    return 1;
                })));

        root.then(session);
    }

    // ========== Scan Commands ==========
    private void registerScanCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var scan = Commands.literal("scan");

        scan.executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            var scanner = new com.aimod.ai.WorldScanner(bot);
            String result = scanner.scanEnvironment(16);
            msg(ctx, "§6=== Environment Scan ===\n§f" + result.replace("\n", "\n§f"));
            return 1;
        });

        scan.then(Commands.literal("ores").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            var scanner = new com.aimod.ai.WorldScanner(bot);
            var results = scanner.findNearbyBlocks("minecraft:diamond_ore", 32);
            StringBuilder sb = new StringBuilder("§6=== Nearby Diamond Ore ===\n");
            if (results.isEmpty()) {
                sb.append("§7None found within 32 blocks");
            } else {
                for (var pos : results) {
                    double dist = Math.sqrt(bot.distanceToSqr(pos.getX()+0.5, pos.getY(), pos.getZ()+0.5));
                    sb.append(String.format("§f(%d, %d, %d) §7- %.1fm away\n", pos.getX(), pos.getY(), pos.getZ(), dist));
                }
            }
            msg(ctx, sb.toString());
            return 1;
        }));

        root.then(scan);
    }

    // ========== Memory Commands ==========
    private void registerMemoryCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var memory = Commands.literal("memory");

        memory.then(Commands.literal("stats").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            msg(ctx, "§6=== Memory Stats ===\n§f" + bot.getMemoryStore().getStats());
            return 1;
        }));

        memory.then(Commands.literal("clear").executes(ctx -> {
            FakePlayer bot = BotCommand.findOrSpawnBot(ctx.getSource(), ctx.getSource().getPlayer());
            if (bot == null) return 0;
            bot.getMemoryStore().clear();
            msg(ctx, "§aMemory cleared for " + bot.getName().getString());
            return 1;
        }));

        root.then(memory);
    }

    // ========== Utilities ==========
    private static void msg(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static String extractJsonString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return "";
        int start = json.indexOf("\"", idx + key.length() + 2);
        int end = json.indexOf("\"", start + 1);
        return start >= 0 && end > start ? json.substring(start + 1, end) : "";
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
