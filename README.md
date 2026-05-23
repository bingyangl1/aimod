# AI Mod — Minecraft 1.21.1 智能机器人模组

一个基于 NeoForge 的 Minecraft 模组，通过 LLM（大语言模型）API 实现自然语言控制的 AI 机器人。机器人以 FakePlayer（继承 ServerPlayer）形式直接注册到服务器，拥有完整的玩家能力。

## 功能特性

- **自然语言命令**：通过 `/ai_bot task <命令>` 用普通语言告诉机器人做什么
- **LLM 集成**：支持 OpenAI 兼容 API（OpenAI、DeepSeek、Claude、Ollama、LM Studio）
- **14 种动作类型**：移动、挖矿、建造、合成、战斗、跟随、交互等
- **FakePlayer 系统**：机器人直接继承 ServerPlayer，拥有完整玩家能力
- **Baritone 风格 A* 寻路**：支持目标点、XZ 平面、Y 层级、组合目标
- **世界感知**：LLM 接收机器人位置、背包、附近方块和实体等上下文
- **任务反馈**：实时向分配任务的玩家报告执行状态
- **直接命令**：16 个子命令，支持绕过 LLM 直接执行任务
- **国际化**：支持中英文界面
- **可配置**：12 项配置选项，涵盖 API、超时和行为设置

## 快速开始

### 环境要求

- Java 21（JDK）
- Minecraft 1.21.1
- NeoForge 21.1.176

### 构建

```bash
# 标准构建会因 SSL 问题失败，必须使用本地初始化脚本：
./gradlew.bat build --init-script init-local.gradle
```

构建产物位于 `build/libs/` 目录。

### 安装

1. 安装 NeoForge 1.21.1
2. 将模组 JAR 文件放入 `mods/` 目录
3. 启动 Minecraft
4. 在 `config/aimod-common.toml` 中配置 API 设置

### 配置

首次启动后编辑 `config/aimod-common.toml`：

```toml
apiUrl = "https://api.deepseek.com/chat/completions"
apiKey = "sk-你的API密钥"
modelName = "deepseek-v4-pro"
maxTokens = 1024
temperature = 0.7
```

对于本地模型（Ollama/LM Studio），将 `apiUrl` 设置为本地端点，`apiKey` 留空即可。

## 使用方法

### 核心命令（LLM 驱动）

| 命令 | 说明 |
|------|------|
| `/ai_bot spawn` | 在附近生成一个 AI 机器人 |
| `/ai_bot task <命令>` | 分配自然语言任务（LLM 解析为动作序列） |
| `/ai_bot status` | 查看最近机器人的状态 |

### 控制命令（Baritone 风格）

| 命令 | 说明 |
|------|------|
| `/ai_bot stop` / `cancel` | 取消当前任务 |
| `/ai_bot pause` / `resume` | 暂停/恢复任务执行 |

### 直接命令（绕过 LLM，即时执行）

| 命令 | 说明 |
|------|------|
| `/ai_bot goto <x> <y> <z>` | 导航到指定坐标 |
| `/ai_bot mine <方块> [数量]` | 挖掘指定方块 |
| `/ai_bot follow <玩家>` | 跟随玩家 |
| `/ai_bot gather <类型> [数量]` | 采集资源（WOOD, STONE, DIRT, SAND） |
| `/ai_bot craft <物品> [数量]` | 制作物品 |
| `/ai_bot say <消息>` | 机器人说话 |
| `/ai_bot give <物品> [数量]` | 给你物品 |
| `/ai_bot equip <物品>` | 装备物品 |
| `/ai_bot help` | 显示所有命令帮助 |

### 使用示例

```
/ai_bot task 砍10棵橡木
/ai_bot task 建一个小木屋
/ai_bot task 挖5个铁矿石
/ai_bot task 跟着我
/ai_bot task 制作一把钻石剑
/ai_bot task 杀死附近所有僵尸
/ai_bot goto 100 64 200
/ai_bot mine diamond_ore 5
/ai_bot follow Steve
```

### 工作原理

1. 玩家通过 `/ai_bot task <命令>` 分配任务
2. 机器人将命令 + 世界上下文发送到配置的 LLM API
3. LLM 返回 JSON 动作计划
4. BotAIManager 将计划解析为 Action 对象
5. 动作按顺序执行（移动、挖矿、合成等）
6. TaskFeedback 向玩家报告进度

## 项目架构

