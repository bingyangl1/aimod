# 参考项目

> 本模组深度参考了以下优秀开源 Minecraft 模组项目，在此致以诚挚感谢。

---

## Baritone

- **仓库**: [cabaletta/baritone](https://github.com/cabaletta/baritone) (1.21.1 分支)
- **作者**: leijurv, Brady, et al.
- **许可证**: LGPL-3.0
- **参考内容**:
  - A* 寻路算法 (Pathfinder, PathNode, BinaryHeapOpenSet)
  - 8 种 Movement 类型 (Traverse, Pillar, Ascend, Descend, Diagonal, Fall, Parkour, Downward)
  - Movement 状态机 (PREPPING → WAITING → RUNNING)
  - CalculationContext 世界快照
  - CachedWorld 区域缓存
  - ToolSet 工具选择
  - InputOverrideHandler 输入模拟
  - Goal 系统 (GoalBlock, GoalXZ, GoalYLevel, GoalComposite)

---

## PlayerEngine (Automatone)

- **仓库**: [Goodbird-git/PlayerEngine](https://github.com/Goodbird-git/PlayerEngine)
- **作者**: Goodbird
- **参考内容**:
  - 服务端 Baritone 适配
  - LivingEntity 接口抽象 (IInventoryProvider, IInteractionManagerProvider)
  - LivingEntityInventory 实体背包
  - LivingEntityInteractionManager 实体交互
  - 线程池设计

---

## Player2NPC

- **仓库**: [Goodbird-git/Player2NPC](https://github.com/Goodbird-git/Player2NPC)
- **作者**: Goodbird
- **参考内容**:
  - 行为链系统 (TaskChain, ChainManager, 9 条链)
  - 优先级调度机制
  - FoodChain 自动进食
  - MobDefenseChain 怪物防御
  - MLGBucketFallChain 水桶防摔
  - UnstuckChain 卡住自救
  - AltoClefController 任务控制器

---

## SiliconeDolls

- **仓库**: [Anvil-Dev/SiliconeDolls](https://github.com/Anvil-Dev/SiliconeDolls)
- **作者**: AnvilCraft 开发团队
- **许可证**: MIT
- **参考内容**:
  - FakePlayer 创建/注册模式 (placeNewPlayer)
  - FakeClientConnection + FakePlayerNetHandler
  - 4 层网络防御 (Connection → Handler → Distributor → CommonListener)
  - PlayerListMixin handler 替换
  - ServerConfigurationPacketListenerImplMixin config 跳过
  - BotInfo / BotPersistence 持久化
  - PlayerActionPack 动作系统
  - FakePlayerAutoFish / AutoReplenish / AutoReplaceTool

---

## RollingGate

- **仓库**: [Anvil-Dev/RollingGate](https://github.com/Anvil-Dev/RollingGate)
- **作者**: AnvilCraft 开发团队
- **许可证**: LGPL-3.0
- **参考内容**:
  - @Rule 注解驱动规则引擎
  - 双层配置（全局 + 世界级）
  - CustomChestMenu 箱子 UI
  - PlanFunction 调度器
  - ModFileScanData 注解扫描

---

## AI-Player

- **仓库**: [shasankp000/AI-Player](https://github.com/shasankp000/AI-Player)
- **作者**: shasankp000
- **许可证**: MIT
- **参考内容**:
  - FakePlayer 创建 (createFakePlayer)
  - GameProfile 持久化 (BotProfileStore)
  - DangerZoneDetector (CliffDetector, LavaDetector)
  - Function Calling (Tool, ToolRegistry)
  - Reinforcement Learning (RLAgent, QTable)
  - BERT/LIDSNet NLP 模型集成

---

## Meteor Client

- **仓库**: [MeteorDevelopment/meteor-client](https://github.com/MeteorDevelopment/meteor-client)
- **作者**: MeteorDevelopment
- **许可证**: GPL-3.0
- **参考内容**:
  - FindItemResult record 模式
  - InvUtils + fluent Action builder
  - BlockUtils (getPlaceSide, canPlace, breakBlock)
  - Rotations 优先级旋转队列
  - BlockIterator 共享世界扫描
  - CustomPlayerInput 程序化输入

---

## EMI (Effectively-Measure-Invoke)

- **仓库**: [EmilyPloszaj/emi](https://github.com/EmilyPloszaj/emi)
- **作者**: EmilyPloszaj
- **许可证**: MIT
- **参考内容**:
  - 两阶段索引构建 (同步 + 后台排序)
  - EmiStack / EmiIngredient 系统
  - Comparison 比较策略 (StrictHashStrategy vs ComparisonHashStrategy)
  - MaterialTree 自动合成树 (递归分解 + 剩余物跟踪)
  - TreeCost 成本计算
  - TagEmiIngredient 标签折叠优化

---

## JEI (Just Enough Items)

- **仓库**: [mezz/JustEnoughItems](https://github.com/mezz/JustEnoughItems)
- **作者**: mezz
- **许可证**: MIT
- **参考内容**:
  - O(1) 配方查找 (IngredientToRecipesMap)
  - UID 系统 (ISubtypeInterpreter + UidContext)
  - 按配方角色分离索引 (INPUT/OUTPUT/CRAFTING_STATION)
  - 插件链 (IRecipeManagerPlugin)
  - ArrayList.trimToSize 后注册压缩

---

## MineColonies

- **仓库**: [ldttteam/minecolonies](https://github.com/ldttteam/minecolonies)
- **作者**: LDT Team
- **许可证**: MIT
- **参考内容**:
  - TickRateStateMachine 状态机架构
  - TickingTransition 均匀 tick 分布

---

*如有遗漏或错误，请提交 Issue 或 PR。*
