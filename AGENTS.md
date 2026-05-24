# AGENTS.md

## 项目简介

NeoForge 1.21.1 Minecraft 模组（aimod）。通过 LLM API 实现自然语言控制的 AI 机器人。

**架构**：混合双实体架构 — AIBotEntity（extends Mob）是世界可见实体，负责渲染和物理存在；FakePlayer（extends ServerPlayer）是 AI 逻辑的核心载体，拥有完整的玩家能力和所有 AI 控制逻辑。

## 构建

**关键**：标准 ./gradlew build 会失败。必须使用本地初始化脚本：

`.\gradlew.bat build --init-script init-local.gradle`

原因是本机无法通过 Java SSL 连接到 maven.neoforged.net。所有 NeoForge 依赖缓存在 local-repo/ 中。

- 需要 Java 21。gradle.properties 中硬编码了 systemProp.java.home — 如果 JDK 路径不同请调整。
- Gradle 守护进程已禁用（org.gradle.daemon=false）。
- Parchment 映射配置为 1.21.1。
- 测试依赖：JUnit 5.10.2 + Mockito 5.11.0。

## 架构

包名：com.aimod

### 双实体架构（职责分离）

| 实体 | 类型 | 职责 |
|------|------|------|
| AIBotEntity | extends Mob | 世界可见实体：渲染、碰撞、mob AI 目标、物品拾取、位置同步到 FakePlayer |
| FakePlayer | extends ServerPlayer | AI 逻辑核心：任务管理、移动控制、寻路、背包、交互、所有 AI 操作 |

**关键原则**：FakePlayer 拥有所有 AI 状态和逻辑。AIBotEntity 仅作为世界存在壳，所有任务操作（assign/cancel/pause/resume）委托给 FakePlayer。

### 模块结构

