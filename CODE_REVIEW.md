# AI Mod 代码审查报告与修复指南

> 本文档记录项目审查发现的问题及具体修复方案，供后续会话按文档逐项修复。

---

## 审查概览

- **审查日期**：2026-05-23（更新）
- **项目版本**：Minecraft 1.21.1 + NeoForge 21.1.176
- **源码路径**：`src/main/java/com/example/aimod/`
- **架构**：FakePlayer-first（FakePlayer extends ServerPlayer，无 AIBotEntity）
- **总体评价**：架构清晰，功能完整，已通过编译和约 50 个单元测试

---

## 问题清单

| 编号 | 类别 | 严重程度 | 文件 | 状态 |
|------|------|----------|------|------|
| FIX-01 | 线程安全 | 中 | LLMService.java | ⚠️ 待验证（volatile 建议） |
| FIX-02 | 资源管理 | 中 | LLMService.java | ⚠️ 待修复（HttpClient 复用） |
| FIX-03 | 线程安全 | 中 | FakePlayer.java | ⚠️ 待验证（volatile 建议） |
| FIX-04 | 代码质量 | 低 | FakePlayer.java | ✅ 已修复（删除旧文件） |
| FIX-05 | 线程安全 | 低 | AIBotEntity.java | ✅ 不适用（已删除） |
| FIX-06 | 编码 | 低 | zh_cn.json | ✅ 已修复（编码正确） |
| FIX-07 | 配置 | 低 | pack.mcmeta | ✅ 已验证（pack_format=34） |
| FIX-08 | 代码质量 | 低 | WorldScanner.java | ✅ 已修复（MAX_RESULTS=16） |
| FIX-09 | 代码质量 | 低 | 代码注释 | ✅ 已修复（清理编码问题） |
| FIX-10 | API 适配 | 中 | 多个文件 | ✅ 已修复（1.21.1 API） |
| FIX-11 | 架构 | 高 | AIBotEntity | ✅ 已删除（重构为 FakePlayer） |
| FIX-12 | 路径系统 | 中 | pathing/ | ✅ 已新增（Baritone 风格） |
| FIX-13 | 命令系统 | 中 | command/ | ✅ 已新增（16 个子命令 + i18n） |
| FIX-14 | 测试覆盖 | 低 | src/test/ | ✅ 已新增（8 个测试文件） |

---

## 重大架构变更记录 (2026-05-23)

### FakePlayer-first 重构

**重构前**：AIBotEntity extends Mob → 内部持有 FakePlayer
**重构后**：FakePlayer IS the bot — 继承 ServerPlayer，通过 `placeNewPlayer()` 注册到服务器

**变更原因**：
- 参考 SiliconeDolls 的实现方式
- FakePlayer 获得完整玩家能力（背包、合成、交互等）
- 服务器原生支持（追踪、区块加载、事件触发）
- 无需自定义实体渲染器

**删除的文件**：
- `entity/AIBotEntity.java`
- `entity/ModEntities.java`
- `client/renderer/AIBotRenderer.java`
- `client/model/AIBotModel.java`

**新增的文件**：
- `fakeplayer/FakeClientConnection.java`
- `fakeplayer/FakePlayerNetHandler.java`
- `command/DirectCommandHandler.java`
- `ai/llm/LLMResponseParser.java`
- `ai/llm/ActionDescriptor.java`
- `ai/pathing/BinaryHeapOpenSet.java`
- `ai/pathing/MovementHelper.java`
- `ai/pathing/PathExecutor.java`
- `ai/pathing/Pathfinder.java`
- `ai/pathing/PathNode.java`
- `ai/pathing/PathResult.java`
- `ai/pathing/MoveCost.java`
- `ai/pathing/ToolSet.java`
- `ai/pathing/goals/Goal.java`
- `ai/pathing/goals/GoalBlock.java`
- `ai/pathing/goals/GoalComposite.java`
- `ai/pathing/goals/GoalXZ.java`
- `ai/pathing/goals/GoalYLevel.java`
- 测试文件（8 个）

---

## 已修复问题详情

### FIX-06: zh_cn.json 中文编码修复

**问题**：中文字符显示为乱码

**修复状态**：✅ 已修复

**验证**：文件使用 UTF-8 编码，中文字符正确显示

### FIX-07: pack.mcmeta pack_format 修正

**问题**：pack_format 为 15（对应 Minecraft 1.20.2）

**修复状态**：✅ 已修复为 34

**验证**：`pack_format: 34`，`supported_formats: [34, 100]`

### FIX-08: WorldScanner 性能优化

**问题**：findNearbyBlocks 扫描整个立方体区域

**修复状态**：✅ 已修复

**修复方案**：
- 添加 `MAX_RESULTS = 16` 限制
- 使用球形距离检查替代立方体
- 结果按距离排序后截断

### FIX-10: 1.21.1 API 适配

**问题**：多个 API 在 1.21.1 中签名变更
- `isSolidRender()` → `isSolidRender(BlockGetter, BlockPos)`
- `EnchantmentHelper.getItemEnchantmentLevel` 参数顺序反转
- `BlockPos` 构造函数不接受 double（需要显式 cast int）
- `Enchantments.SILK_TOUCH` 是 `ResourceKey<Enchantment>` 非 `Holder<Enchantment>`

