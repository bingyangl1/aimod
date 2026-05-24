# AI Mod — Minecraft AI 机器人模组

> Minecraft 1.21.1 NeoForge 模组。通过云端 LLM（大语言模型）或本地确定性规划器，实现自然语言控制的 AI 机器人。机器人可自主移动、采集、合成、战斗，具备危险规避、连锁采集、撤销等能力。

---

## 项目信息

| 属性 | 值 |
|------|------|
| Minecraft 版本 | 1.21.1 |
| NeoForge 版本 | 21.1.176 |
| Java 版本 | 21 |
| 模组 ID | `aimod` |
| 当前版本 | 1.0.43-r54 |
| 许可证 | MIT |

---

## 快速开始

### 构建

```bash
./gradlew jar
```

产物：`build/libs/aimod-<version>.jar`

### 安装

1. 将 jar 放入 `.minecraft/mods/` 目录
2. 使用 NeoForge 1.21.1 启动游戏
3. 首次启动在 `config/aimod-common.toml` 生成配置文件
4. 配置 `apiKey` 启用 LLM 功能（可选，留空则使用本地规划器）

### 基本使用

```
/ai_bot spawn                   → 生成 AI 机器人
/ai_bot task 制作一把钻石镐给我   → 自然语言任务（LLM 云端规划）
/ai_bot task 挖5个铁矿石         → 同上
/ai_bot mine iron_ore 3          → 直接命令（本地规划器，零延迟）
/ai_bot status                   → 查看所有机器人状态
/ai_bot stop                     → 停止当前任务
/ai_bot help                     → 命令帮助
/ai_bot help goto                → 查看具体命令详细帮助
```

---

## 架构

### 双层规划器

```
用户命令
  │
  ├─ 云端 LLM 规划器 (DeepSeek/GPT/Ollama)
  │    自然语言 → JSON actions → 14种Action
  │    灵活，任意复杂指令，2-10s延迟
  │
  └─ 本地确定性规划器
       正则+NLP + MaterialTree配方树 + SequencePlanner
       零延迟，离线可用，覆盖常见模式
       支持 PlanCache: LLM成功规划持久化复用
```

### 实体架构

```
AIBotEntity (Mob) ← 世界中可见实体
  └─ FakePlayer (ServerPlayer) ← 提供完整玩家能力
       ├─ BotAIManager ← LLM 解析 + 动作序列
       ├─ MovementController ← 8 种 Movement 类型 (A* 寻路)
       ├─ ChainManager ← 4 条行为链 (Danger/Defense/Food/Unstuck)
       ├─ UndoManager ← 10 次撤销历史
       └─ PlanCache ← LLM 规划结果本地缓存
```

### 路径/Movement 系统

8 种 Movement 类型（Baritone 风格），由 A* Pathfinder 自动选择：

| Movement | 功能 |
|----------|------|
| Traverse | 平地行走 + 搭桥 |
| Pillar | 搭柱向上 |
| Ascend | 斜坡上升 |
| Descend | 斜坡下降 |
| Diagonal | 对角移动 |
| Fall | 跌落（含水桶防摔） |
| Parkour | 跑跳跨沟 |
| Downward | 向下挖掘 |

### 行为链优先级

```
P90 Danger   ← 岩浆/着火/溺水/坠落
P70 Defense  ← 怪物攻击（撤退/盾牌/远程）
P55 Food     ← 自动进食
P50 Unstuck  ← 卡住自救（WAIT→JUMP→SHIMMY→PILLAR）
```

---

## 命令系统

27 条命令，Tab 补全，中英文双语帮助。

### 核心命令

| 命令 | 说明 |
|------|------|
| `/ai_bot spawn [name]` | 生成机器人 |
| `/ai_bot select <name>` | 选择默认操作目标 |
| `/ai_bot status [name]` | 查看状态（无参 = 全部） |
| `/ai_bot task <命令>` | 分配自然语言任务（LLM） |
| `/ai_bot task_all <命令>` | 分配给全部空闲机器人 |
| `/ai_bot test` | 运行 18 项自动化测试 |

### 控制命令

| 命令 | 说明 |
|------|------|
| `/ai_bot stop [name]` | 停止机器人 |
| `/ai_bot pause [name]` | 暂停 |
| `/ai_bot resume [name]` | 恢复 |
| `/ai_bot remove <name>` | 删除活跃机器人 |

### 操作命令

| 命令 | 说明 |
|------|------|
| `/ai_bot goto <x> <y> <z>` | 浮点坐标导航（支持 `~ ^`） |
| `/ai_bot mine <方块> [数量]` | 挖掘（veinMine=ON 时连锁） |
| `/ai_bot vein <方块> [数量]` | 强制连锁挖掘 |
| `/ai_bot gather <类型> [数量]` | 采集（WOOD/STONE/DIRT/SAND/COBBLESTONE） |
| `/ai_bot craft <物品> [数量]` | 合成 |
| `/ai_bot follow <玩家>` | 跟随玩家（A* 寻路避障） |
| `/ai_bot follow_bot <bot> <玩家>` | 指定机器人跟随 |
| `/ai_bot give <物品> [数量]` | 给玩家物品 |
| `/ai_bot equip <物品>` | 装备物品 |
| `/ai_bot say <消息>` | 聊天消息 |

### 工具命令

