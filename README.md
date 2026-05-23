# AI Mod — Minecraft 自然语言控制 AI 机器人模组

> 通过大语言模型（LLM）实现自然语言控制的 Minecraft AI 机器人。机器人以 **AIBotEntity**（Mob 实体）形式存在于世界中，内部懒加载 **FakePlayer**（ServerPlayer）以获得完整玩家能力。

## 项目信息

- **Minecraft 版本**：1.21.1
- **NeoForge 版本**：21.1.176
- **Java 版本**：21
- **模组 ID**：imod

## 快速开始

### 构建

> ⚠️ 标准 ./gradlew build 会因 SSL 限制失败，必须使用本地初始化脚本：

`
.\gradlew.bat build --init-script init-local.gradle
`

构建产物位于 uild/libs/aimod-1.0.0.jar。

### 安装

1. 将 imod-1.0.0.jar 放入 .minecraft/mods/ 目录
2. 使用 NeoForge 1.21.1 加载器启动游戏
3. 首次启动时在 config/aimod-common.toml 生成配置文件
4. 配置 piKey 以启用 LLM 功能（可选，留空则使用本地 fallback）

### 基本使用

`
/ai_bot spawn              — 生成 AI 机器人
/ai_bot task 挖 3 个铁矿石  — 分配自然语言任务（需 LLM）
/ai_bot mine iron_ore 3    — 直接命令挖矿（无需 LLM）
/ai_bot goto 100 64 100    — 导航到指定坐标
/ai_bot status             — 查看机器人状态
/ai_bot stop               — 停止当前任务
`

---

## 架构

### 混合架构：AIBotEntity + FakePlayer

`
┌─────────────────────────────────────────────────────────────┐
│  AIBotEntity (extends Mob)                                  │
│  ├─ 世界中可见的实体，渲染为玩家外观（Steve 皮肤）            │
│  ├─ 持有 inventory（SimpleContainer）                        │
│  ├─ 持有 BotAIManager                                       │
│  ├─ 每 tick 同步位置到 FakePlayer                            │
│  └─ 懒加载 FakePlayer（首次需要时创建）                       │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  FakePlayer (extends ServerPlayer)                  │    │
│  │  ├─ 注册到服务器玩家列表（placeNewPlayer）            │    │
│  │  ├─ 提供完整玩家能力（背包、合成、交互、破坏方块）     │    │
│  │  ├─ 无敌（hurt() 返回 false）                        │    │
│  │  ├─ 死亡后自动重生（40 tick 延迟）                    │    │
│  │  └─ 由 AIBotEntity 控制，不独立运行 AI               │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
`

**设计理由**：
- **AIBotEntity** 作为世界实体，可被玩家看见、交互、追踪
- **FakePlayer** 提供完整玩家 API（背包操作、方块破坏、合成等），避免重复实现
- FakePlayer 通过 placeNewPlayer() 注册到服务器，获得原生支持（区块加载、事件触发等）
- 参考 SiliconeDolls 的 FakePlayer 创建模式

---

## 源码结构

`
com.example.aimod/
├── AIMod.java                          # @Mod 入口，注册命令和配置
│
├── entity/
│   ├── AIBotEntity.java                # 核心机器人实体（extends Mob）
│   └── ModEntities.java                # 实体类型注册
│
├── fakeplayer/
│   ├── FakePlayer.java                 # 假人玩家（extends ServerPlayer）
│   ├── FakePlayerManager.java          # FakePlayer 生命周期管理
│   ├── FakePlayerNetHandler.java       # 网络处理器（丢弃数据包）
│   ├── FakeClientConnection.java       # EmbeddedChannel 连接
│   ├── FakeConnection.java             # 旧版连接（兼容）
│   ├── FakeNetHandler.java             # 旧版网络处理器（兼容）
│   └── BotProfileStore.java            # GameProfile 持久化（name→UUID）
│
├── ai/
│   ├── BotAIManager.java               # LLM 响应解析 → 动作序列
│   ├── Task.java                       # 任务数据模型
│   ├── TaskFeedback.java               # 任务状态反馈（i18n）
│   ├── WorldScanner.java               # 附近方块/实体/玩家扫描
│   ├── InventoryUtils.java             # 背包操作工具
│   ├── RecipeIndex.java                # 合成配方索引（O(1) 查找）
│   │
│   ├── action/                         # 动作系统（14 种动作）
│   │   ├── Action.java                 # 动作基类
│   │   ├── MoveToAction.java           # 寻路移动
│   │   ├── BreakBlockAction.java       # 破坏方块
│   │   ├── PlaceBlockAction.java       # 放置方块
│   │   ├── AttackAction.java           # 攻击实体
│   │   ├── CraftAction.java            # 合成物品
│   │   ├── FollowAction.java           # 跟随玩家
│   │   ├── GiveItemAction.java         # 给予物品
│   │   ├── RequireItemsAction.java     # 检查物品（门控）
│   │   ├── SayAction.java              # 聊天消息
│   │   ├── WaitAction.java             # 等待 tick
│   │   ├── MineBlockAction.java        # 挖掘矿石（A* 寻路）
│   │   ├── GatherResourceAction.java   # 采集资源（A* 寻路 + 障碍破坏）
│   │   ├── InteractBlockAction.java    # 与方块交互
│   │   └── EquipItemAction.java        # 装备物品
│   │
│   ├── llm/                            # LLM 服务层
│   │   ├── LLMService.java             # HTTP 调用 OpenAI 兼容 API
│   │   ├── LLMResponse.java            # 响应数据模型
│   │   ├── LLMResponseParser.java      # 响应解析器（宽松解析 + 正则回退）
│   │   └── ActionDescriptor.java       # 动作描述符（封装 JSON 参数）
│   │
│   └── pathing/                        # Baritone 风格 A* 寻路系统
│       ├── Pathfinder.java             # A* 核心算法
│       ├── PathNode.java               # 路径节点
│       ├── PathResult.java             # 路径结果
│       ├── PathExecutor.java           # 路径执行器（逐步移动 + 卡住检测）
│       ├── BinaryHeapOpenSet.java      # 二叉堆开放列表
│       ├── MoveCost.java               # 移动代价计算（多格跌落、穿墙检测）
│       ├── MovementHelper.java         # 移动辅助（方块可通行性）
│       ├── ToolSet.java                # 工具选择（挖掘速度计算）
│       └── goals/                      # Goal 系统
│           ├── Goal.java               # Goal 基类
│           ├── GoalBlock.java          # 到达指定方块
│           ├── GoalXZ.java             # 到达 XZ 平面
│           ├── GoalYLevel.java         # 到达 Y 高度
│           └── GoalComposite.java      # 组合目标
│
├── command/
│   ├── BotCommand.java                 # /ai_bot 命令树（16 个子命令）
│   └── DirectCommandHandler.java       # 绕过 LLM 直接创建任务
│
├── config/
│   └── ModConfig.java                  # 配置项（12 项）
│
├── client/
│   └── ClientModEvents.java            # 客户端渲染（AIBotRenderer → HumanoidMobRenderer）
│
└── util/
    └── DevLog.java                     # 开发日志工具
`