```
src/main/java/com/example/aimod/
├── AIMod.java                    # @Mod 入口，注册命令和配置
├── ai/
│   ├── BotAIManager.java         # LLM 响应解析，任务执行引擎
│   ├── Task.java                 # 任务数据模型
│   ├── TaskFeedback.java         # 玩家通知系统
│   ├── WorldScanner.java         # 方块/实体/环境扫描
│   ├── InventoryUtils.java       # 背包操作工具类
│   ├── action/                   # 14 种动作实现
│   │   ├── Action.java           # 动作基类
│   │   ├── MoveToAction.java
│   │   ├── BreakBlockAction.java
│   │   ├── PlaceBlockAction.java
│   │   ├── AttackAction.java
│   │   ├── CraftAction.java
│   │   ├── FollowAction.java
│   │   ├── GiveItemAction.java
│   │   ├── RequireItemsAction.java
│   │   ├── SayAction.java
│   │   ├── WaitAction.java
│   │   ├── MineBlockAction.java
│   │   ├── GatherResourceAction.java
│   │   ├── InteractBlockAction.java
│   │   └── EquipItemAction.java
│   ├── pathing/                  # Baritone 风格 A* 寻路系统
│   │   ├── Pathfinder.java       # A* 核心算法
│   │   ├── PathNode.java         # 路径节点
│   │   ├── PathResult.java       # 路径结果
│   │   ├── PathExecutor.java     # 路径执行器
│   │   ├── BinaryHeapOpenSet.java # 二叉堆开放列表
│   │   ├── MovementHelper.java   # 移动辅助
│   │   ├── MoveCost.java         # 移动代价常量
│   │   ├── ToolSet.java          # 工具选择优化
│   │   └── goals/                # 目标系统
│   │       ├── Goal.java         # 目标接口
│   │       ├── GoalBlock.java    # 精确坐标目标
│   │       ├── GoalXZ.java       # XZ 平面目标
│   │       ├── GoalYLevel.java   # Y 层级目标
│   │       └── GoalComposite.java # 组合目标
│   └── llm/
│       ├── LLMService.java       # LLM HTTP 客户端（流式 + 健康检查）
│       ├── LLMResponse.java      # 响应数据模型
│       ├── LLMResponseParser.java # 响应解析器
│       └── ActionDescriptor.java  # 动作描述符
├── command/
│   ├── BotCommand.java           # Brigadier 命令注册（16 个子命令）
│   └── DirectCommandHandler.java # 直接命令处理（绕过 LLM）
├── config/
│   └── ModConfig.java            # 12 项配置选项
├── fakeplayer/
│   ├── FakePlayer.java           # 核心机器人（继承 ServerPlayer）
│   ├── FakePlayerManager.java    # 机器人生命周期管理
│   ├── FakePlayerNetHandler.java # 伪网络处理器
│   ├── FakeClientConnection.java # EmbeddedChannel 连接
│   ├── FakeConnection.java       # 兼容旧连接
│   └── FakeNetHandler.java       # 兼容旧网络处理器
├── client/
│   └── ClientModEvents.java      # 空实现（FakePlayer 渲染为原版玩家）
└── util/
    └── DevLog.java               # 结构化日志工具
```

### 测试文件

```
src/test/java/com/example/aimod/
├── ai/
│   ├── TaskTest.java             # 任务模型测试
│   ├── llm/
│   │   ├── LLMResponseTest.java  # 响应模型测试
│   │   └── LLMServiceParseTest.java # 解析逻辑测试
│   └── pathing/
│       ├── BinaryHeapOpenSetTest.java # 二叉堆测试
│       ├── MoveCostTest.java     # 移动代价测试
│       ├── PathNodeTest.java     # 路径节点测试
│       └── goals/
│           └── GoalsTest.java    # 目标系统测试
└── util/
    └── DevLogTest.java           # 日志工具测试
```

## 开发指南

### 添加新动作

1. 在 `ai/action/` 目录下创建继承 `Action` 的类：

```java
public class MyAction extends Action {
    public MyAction() {
        super("我的动作描述");
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        // 检查动作是否可以执行
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
        }
        // 执行动作逻辑
        if (done) {
            status = ActionStatus.COMPLETED;
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
```

2. 在 `BotAIManager.convertResponseToActions()` 的 switch 语句中注册新动作。

### 关键设计模式

- **状态机**：动作使用 `PENDING → IN_PROGRESS → COMPLETED/FAILED` 生命周期
- **FakePlayer 直接操作**：所有动作直接接收 FakePlayer 作为 `bot` 参数
- **异步 LLM 调用**：LLM 请求在守护线程中运行，不阻塞服务器 tick
- **世界上下文注入**：WorldScanner 为 LLM 提示词提供环境数据
- **A* 寻路**：Baritone 风格的路径规划，支持超时和最佳路径回退
- **直接命令**：DirectCommandHandler 绕过 LLM 直接创建 Task 对象

## 已知限制

- **移动系统**：当前使用 `setDeltaMovement()` 物理滑动，非真实玩家输入模拟
- **持久化**：机器人背包和任务不会在服务器重启后保存
- **性能**：WorldScanner 暴力扫描方块区域（O(n³)）
- **并发**：LLMService 健康检查缓存存在线程安全顾虑
- **HttpClient**：每次 LLM 调用创建新的 HttpClient 实例，未复用

## 开发路线图

详见 [PROGRESS.md](PROGRESS.md) 获取完整的 Baritone 1.21.1 对比分析和详细阶段计划。

| 阶段 | 优先级 | 重点 | 预计工时 |
|------|--------|------|---------|
| 阶段 1 | P0 | 移动精度（输入模拟） | ~6 天 |
| 阶段 2 | P1 | 高级移动类型（7 种） | ~10 天 |
| 阶段 3 | P1 | 世界缓存与挖矿智能 | ~6 天 |
| 阶段 4 | P2 | 异步寻路 | ~4 天 |
| 阶段 5 | P2 | 战斗 AI 增强 | ~6 天 |
| 阶段 6 | P2 | 任务系统增强 | ~5 天 |
| 阶段 7 | P3 | 命令系统增强 | ~6 天 |

**关键洞察**：移动精度（阶段 1）是基础 — 所有高级功能都依赖于从 `setDeltaMovement()` 升级为输入模拟。

**我们相比 Baritone 的独特优势**：LLM 自然语言集成、服务端 FakePlayer、战斗 AI、合成系统、任务队列反馈、国际化支持。

## 国际化

所有面向用户的字符串均可通过语言文件翻译：
- `src/main/resources/lang/en_us.json` — 英文
- `src/main/resources/lang/zh_cn.json` — 中文

添加新语言：在 `src/main/resources/lang/` 目录下创建遵循相同键结构的 JSON 文件。

## 依赖

- NeoForge 21.1.176
- Minecraft 1.21.1
- Parchment mappings 2024.11.17
- JUnit 5.10.2（测试）
- Mockito 5.11.0（测试）

## 许可证

MIT