| 层级 | 关键文件 | 角色 |
|------|----------|------|
| 入口 | AIMod.java | @Mod 类，注册命令和配置 |
| 世界实体 | entity/AIBotEntity.java | 世界可见实体（extends Mob），委托所有 AI 逻辑给 FakePlayer |
| 实体注册 | entity/ModEntities.java | EntityType 注册（DeferredRegister） |
| AI 核心 | fakeplayer/FakePlayer.java | 假人玩家（extends ServerPlayer），拥有所有 AI 状态和 MovementController |
| FP 管理器 | fakeplayer/FakePlayerManager.java | FakePlayer 生命周期管理，集成 BotProfileStore |
| FP 网络 | fakeplayer/FakePlayerNetHandler.java | 继承 ServerGamePacketListenerImpl，丢弃数据包 |
| FP 连接 | fakeplayer/FakeClientConnection.java | 基于 EmbeddedChannel 的连接 |
| Profile 持久化 | fakeplayer/BotProfileStore.java | GameProfile 持久化（name 到 UUID，存储到 config/aimod/bots.json） |
| 兼容层 | fakeplayer/FakeConnection.java | 旧版连接（保持兼容） |
| 兼容层 | fakeplayer/FakeNetHandler.java | 旧版网络处理器（保持兼容） |
| 命令 | command/BotCommand.java | /ai_bot 命令树（16 个子命令） |
| 直接命令 | command/DirectCommandHandler.java | 绕过 LLM 直接创建任务 |
| AI 核心 | ai/BotAIManager.java | 解析 LLM 响应为动作序列 |
| 任务 | ai/Task.java | 任务数据模型，含状态和动作列表 |
| 任务反馈 | ai/TaskFeedback.java | 向玩家报告任务状态（i18n） |
| 世界扫描 | ai/WorldScanner.java | 扫描附近方块、实体和环境 |
| 背包工具 | ai/InventoryUtils.java | 机器人背包操作方法 |
| 配方索引 | ai/RecipeIndex.java | 合成配方索引（O(1) 查找，Tag 匹配，催化剂区分） |
| **移动控制** | **ai/movement/MovementController.java** | **集中式移动控制器：A* 异步寻路 + 直接移动 + 卡住检测** |
| **移动基类** | **ai/movement/BotMovement.java** | **移动步骤抽象基类（src → dest），含状态机** |
| **平地移动** | **ai/movement/MovementTraverse.java** | **平地行走 + 自动搭桥** |
| **搭柱移动** | **ai/movement/MovementPillar.java** | **放置方块 + 跳跃向上** |
| **下降移动** | **ai/movement/MovementDescend.java** | **下降1格** |
| **跌落移动** | **ai/movement/MovementFall.java** | **多格跌落(2-4格)** |
| **对角移动** | **ai/movement/MovementDiagonal.java** | **水平对角移动** |
| **对角上升** | **ai/movement/MovementAscend.java** | **对角上升1格** |
| **跑跳移动** | **ai/movement/MovementParkour.java** | **冲刺跳跃过沟** |
| **下挖移动** | **ai/movement/MovementDownward.java** | **挖掘下降一层** |
| **脱困检测** | **ai/movement/UnstuckDetector.java** | **位置历史追踪 + 卡住检测 + 分级恢复策略** |
| 动作系统 | ai/action/*.java | Action 基类 + 14 种动作类型（通过 MovementController 移动） |
| 寻路系统 | ai/pathing/*.java | Baritone 风格 A* 寻路 + Goal 系统 |
| **异步寻路** | **ai/pathing/AsyncPathfinder.java** | **后台线程寻路，不阻塞服务器 tick（支持 CalculationContext 线程安全模式）** |
| **寻路上下文** | **ai/pathing/CalculationContext.java** | **线程安全的世界状态快照：方块缓存 + 工具集 + 配置标志，供异步 A* 使用** |
| **工具集** | **ai/pathing/ToolSet.java** | **背包工具速度计算（参考 Baritone ToolSet），含附魔效率和药水效果** |
| LLM 服务 | ai/llm/LLMService.java | HTTP 调用 OpenAI 兼容 API（HTTP/2 共享实例） |
| LLM 响应 | ai/llm/LLMResponse.java | LLM 响应数据模型 |
| LLM 解析 | ai/llm/LLMResponseParser.java | 响应解析器（宽松解析 + 正则回退） |
| 动作描述 | ai/llm/ActionDescriptor.java | 动作描述符，封装 JSON 参数 |
| 配置 | config/ModConfig.java | ModConfigSpec — 12 项配置选项 |
| 客户端 | client/ClientModEvents.java | AIBotRenderer（HumanoidMobRenderer，Steve 皮肤） |
| 日志 | util/DevLog.java | 开发日志工具 |

### 移动系统

移动系统是 P0 重构的核心成果，解决了 Action.navigateTo() 重复代码和同步寻路阻塞问题。

**MovementController**（集中式移动控制器）：
- 持有 AsyncPathfinder（后台线程 A* 寻路）和 UnstuckDetector（卡住检测）
- `navigateTo(target)` — 请求异步 A* 寻路，自动跟踪路径
- `moveToward(target, speed)` — 直接移动（无寻路），用于短距离
- `stop()` — 停止所有移动
- `tick()` — 在 FakePlayer.tick() 中调用，处理异步结果和移动执行

**BotMovement**（移动步骤抽象）：
- `src` → `dest` 的单步移动，含状态机（PENDING → PREPPING → RUNNING → COMPLETE/FAILED）
- `calculateCost(level)` — 供 A* 寻路计算代价
- 子类：MovementTraverse（平地+搭桥）、MovementPillar（搭柱向上）、MovementDescend（下降1格）、MovementFall（多格跌落2-4格）、MovementDiagonal（水平对角）、MovementAscend（对角上升）、MovementParkour（跑跳过沟）、MovementDownward（挖掘下降）

**AsyncPathfinder**：
- 在独立后台线程运行 Pathfinder.findPath()，优先级为 Thread.MIN_PRIORITY
- 结果通过 tick() 回调到服务器主线程
- 支持取消（cancel()）

**UnstuckDetector**：
- 追踪最近 100 个位置
- 60 tick（3 秒）无显著移动判定为卡住
- 分级恢复：WAIT → JUMP → SHIMMY_LEFT → SHIMMY_RIGHT → SKIP

## 动作类型

| 动作 | 说明 | 使用移动 |
|------|------|----------|
| MoveToAction | 导航到指定坐标 | MovementController.navigateTo() |
| BreakBlockAction | 破坏方块 | 无（原地操作） |
| PlaceBlockAction | 放置方块 | 无（原地操作） |
| AttackAction | 攻击实体 | MovementController.moveToward() |
| CraftAction | 合成物品 | 无（原地操作） |
| FollowAction | 跟随玩家 | MovementController.moveToward() |
| GiveItemAction | 给玩家物品 | 无（原地操作） |
| RequireItemsAction | 检查背包物品 | 无（门控动作） |
| SayAction | 广播聊天 | 无 |
| WaitAction | 等待 N tick | 无 |
| MineBlockAction | 搜索并挖掘矿石 | MovementController.moveToward() + A* |
| GatherResourceAction | 采集资源 | MovementController.moveToward() + A* + 搭柱 |
| InteractBlockAction | 交互方块 | 无（原地操作） |
| EquipItemAction | 装备物品 | 无（原地操作） |

## 命令系统

/ai_bot 命令树支持 16 个子命令，灵感来自 Baritone 1.21.1 命令模式。

### 核心命令（LLM 驱动）

- /ai_bot spawn — 在附近生成 AI 机器人
- /ai_bot task <命令> — 分配自然语言任务（LLM 解析为动作）
- /ai_bot task_all <命令> — 向所有机器人分配任务
- /ai_bot status — 显示最近机器人状态、任务进度、暂停状态

### 控制命令（Baritone 风格）

- /ai_bot stop / cancel — 立即取消当前任务
- /ai_bot pause / resume — 暂停/恢复任务执行

### 直接命令（绕过 LLM，即时动作）

- /ai_bot goto <x> <y> <z> — 导航到坐标
- /ai_bot goto <x> <z> — 导航到 X,Z（当前 Y 高度）
- /ai_bot mine <方块> [数量] — 挖掘指定方块
- /ai_bot follow <玩家> — 跟随玩家
- /ai_bot gather <类型> [数量] — 采集资源（WOOD, STONE, DIRT, SAND）
- /ai_bot craft <物品> [数量] — 制作物品
- /ai_bot say <消息> — 机器人广播聊天消息
- /ai_bot give <物品> [数量] — 给你物品
- /ai_bot equip <物品> — 装备物品
- /ai_bot help — 显示帮助信息

## 添加新动作

1. 在 ai/action/ 目录下创建继承 Action 的类，实现 canExecute()、execute()、isComplete()
2. 所有动作直接接收 FakePlayer 作为 bot 参数
3. 需要移动时使用 `getMovementController(bot).navigateTo(target)` 或 `navigateTo(bot, target, speed)`
4. 在 BotAIManager.convertResponseToActions() 中注册 — 添加 switch case

## 世界上下文

LLM 接收的世界上下文包括：
- 机器人位置、生命值
- 机器人背包内容
- 当前时间（白天/夜晚）
- 生态群系信息
- 附近方块、实体和玩家（通过 WorldScanner）

## 任务反馈

TaskFeedback 向分配任务的玩家报告任务状态：
- 任务开始通知
- 动作进度更新
- 任务完成/失败通知
- 缺少资源提醒
- 支持可翻译消息（i18n）

## 配置

首次启动时在 config/aimod-common.toml 生成。12 项配置选项：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| apiUrl | String | DeepSeek 端点 | LLM API 端点 URL |
| apiKey | String | "" | LLM 服务的 API 密钥 |
| modelName | String | deepseek-v4-pro | 使用的模型名称 |
| maxTokens | Integer | 1024 | LLM 响应的最大 token 数 |
| temperature | Double | 0.7 | LLM 温度 |
| connectTimeoutSeconds | Integer | 10 | HTTP 连接超时（秒） |
| readTimeoutSeconds | Integer | 600 | HTTP 读取超时（秒） |
| streamResponses | Boolean | false | 请求流式响应 |
| modelHealthCheck | Boolean | true | 运行模型可用性检查 |
| healthCheckIntervalSeconds | Integer | 300 | 健康检查缓存时长 |
| healthCheckTimeoutSeconds | Integer | 20 | 健康检查读取超时 |
| allowDevCreativeItemProvisioning | Boolean | false | 开发用：允许凭空创建物品 |

## 关键约定

- 模组 ID：aimod（定义在 AIMod.MODID 和 gradle.properties 中）
- AIBotEntity 是世界实体壳（extends Mob），FakePlayer 是 AI 逻辑核心（extends ServerPlayer）
- FakePlayer 通过 placeNewPlayer() 注册到服务器玩家列表
- FakePlayer 拥有 MovementController，所有移动逻辑集中在此
- BotProfileStore 持久化 GameProfile（name 到 UUID）到 config/aimod/bots.json
- RecipeIndex 提供 O(1) 配方查找（参考 EMI）
- 命令使用 Brigadier，通过 NeoForge.EVENT_BUS.addListener 注册
- neoforge.mods.toml 使用占位符，在构建时从 gradle.properties 展开
- DevLog 用于带标签的结构化开发日志
- 所有动作直接接收 FakePlayer 作为 bot 参数
- DirectCommandHandler 用于绕过 LLM 直接创建任务对象
- 所有面向用户的字符串使用 Component.translatable() 支持 i18n
- BotCommand.init() 在 AIMod.onServerStarted() 中初始化 FakePlayerManager
- FakePlayer 拥有 cancelTask()、pauseExecution()、resumeExecution() 任务控制方法
- 寻路通过 AsyncPathfinder 在后台线程执行，不阻塞服务器 tick
- 异步寻路使用 CalculationContext 快照世界状态，保证线程安全
- MovementController.navigateTo() 自动创建 CalculationContext 并预加载区域

## 开发路线图

### 阶段 1：移动精度升级（已完成）
- [x] FakePlayer.travel() 重写 — AI 活动时跳过原版移动
- [x] 路径偏离检测 + 卡住超时自动跳过
- [x] 斜坡、1 格跳跃验证

### 阶段 2：移动系统抽象（P0，已完成）
- [x] BotMovement 基类（src/dest/status 状态机）
- [x] MovementTraverse — 平地行走 + 自动搭桥
- [x] MovementPillar — 搭柱向上
- [x] MovementDescend — 下降1格
- [x] MovementFall — 多格跌落(2-4格)
- [x] MovementDiagonal — 水平对角移动
- [x] MovementAscend — 对角上升1格
- [x] MovementParkour — 跑跳过沟
- [x] MovementDownward — 挖掘下降
- [x] MovementController — 集中式移动控制（替代 Action.navigateTo 重复代码）
- [x] AsyncPathfinder — 后台线程寻路，不阻塞 tick
- [x] UnstuckDetector — 位置历史追踪 + 分级恢复策略
- [x] AIBotEntity/FakePlayer 职责分离 — AIBotEntity 仅世界存在，FakePlayer 拥有全部 AI 逻辑

### 阶段 3：世界缓存与挖矿智能（P1）
- [ ] ChunkCache：缓存已加载区块的方块数据
- [ ] OreVeinTracker：矿脉追踪
- [ ] BranchMining：分支采矿策略（鱼骨矿道）

### 阶段 4：路径执行增强（P1）
- [ ] PathExecutor 状态机增强 + 路径拼接（trySplice）
- [x] Movement 子类扩展 — 已实现全部8种Movement类型
- [ ] 路径缓存：复用最近计算的路径

### 阶段 5：战斗 AI 增强（P2）
- [ ] 武器选择、盾牌格挡、走位、远程攻击

### 阶段 6：任务系统增强（P2）
- [ ] 优先级任务、持久化、多任务队列、航点系统

### 阶段 7：命令系统增强（P3）
- [ ] 相对坐标、航点命令、建筑命令、重复命令

## 已知问题

- FakeClientConnection 使用反射设置 EmbeddedChannel — 字段名可能不同
- 无持久化（背包和任务不保存，GameProfile 已持久化）
- 单元测试：12 个文件 / 90 个测试全部通过；无集成测试
- WorldScanner 暴力扫描方块区域（O(n^3)）

## 可忽略文件

- build_*.txt、download_output.txt — 构建调试日志
- local-repo/、maven-cache/、maven-proxy.ps1 — 本地依赖基础设施
- init-proxy.gradle — 未使用的代理初始化脚本
- mc_1211.json、mc_manifest.json — Minecraft 版本元数据
- TestSSL*.class、TestSSL.java — 遗留测试产物
- temp_installertools_*.html — 临时下载产物
- _*.py、*.ps1 — 构建调试的临时脚本
- *.md（AGENTS.md 除外）— 项目文档
- gh.zip — 临时文件
- Player2NPC-master.zip、PlayerEngine-main.zip、SiliconeDolls-releases-1.21.zip、RollingGate-releases-1.21.zip、JustEnoughItems-26.1.zip、emi-1.21.zip — 参考项目压缩包

## 参考项目目录（只读，不参与构建，不提交）

以下目录为参考代码，仅供阅读和学习，已加入 .gitignore：

| 目录 | 项目 | 用途 |
|------|------|------|
| baritone-ref/ | Baritone 1.21.1 | A* 寻路、Movement 系统、世界缓存参考 |
| PlayerEngine-main/ | PlayerEngine (Automatone) | 服务端 Baritone 分支，Movement 抽象、PathingBehavior、UnstuckChain 参考 |
| Player2NPC-master/ | Player2NPC | NPC 行为链系统、AltoClef 任务系统参考 |
| RollingGate-releases-1.21/ | RollingGate | 配置文件风格参考 |
| SiliconeDolls-releases-1.21/ | SiliconeDolls | FakePlayer 创建/注册模式参考 |
| JustEnoughItems-26.1/ | JustEnoughItems | 配方解析参考 |
| emi-1.21/ | EMI | 配方树参考 |