| 命令 | 说明 |
|------|------|
| `/ai_bot inventory [name]` | 打开机器人背包（9x3 箱子） |
| `/ai_bot showpath [name]` | 显示路径粒子 |
| `/ai_bot toggle <功能>` | 切换 autoFish/autoReplenish/autoReplace/veinMine |
| `/ai_bot veinmine on/off/status` | 连锁采集开关 |
| `/ai_bot veinmine history` | 撤销历史 |
| `/ai_bot veinmine undo [steps]` | 撤销操作 |
| `/ai_bot save <名称> [描述]` | 保存机器人到磁盘 |
| `/ai_bot load <名称>` | 从磁盘加载 |
| `/ai_bot list` | 列出已保存机器人 |
| `/ai_bot delete <名称>` | 删除已保存机器人 |

---

## 配置文件

`config/aimod-common.toml`:

```toml
apiUrl = "https://api.deepseek.com/chat/completions"
apiKey = ""
modelName = "deepseek-v4-pro"
maxTokens = 128000
temperature = 0.7

# AI 行为
maxBots = 10
defaultScanRadius = 32
movementSpeed = 0.3
hungerThreshold = 14

# 自动功能
autoReplenish = true
autoReplaceTool = true
autoFish = false
veinMine = true          # 连锁采集
undoHistory = 10          # 撤销步数 (0=禁用)
```

---

## 功能特性

| 类别 | 特性 |
|------|------|
| **规划** | LLM 云端规划 + 本地 NLP 确定性规划 + PlanCache 复用 |
| **移动** | 8 Movement + A* Pathfinder + STEP_HEIGHT 1.0 |
| **生存** | 4 条行为链（Danger/Defense/Food/Unstuck）+ PILLAR 脱坑 |
| **采集** | 连锁挖矿/砍树（BFS VeinScanner）+ 工具自动选择（ToolSet） |
| **撤销** | UndoManager 记录 10 次操作，`/ai_bot veinmine undo` |
| **持久化** | BotInfo + BotPersistence（save/load/auto-load） |
| **配方** | RecipeIndex O(1) + MaterialTree 自动合成树 + ItemUid NBT 感知 |
| **世界** | ChunkCache 异步缓存 + BlockIterator 共享扫描 + DangerZone 危险检测 |
| **GUI** | BotStatusScreen 9x3 箱子界面 |
| **配置** | 18 个配置项 + 运行时 toggle |
| **测试** | 129 单元测试 + 18 集成测试 + 26 GameTest |
| **i18n** | 中英文双语（zh_cn / en_us） |

---

## 源码结构

```
com.aimod/
├── AIMod.java                     # @Mod 入口
├── entity/                        # AIBotEntity + ModEntities
├── fakeplayer/                    # FakePlayer + Manager + Persistence
├── ai/
│   ├── BotAIManager.java          # LLM 解析 + 动作序列
│   ├── Task.java / TaskFeedback.java
│   ├── WorldScanner.java          # 环境扫描
│   ├── InventoryUtils.java        # 背包工具 + FindItemResult
│   ├── RecipeIndex.java           # O(1) 配方查找
│   ├── VeinScanner.java           # BFS 连锁扫描
│   ├── UndoManager.java           # 撤销管理
│   ├── BlockIterator.java         # 共享扫描器
│   ├── action/                    # 15 种动作
│   │   ├── Action.java            #   基类
│   │   ├── GatherResourceAction   #   采集
│   │   ├── MineBlockAction        #   挖矿
│   │   ├── VeinMineAction         #   连锁挖矿
│   │   ├── PlaceBlockAction       #   放置
│   │   ├── FollowAction           #   跟随
│   │   ├── CraftAction            #   合成
│   │   └── ...
│   ├── movement/                  # 8 Movement + Controller + UnstuckDetector
│   ├── pathing/                   # A* Pathfinder + Goals + CalculationContext
│   ├── chain/                     # 4 条行为链 + DangerZone
│   ├── llm/                       # LLMService + PlanCache + StateMachine
│   ├── planner/                   # CommandParser + SequencePlanner
│   ├── craft/                     # MaterialTree + MaterialNode + TreeCost
│   ├── tool/                      # AutoReplenish/ReplaceTool/Fish
│   └── cache/                     # ChunkCache + CachedChunkData
├── command/                       # BotCommand (27 条) + DirectCommandHandler
├── config/                        # ModConfig (18 配置项)
├── client/                        # ClientModEvents + BotStatusScreen
├── gametest/                      # GameTest 模板
├── mixin/                         # 3 Mixin (Connection/PlayerList/ServerConfig)
└── util/                          # DevLog
```

---

## 参考项目与致谢

本模组深度参考了 10 个优秀开源项目，详见 [docs/REFERENCES.md](docs/REFERENCES.md)。

向以下项目的作者和贡献者致以诚挚感谢：

Baritone (cabaletta) · PlayerEngine (Goodbird) · Player2NPC (Goodbird) · SiliconeDolls (AnvilCraft) · RollingGate (AnvilCraft) · AI-Player (shasankp000) · Meteor Client (MeteorDevelopment) · EMI (EmilyPloszaj) · JEI (mezz) · MineColonies (LDT Team)

---

## 构建与发布

GitHub Actions 自动构建（tag 触发）：

```bash
git tag r55
git push origin r55
```

CI 自动编译 jar 并创建 GitHub Release。
