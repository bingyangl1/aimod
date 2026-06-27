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
 * Supports bilingual (Chinese + English) help text.
 */
public class BotCommandHelp implements SubCommand {

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("help")
                .executes(BotCommandHelp::showHelp)
                .then(Commands.argument("command", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(getAllCommands(), b))
                        .executes(BotCommandHelp::showHelpForCommand)));
    }

    private static String[] getAllCommands() {
        return new String[]{
            "task", "task_all", "stop", "pause", "resume", "status",
            "goto", "mine", "vein", "follow", "gather", "craft", "say", "give", "equip",
            "attack", "build", "explore", "farm", "sneak", "use", "drop", "look",
            "spawn", "select", "remove", "save", "load", "list", "delete", "inventory",
            "toggle", "config", "veinmine", "showpath", "metrics",
            "waypoint", "ore", "cache", "agent", "chain", "session", "scan", "memory",
            "test", "replay", "help"
        };
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal(
            "§6=== AI Bot 命令帮助 / AI Bot Command Help ===\n" +
            "\n§e--- 任务控制 / Task Control ---\n" +
            "§f/ai_bot task <command>       §7- 分配任务 / Assign task\n" +
            "§f/ai_bot stop [name]          §7- 停止任务 / Stop task\n" +
            "§f/ai_bot pause [name]         §7- 暂停任务 / Pause task\n" +
            "§f/ai_bot resume [name]        §7- 恢复任务 / Resume task\n" +
            "§f/ai_bot status [name]        §7- 查看状态 / Show status\n" +
            "\n§e--- 行动命令 / Action Commands ---\n" +
            "§f/ai_bot goto <x> <y> <z>     §7- 移动到坐标 / Move to position\n" +
            "§f/ai_bot mine <block> [n]     §7- 挖掘方块 / Mine blocks\n" +
            "§f/ai_bot vein <block> [n]     §7- 连锁挖掘 / Vein mine\n" +
            "§f/ai_bot gather <type> [n]    §7- 收集资源 / Gather resources\n" +
            "§f/ai_bot craft <item> [n]     §7- 合成物品 / Craft items\n" +
            "§f/ai_bot follow <player>      §7- 跟随玩家 / Follow player\n" +
            "§f/ai_bot give <item> [n]      §7- 给予物品 / Give items\n" +
            "§f/ai_bot equip <item>         §7- 装备物品 / Equip item\n" +
            "§f/ai_bot attack <target>      §7- 攻击目标 / Attack target\n" +
            "§f/ai_bot build <file>         §7- 建造结构 / Build structure\n" +
            "§f/ai_bot explore [n]          §7- 探索世界 / Explore world\n" +
            "§f/ai_bot farm [n]             §7- 自动种田 / Auto farm\n" +
            "§f/ai_bot sneak                §7- 潜行 / Toggle sneak\n" +
            "§f/ai_bot say <message>        §7- 说话 / Chat message\n" +
            "\n§e--- 路径点 / Waypoints ---\n" +
            "§f/ai_bot waypoint save <name> §7- 保存路径点 / Save waypoint\n" +
            "§f/ai_bot waypoint list        §7- 列出路径点 / List waypoints\n" +
            "§f/ai_bot waypoint goto <name> §7- 前往路径点 / Go to waypoint\n" +
            "§f/ai_bot waypoint delete <n>  §7- 删除路径点 / Delete waypoint\n" +
            "\n§e--- 调试命令 / Debug Commands ---\n" +
            "§f/ai_bot ore stats            §7- 矿物索引统计 / Ore index stats\n" +
            "§f/ai_bot cache stats          §7- 计划缓存统计 / Plan cache stats\n" +
            "§f/ai_bot agent status         §7- Agent 循环状态 / Agent loop status\n" +
            "§f/ai_bot chain status         §7- 行为链状态 / Chain status\n" +
            "§f/ai_bot session list         §7- 列出会话 / List sessions\n" +
            "§f/ai_bot scan [radius]        §7- 扫描环境 / Scan environment\n" +
            "§f/ai_bot memory stats         §7- 内存统计 / Memory stats\n" +
            "§f/ai_bot metrics [name]       §7- 性能指标 / Performance metrics\n" +
            "\n§e--- 管理命令 / Admin Commands ---\n" +
            "§f/ai_bot spawn [name]         §7- 生成 bot / Spawn bot\n" +
            "§f/ai_bot remove <name>        §7- 移除 bot / Remove bot\n" +
            "§f/ai_bot inventory [name]     §7- 查看背包 / View inventory\n" +
            "§f/ai_bot toggle <feature>     §7- 切换功能 / Toggle feature\n" +
            "§f/ai_bot config <key> [val]   §7- 配置管理 / Config management\n" +
            "§f/ai_bot test                 §7- 运行测试 / Run tests\n" +
            "§f/ai_bot replay <session>     §7- 重放会话 / Replay session\n" +
            "\n§7输入 /ai_bot help <命令> 查看详细帮助\n" +
            "§7Type /ai_bot help <command> for detailed help"
        ), false);
        return 1;
    }

    private static int showHelpForCommand(CommandContext<CommandSourceStack> ctx) {
        String cmd = StringArgumentType.getString(ctx, "command");
        String detail = getCommandDetail(cmd);
        if (detail == null) {
            ctx.getSource().sendFailure(Component.literal("§c未知命令 / Unknown command: " + cmd));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§6=== " + cmd + " ===\n" + detail), false);
        return 1;
    }

    private static String getCommandDetail(String cmd) {
        return switch (cmd) {
            case "task" -> "§f/ai_bot task <command>\n§7分配自然语言任务给 bot / Assign natural language task\n§7示例: /ai_bot task 制作一套钻石装备";
            case "mine" -> "§f/ai_bot mine <block> [count]\n§7挖掘指定方块 / Mine specific blocks\n§7支持自动挖到矿层 / Auto-dig to ore level\n§7示例: /ai_bot mine diamond_ore 24";
            case "goto" -> "§f/ai_bot goto <x> <y> <z>\n§7移动到指定坐标 / Move to coordinates\n§7支持自动挖洞和搭桥 / Auto-dig and bridge\n§7示例: /ai_bot goto 100 -59 200";
            case "waypoint" -> "§f/ai_bot waypoint save <name> [category]\n§7保存当前位置 / Save current position\n§f/ai_bot waypoint list [category]\n§7列出路径点 / List waypoints\n§f/ai_bot waypoint goto <name>\n§7前往路径点 / Go to waypoint";
            case "ore" -> "§f/ai_bot ore stats\n§7显示矿物索引统计 / Show ore index stats\n§f/ai_bot ore clear\n§7清除矿物索引 / Clear ore index";
            case "cache" -> "§f/ai_bot cache stats\n§7显示计划缓存统计 / Show plan cache stats\n§f/ai_bot cache clear\n§7清除计划缓存 / Clear plan cache";
            case "agent" -> "§f/ai_bot agent status\n§7显示 Agent 循环状态 / Show agent loop status\n§f/ai_bot agent cancel\n§7取消 Agent 循环 / Cancel agent loop";
            case "chain" -> "§f/ai_bot chain status\n§7显示当前活跃行为链 / Show active behavior chain\n§f/ai_bot chain list\n§7列出所有行为链 / List all chains";
            case "session" -> "§f/ai_bot session list\n§7列出所有会话 / List all sessions\n§f/ai_bot session info <id>\n§7查看会话详情 / View session info\n§f/ai_bot session delete <id>\n§7删除会话 / Delete session";
            case "scan" -> "§f/ai_bot scan [radius]\n§7扫描周围环境 / Scan environment\n§f/ai_bot scan ores\n§7扫描附近矿石 / Scan nearby ores";
            case "farm" -> "§f/ai_bot farm [count]\n§7自动收割种植作物 / Auto harvest and replant\n§7支持: 小麦/胡萝卜/土豆/甜菜/西瓜/南瓜/甘蔗/仙人掌/地狱疣";
            case "build" -> "§f/ai_bot build <blueprint>\n§7从蓝图文件建造 / Build from blueprint file\n§7蓝图格式: JSON 文件包含方块坐标和类型";
            case "explore" -> "§f/ai_bot explore [waypoints]\n§7自动探索世界 / Auto explore world\n§7使用螺旋模式向外探索 / Spiral pattern exploration";
            case "toggle" -> "§f/ai_bot toggle <feature>\n§7切换功能开关 / Toggle feature\n§7可用: autoFish, autoReplenish, autoReplace, veinMine, pvpDefense, showTaskAboveHead, agenticMode";
            case "config" -> "§f/ai_bot config [list | key [value]]\n§7查看/设置配置 / Get/set config\n§7示例: /ai_bot config movementSpeed 0.5";
            default -> null;
        };
    }
}