**修复状态**：✅ 已修复

### FIX-11: AIBotEntity 架构重构

**问题**：AIBotEntity (PathfinderMob) 与 FakePlayer 分离，导致功能重复

**修复状态**：✅ 已删除 AIBotEntity，重构为 FakePlayer-first 架构

### FIX-12: 路径系统

**问题**：只有基础移动控制，无 A* 寻路

**修复状态**：✅ 已新增 Baritone 风格 A* 寻路系统

**新增组件**：
- Pathfinder（A* 核心算法）
- PathNode（路径节点）
- PathResult（路径结果）
- PathExecutor（路径执行器）
- BinaryHeapOpenSet（二叉堆）
- MovementHelper（移动辅助）
- MoveCost（移动代价常量）
- ToolSet（工具选择）
- Goal 系统（GoalBlock, GoalXZ, GoalYLevel, GoalComposite）

### FIX-13: 命令系统

**问题**：命令数量有限，缺少直接命令

**修复状态**：✅ 已新增 16 个子命令 + i18n 支持

**新增命令**：
- stop/cancel（取消任务）
- pause/resume（暂停/恢复）
- goto（导航到坐标）
- mine（挖掘方块）
- follow（跟随玩家）
- gather（采集资源）
- craft（制作物品）
- say（说话）
- give（给物品）
- equip（装备物品）
- help（帮助）

### FIX-14: 测试覆盖

**问题**：无单元测试

**修复状态**：✅ 已新增 8 个测试文件，约 50 个测试

**测试文件**：
- TaskTest.java — 任务模型测试
- LLMResponseTest.java — 响应模型测试
- LLMServiceParseTest.java — 解析逻辑测试
- BinaryHeapOpenSetTest.java — 二叉堆测试
- MoveCostTest.java — 移动代价测试
- PathNodeTest.java — 路径节点测试
- GoalsTest.java — 目标系统测试
- DevLogTest.java — 日志工具测试

---

## 仍需修复的问题

### FIX-01: LLMService 健康检查线程安全

**文件**：`src/main/java/com/example/aimod/ai/llm/LLMService.java`

**问题描述**：
`cachedHealthOk`、`cachedHealthCheckedAtMs`、`cachedHealthKey` 三个字段在多个线程中读写，虽然使用了 `volatile` 和 `synchronized`，但同步块可能不够完整。

**当前状态**：已使用 `volatile` + `synchronized(HEALTH_CHECK_LOCK)`，基本安全

**建议**：验证在高并发场景下的正确性

### FIX-02: HttpClient 复用

**文件**：`src/main/java/com/example/aimod/ai/llm/LLMService.java`

**问题描述**：
`callLLMApiDirect()` 每次调用都通过 `HttpURLConnection` 创建新连接。虽然 HttpURLConnection 有连接池，但频繁创建仍有开销。

**修复方案**：

```java
// 1. 在类中添加共享的 HttpClient 字段：
private static final HttpClient sharedHttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();

// 2. 使用 HttpClient 替代 HttpURLConnection
// 3. 健康检查保留独立超时配置
```

**修复位置**：LLMService.java 全文重构

**验证方式**：编译通过，多次执行任务后检查无连接泄漏

### FIX-03: FakePlayer volatile 字段

**文件**：`src/main/java/com/example/aimod/fakeplayer/FakePlayer.java`

**问题描述**：
`paused` 和 `parsingTask` 字段已标记为 `volatile`，但 `currentTask` 字段在多个线程中读写（命令线程赋值，服务器线程读取）。

**当前状态**：`paused` 和 `parsingTask` 已是 volatile

**建议**：将 `currentTask` 也标记为 `volatile`，或使用 `AtomicReference<Task>`

```java
// 将第 55 行改为：
private volatile Task currentTask;
```

**修复位置**：FakePlayer.java 第 55 行

**验证方式**：编译通过

---

## 修复优先级建议

### 第一批（建议立即修复）
1. **FIX-02** HttpClient 复用 — 资源浪费
2. **FIX-03** currentTask volatile — 潜在运行时问题

### 第二批（可选优化）
3. **FIX-01** 健康检查线程安全验证 — 已基本安全，验证即可

---

## 验证清单

- [x] 项目编译通过（BUILD SUCCESSFUL）
- [x] 约 50 个单元测试全部通过
- [x] zh_cn.json 编码正确
- [x] pack.mcmeta pack_format=34
- [x] WorldScanner 性能优化（MAX_RESULTS=16）
- [x] 1.21.1 API 适配完成
- [x] FakePlayer-first 架构重构完成
- [x] Baritone 风格 A* 寻路系统新增
- [x] 16 个子命令 + i18n 支持
- [ ] 游戏启动正常，无报错
- [ ] `/ai_bot spawn` 生成假人正常
- [ ] `/ai_bot task chop 3 oak logs` 任务执行正常
- [ ] `/ai_bot status` 显示状态正常
- [ ] 简体中文语言下实体名称和命令提示正确显示
- [ ] 无资源包兼容性警告
- [ ] 多次执行任务后无内存泄漏或连接泄漏
