# 项目重构：NeoForge 1.21.1 AI Bot Mod

## 项目概述

F:\MC 是一个 NeoForge 1.21.1 Minecraft 模组，通过 LLM API 实现自然语言控制的 AI 机器人。
**构建命令**：`.\gradlew.bat build --init-script init-local.gradle`（必须使用，标准 build 会因 SSL 失败）

## 当前架构

混合模式：AIBotEntity(extends Mob) 是世界实体，内部懒加载 FakePlayer(extends ServerPlayer)。
- 53 个 Java 源文件，14 种动作类型，A* 寻路，LLM 驱动任务解析
- 已实现：FakePlayer 生命周期、任务系统、16 个命令、配方索引、世界扫描、背包管理
- 路线图阶段 1-2 大部分完成（移动精度、多格跌落、对角线检测）

## 已知问题（需解决）

1. **AIBotEntity + FakePlayer 双实体架构冗余**：AIBotEntity 是 Mob，FakePlayer 是 ServerPlayer，两者都持有任务状态、都有 assignTask/cancelTask，职责不清。位置同步依赖 tick 顺序。
2. **动作系统过于扁平**：Action 基类的 navigateTo() 在每个动作中重复实现移动逻辑，没有 Baritone 的 Movement 抽象（src/dest/positionsToBreak/status）。
3. **寻路是同步阻塞的**：Pathfinder.findPath() 在调用线程阻塞，大范围搜索会卡住 tick。
4. **没有世界缓存**：WorldScanner 每次暴力扫描 O(n^3)，没有 Baritone 的 CachedWorld/CachedRegion 区块缓存。
5. **没有脱困机制**：卡住时没有自动检测和恢复（参考 AltoClef 的 UnstuckChain）。
6. **没有自动工具选择**：挖掘时不自动切换最佳工具（参考 SiliconeDolls 的 AutoReplaceTool、Baritone 的 ToolSet）。
7. **Task/Action 没有复合任务能力**：无法递归组合任务（参考 AltoClef 的 ResourceTask/AbstractDoToClosestObjectTask）。

## 参考项目（只读，已在本地解压，可直接阅读源码）

| 目录 | 项目 | 可复用内容 |
|------|------|------------|
| `baritone-ref/` | Baritone 1.21.1（原版客户端） | A* 寻路、Movement 系统（8 种 Movement 子类）、CalculationContext、CachedWorld 区块缓存、PathingBehavior 异步路径管理、ToolSet、Goal 系统、PathExecutor |
| `PlayerEngine-main/` | PlayerEngine（Automatone，服务端 Baritone 分支） | **最重要**：已适配服务端的 Baritone，Movement/PathingBehavior/CalculationContext 改为 Entity 实体而非客户端 Player；IEntityContext、EntityContext、LivingEntityInventory 等实体抽象接口；AltoClef 任务系统（Task/TaskChain/ResourceTask/UnstuckChain/FoodChain/MobDefenseChain） |
| `SiliconeDolls-releases-1.21/` | SiliconeDolls | FakePlayer 创建/注册模式（我们已采用）、PlayerActionPack、FakePlayerAutoReplaceTool、CombatManager、容器交互（PlayerContainer/PlayerInventoryMenu）、Mixin 模式参考（PlayerAccessor、ServerPlayerMixin、ConnectionMixin） |
| `Player2NPC-master/` | Player2NPC | NPC 行为链系统、AutomatoneEntity、CompanionManager |
| `JustEnoughItems-26.1/` | JEI | 配方解析参考（我们已有 RecipeIndex） |
| `emi-1.21/` | EMI | 配方树参考 |

### 重点参考文件指引

**Baritone 寻路核心**（baritone-ref/）：
- `src/main/java/baritone/pathing/calc/AStarPathFinder.java` — A* 实现，BinaryHeap + Favoring + 超时
- `src/main/java/baritone/pathing/movement/Movement.java` — Movement 基类（src/dest/positionsToBreak/MovementState）
- `src/main/java/baritone/pathing/movement/movements/MovementTraverse.java` — 平地行走+搭桥
- `src/main/java/baritone/pathing/movement/movements/MovementPillar.java` — 搭柱向上
- `src/main/java/baritone/pathing/movement/CalculationContext.java` — 寻路上下文（缓存方块状态查询）
- `src/main/java/baritone/pathing/path/PathExecutor.java` — 路径执行器（状态机 + splice）
- `src/main/java/baritone/cache/CachedWorld.java` — 区块缓存（Region/Chunk/磁盘持久化）
- `src/main/java/baritone/cache/ChunkPacker.java` — 区块打包为缓存格式
- `src/main/java/baritone/behavior/PathingBehavior.java` — 异步路径管理（findPathInNewThread + 路径拼接）
- `src/main/java/baritone/utils/ToolSet.java` — 工具选择（按挖掘速度排序）

