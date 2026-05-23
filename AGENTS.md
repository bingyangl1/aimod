# AGENTS.md

## 项目简介

NeoForge 1.21.1 Minecraft 模组（aimod）。通过 LLM API 实现自然语言控制的 AI 机器人。

**架构**：混合模式 — AIBotEntity（extends Mob）是世界中可见的实体，内部懒加载 FakePlayer（extends ServerPlayer）以获得完整玩家能力。AIBotEntity 是实际控制者，FakePlayer 提供玩家 API。

## 构建

**关键**：标准 ./gradlew build 会失败。必须使用本地初始化脚本：

`
.\gradlew.bat build --init-script init-local.gradle
`

原因是本机无法通过 Java SSL 连接到 maven.neoforged.net。所有 NeoForge 依赖缓存在 local-repo/ 中。

- 需要 Java 21。gradle.properties 中硬编码了 systemProp.java.home — 如果 JDK 路径不同请调整。
- Gradle 守护进程已禁用（org.gradle.daemon=false）。
- Parchment 映射配置为 1.21.1。
- 测试依赖：JUnit 5.10.2 + Mockito 5.11.0。

## 架构

包名：com.example.aimod

**混合架构**：AIBotEntity（Mob）是世界实体，内部懒加载 FakePlayer（ServerPlayer）。FakePlayer 通过 placeNewPlayer() 注册到服务器玩家列表，提供完整玩家能力。AIBotEntity 持有 BotAIManager，控制 AI 逻辑。

| 层级 | 关键文件 | 角色 |
|------|----------|------|
| 入口 | AIMod.java | @Mod 类，注册命令和配置 |
| 实体 | entity/AIBotEntity.java | 核心机器人实体（extends Mob），持有 BotAIManager 和 inventory |
| 实体注册 | entity/ModEntities.java | EntityType 注册（DeferredRegister） |
| FakePlayer | fakeplayer/FakePlayer.java | 假人玩家（extends ServerPlayer），提供完整玩家 API |
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
| 动作系统 | ai/action/*.java | Action 基类 + 14 种动作类型 |
| 寻路系统 | ai/pathing/*.java | Baritone 风格 A* 寻路 + Goal 系统 |
| LLM 服务 | ai/llm/LLMService.java | HTTP 调用 OpenAI 兼容 API（HTTP/2 共享实例） |
| LLM 响应 | ai/llm/LLMResponse.java | LLM 响应数据模型 |
| LLM 解析 | ai/llm/LLMResponseParser.java | 响应解析器（宽松解析 + 正则回退） |
| 动作描述 | ai/llm/ActionDescriptor.java | 动作描述符，封装 JSON 参数 |
| 配置 | config/ModConfig.java | ModConfigSpec — 12 项配置选项 |
| 客户端 | client/ClientModEvents.java | AIBotRenderer（HumanoidMobRenderer，Steve 皮肤） |
| 日志 | util/DevLog.java | 开发日志工具 |

## 动作类型

| 动作 | 说明 |
|------|------|
| MoveToAction | 寻路到指定方块坐标（A* 寻路） |
| BreakBlockAction | 使用 FakePlayer 破坏方块 |
| PlaceBlockAction | 使用 FakePlayer 放置方块 |
| AttackAction | 查找并攻击实体（多次攻击） |
| CraftAction | 使用 RecipeIndex O(1) 合成（Tag 感知 + 催化物区分） |
| FollowAction | 按名称跟随玩家 |
| GiveItemAction | 从机器人背包给玩家物品 |
| RequireItemsAction | 门控：检查背包是否有物品 |
| SayAction | 广播聊天消息 |
| WaitAction | 等待 N tick |
| MineBlockAction | 搜索并挖掘指定矿石（A* 寻路） |
| GatherResourceAction | 采集木材/石头/泥土/沙子（A* + 障碍破坏 + 搭柱） |
| InteractBlockAction | 与工作台、熔炉、箱子等交互 |
| EquipItemAction | 装备盔甲、工具或武器 |

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
3. 在 BotAIManager.convertResponseToActions() 中注册 — 添加 switch case

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
- AIBotEntity 是世界实体（extends Mob），FakePlayer 是内部工具（extends ServerPlayer）
- FakePlayer 通过 placeNewPlayer() 注册到服务器玩家列表
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

## 开发路线图

### 阶段 1：移动精度升级（P0，大部分完成）
- [x] FakePlayer.travel() 重写 — AI 活动时跳过原版移动
- [x] Action.navigateTo() — 正确处理重力、step-up 跳跃、碰撞
- [x] 路径偏离检测 + 卡住超时自动跳过
- [x] 斜坡、1 格跳跃验证
- [ ] MovementInput 类：模拟前进/后退/左右移动/跳跃/潜行输入

### 阶段 2：高级移动类型（P1，部分完成）
- [x] MoveCost 多格跌落（1-4 格）+ 跌落伤害代价
- [x] MoveCost 对角线穿墙检测（corner-cutting）
- [x] MoveCost 半砖/楼梯可行走
- [x] GatherResourceAction 障碍物自动破坏（卡住 2 秒触发）
- [ ] Movement 基类，含 src/dest/positionsToBreak/status
- [ ] MovementTraverse：平地行走 + 搭桥
- [ ] MovementPillar：搭柱向上

### 阶段 3：世界缓存与挖矿智能（P1）
- ChunkCache：缓存已加载区块的方块数据
- OreVeinTracker：矿脉追踪
- BranchMining：分支采矿策略（鱼骨矿道）

### 阶段 4：异步寻路（P2，依赖阶段 1）
- AsyncPathfinder：独立线程寻路
- PathingBehavior：管理寻路生命周期
- 路径缓存：复用最近计算的路径

### 阶段 5：战斗 AI 增强（P2，依赖阶段 1）
- 武器选择、盾牌格挡、走位、远程攻击

### 阶段 6：任务系统增强（P2）
- 优先级任务、持久化、多任务队列、航点系统

### 阶段 7：命令系统增强（P3）
- 相对坐标、航点命令、建筑命令、重复命令

## 已知问题

- FakeClientConnection 使用反射设置 EmbeddedChannel — 字段名可能不同
- 无持久化（背包和任务不保存，GameProfile 已持久化）
- 单元测试：10 个文件 / 87 个测试全部通过；无集成测试
- WorldScanner 暴力扫描方块区域（O(n^3)）
- AIBotEntity 和 FakePlayer 之间的位置同步依赖 tick 顺序

## 可忽略文件

- build_*.txt、download_output.txt — 构建调试日志
- local-repo/、maven-cache/、maven-proxy.ps1 — 本地依赖基础设施
- init-proxy.gradle — 未使用的代理初始化脚本
- mc_1211.json、mc_manifest.json — Minecraft 版本元数据
- TestSSL*.class、TestSSL.java — 遗留测试产物
- temp_installertools_*.html — 临时下载产物
- baritone-ref/ — Baritone 参考代码（不参与编译）
- _*.py、*.ps1 — 构建调试的临时脚本
- *.md（AGENTS.md 除外）— 项目文档
- gh.zip — 临时文件