---

## 动作系统

| 动作 | 说明 | 寻路 |
|------|------|------|
| MoveToAction | 寻路到指定方块坐标 | A* |
| BreakBlockAction | 破坏方块 | — |
| PlaceBlockAction | 放置方块 | — |
| AttackAction | 查找并攻击实体 | 追踪移动 |
| CraftAction | 使用 RecipeIndex O(1) 合成 | — |
| FollowAction | 按名称跟随玩家 | 实时追踪 |
| GiveItemAction | 从机器人背包给玩家物品 | — |
| RequireItemsAction | 门控：检查背包是否有物品 | — |
| SayAction | 广播聊天消息 | — |
| WaitAction | 等待 N tick | — |
| MineBlockAction | 搜索并挖掘指定矿石 | A* |
| GatherResourceAction | 采集木材/石头/泥土/沙子 | A* + 障碍破坏 |
| InteractBlockAction | 与工作台/熔炉/箱子交互 | — |
| EquipItemAction | 装备盔甲/工具/武器 | — |

---

## 命令系统

/ai_bot 命令树支持 16 个子命令，灵感来自 Baritone。

### 核心命令（LLM 驱动）
| 命令 | 说明 |
|------|------|
| /ai_bot spawn | 在附近生成 AI 机器人 |
| /ai_bot task <命令> | 分配自然语言任务（LLM 解析为动作） |
| /ai_bot task_all <命令> | 向所有机器人分配任务 |
| /ai_bot status | 显示机器人状态、任务进度 |

### 控制命令
| 命令 | 说明 |
|------|------|
| /ai_bot stop / cancel | 立即取消当前任务 |
| /ai_bot pause / esume | 暂停/恢复任务执行 |

### 直接命令（绕过 LLM）
| 命令 | 说明 |
|------|------|
| /ai_bot goto <x> <y> <z> | 导航到坐标 |
| /ai_bot mine <方块> [数量] | 挖掘指定方块 |
| /ai_bot follow <玩家> | 跟随玩家 |
| /ai_bot gather <类型> [数量] | 采集资源（WOOD, STONE, DIRT, SAND） |
| /ai_bot craft <物品> [数量] | 制作物品 |
| /ai_bot say <消息> | 机器人广播聊天消息 |
| /ai_bot give <物品> [数量] | 给你物品 |
| /ai_bot equip <物品> | 装备物品 |
| /ai_bot help | 显示帮助信息 |

---

## 配置

首次启动时在 config/aimod-common.toml 生成，共 12 项：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| piUrl | String | DeepSeek 端点 | LLM API 端点 URL |
| piKey | String | "" | API 密钥（留空使用本地 fallback） |
| modelName | String | deepseek-v4-pro | 模型名称 |
| maxTokens | Integer | 1024 | 最大 token 数 |
| 	emperature | Double | 0.7 | 温度 |
| connectTimeoutSeconds | Integer | 10 | 连接超时 |
| eadTimeoutSeconds | Integer | 600 | 读取超时 |
| streamResponses | Boolean | false | 流式响应 |
| modelHealthCheck | Boolean | true | 模型可用性检查 |
| healthCheckIntervalSeconds | Integer | 300 | 健康检查缓存时长 |
| healthCheckTimeoutSeconds | Integer | 20 | 健康检查超时 |
| llowDevCreativeItemProvisioning | Boolean | false | 开发用：凭空创建物品 |

---

## 测试

- **单元测试**：10 个文件，87 个测试，全部通过
- **测试框架**：JUnit 5.10.2 + Mockito 5.11.0
- 测试覆盖：Task、LLMResponse、LLMResponseParser、BinaryHeapOpenSet、MoveCost、PathNode、Goals、DevLog 等

运行测试：
`
.\gradlew.bat test --init-script init-local.gradle
`

---

## 已知限制

- FakeClientConnection 使用反射设置 EmbeddedChannel，NeoForge patched 字段名可能不同
- 无持久化（机器人背包和任务不会在服务器重启后保存，GameProfile 已持久化）
- WorldScanner 暴力扫描方块区域（O(n³)）
- 单元测试无集成测试或游戏测试

---

## 许可证

MIT License