**PlayerEngine 服务端适配**（PlayerEngine-main/）：
- `src/main/java/baritone/pathing/movement/Movement.java` — 服务端 Movement（IEntityContext 替代 IPlayerContext）
- `src/main/java/baritone/behavior/PathingBehavior.java` — 服务端异步路径管理
- `src/main/java/baritone/utils/player/EntityContext.java` — 实体上下文（替代客户端 PlayerContext）
- `src/autoclef/java/adris/altoclef/chains/UnstuckChain.java` — 脱困链（位置历史+卡住检测+自动恢复）
- `src/autoclef/java/adris/altoclef/chains/FoodChain.java` — 自动进食
- `src/autoclef/java/adris/altoclef/chains/MobDefenseChain.java` — 战斗防御
- `src/autoclef/java/adris/altoclef/tasks/resources/MineAndCollectTask.java` — 挖矿+拾取复合任务
- `src/autoclef/java/adris/altoclef/tasksystem/Task.java` — 任务基类（递归组合模式）

**SiliconeDolls FakePlayer 模式**（SiliconeDolls-releases-1.21/）：
- `src/.../entity/FakePlayer.java` — FakePlayer 创建/注册
- `src/.../entity/FakeClientConnection.java` — 网络连接
- `src/.../entity/FakePlayerNetHandler.java` — 网络处理器
- `src/.../tool/FakePlayerAutoReplaceTool.java` — 自动换工具
- `src/.../tool/PlayerContainer.java` — 容器交互
- `src/.../mixin/PlayerAccessor.java` — Player 私有字段访问
- `src/.../mixin/ServerPlayerMixin.java` — ServerPlayer tick 注入
- `src/.../mixin/ConnectionMixin.java` — 连接处理

## 重构目标

### 优先级 P0：架构清理
1. **统一实体架构**：评估是否移除 AIBotEntity，让 FakePlayer 直接作为世界实体（参考 SiliconeDolls）；或明确 AIBotEntity 只负责可见外观，所有逻辑下沉到 FakePlayer
2. **引入 Movement 抽象**：从 PlayerEngine-main 的 `baritone.pathing.movement.Movement` 借鉴，创建 Movement 基类（src/dest/positionsToBreak/status），替代当前 Action.navigateTo() 的重复逻辑
3. **异步寻路**：从 PlayerEngine-main 的 `PathingBehavior.findPathInNewThread()` 借鉴，将 Pathfinder 放到独立线程

### 优先级 P1：能力增强
4. **区块缓存**：从 baritone-ref 的 `CachedWorld/CachedRegion/ChunkPacker` 借鉴，实现轻量级区块缓存替代暴力扫描
5. **脱困系统**：从 PlayerEngine-main 的 `UnstuckChain` 借鉴，实现位置历史追踪 + 卡住检测 + 自动脱困
6. **自动工具选择**：从 baritone-ref 的 `ToolSet` 和 SiliconeDolls 的 FakePlayerAutoTool 借鉴，挖掘时自动切换最佳工具
7. **复合任务**：从 AltoClef 的 `ResourceTask/AbstractDoToClosestObjectTask` 借鉴，支持任务递归组合

### 优先级 P2：路径图完善
8. Movement 子类：MovementTraverse（平地行走+搭桥）、MovementPillar（搭柱向上）
9. 异步路径缓存和路径拼接（参考 PathExecutor.trySplice）

## 约束

- NeoForge 1.21.1，Mixin 可用（NeoForge 自带，无需额外依赖）
  启用方式：取消 neoforge.mods.toml 中 [[mixins]] 的注释，创建 aimod.mixins.json，添加 Mixin 类
  参考 SiliconeDolls 的 mixin 配置和 Mixin 类（如 PlayerAccessor、ServerPlayerMixin、ConnectionMixin）
- 必须保持 LLM 驱动的任务系统正常工作
- 构建命令：`.\gradlew.bat build --init-script init-local.gradle`
- Java 21，Gradle 守护进程已禁用
- 所有面向用户的字符串使用 Component.translatable()（i18n）
- 参考项目是只读的，不要修改它们

## 请求

1. 仔细阅读当前项目源码（src/main/java/com/example/aimod/）和上述参考项目的关键文件
2. 分析每个参考项目中可直接复用或需要适配后复用的具体代码模式
3. 制定详细的重构计划，按 P0->P1->P2 优先级排列
4. 执行重构，每完成一个阶段后运行构建验证
5. 同步更新 AGENTS.md 以反映新的架构