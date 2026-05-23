# AI Mod 代码审查报告与修复指南

> 本文档记录项目审查发现的问题及具体修复方案，供后续会话按文档逐项修复。

---

## 审查概览

- **审查日期**：2026-05-23（更新）
- **项目版本**：Minecraft 1.21.1 + NeoForge 21.1.176
- **源码路径**：src/main/java/com/example/aimod/
- **架构**：混合模式（AIBotEntity extends Mob + 懒加载 FakePlayer extends ServerPlayer）
- **总体评价**：架构清晰，功能完整，已通过编译和 87 个单元测试

---

## 问题清单

| 编号 | 类别 | 严重程度 | 文件 | 状态 |
|------|------|----------|------|------|
| FIX-01 | 线程安全 | 低 | LLMService.java | 已修复（AtomicReference） |
| FIX-02 | 资源管理 | 低 | LLMService.java | 已修复（HTTP/2 共享实例） |
| FIX-03 | 线程安全 | 中 | FakePlayer.java | 待修复（currentTask volatile） |
| FIX-04 | 代码质量 | 低 | AIBotEntity.java | 待修复（重复赋值） |
| FIX-05 | 代码质量 | 低 | WorldScanner.java | 已修复（MAX_RESULTS=16） |
| FIX-06 | 编码 | 低 | zh_cn.json | 已修复（UTF-8 编码正确） |
| FIX-07 | 配置 | 低 | pack.mcmeta | 已验证（pack_format=34） |
| FIX-08 | API 适配 | 中 | 多个文件 | 已修复（1.21.1 API） |
| FIX-09 | 路径系统 | 中 | pathing/ | 已新增（Baritone 风格） |
| FIX-10 | 命令系统 | 中 | command/ | 已新增（16 个子命令 + i18n） |
| FIX-11 | 测试覆盖 | 低 | src/test/ | 已扩展（10 个测试文件，87 个测试） |

---

## 仍需修复的问题

### FIX-03: FakePlayer currentTask 线程安全

**文件**：src/main/java/com/example/aimod/fakeplayer/FakePlayer.java

**问题描述**：
currentTask 字段在多个线程中读写（命令线程赋值，服务器线程读取），但未标记为 volatile。paused 和 parsingTask 已是 volatile。

**修复方案**：
将 currentTask 标记为 volatile：

`java
// 将 private Task currentTask; 改为：
private volatile Task currentTask;
`

**修复位置**：FakePlayer.java 字段声明处

**验证方式**：编译通过

---

### FIX-04: AIBotEntity 重复赋值

**文件**：src/main/java/com/example/aimod/entity/AIBotEntity.java

**问题描述**：
构造函数中 	his.currentTask = null; 赋值了两次（第 44-45 行）。

**修复方案**：
删除重复的赋值语句。

**修复位置**：AIBotEntity.java 构造函数

**验证方式**：编译通过

---

## 已修复问题详情

### FIX-01: LLMService 健康检查线程安全

**文件**：src/main/java/com/example/aimod/ai/llm/LLMService.java

**问题描述**：健康检查缓存在多线程中读写

**修复状态**：已修复

**修复方案**：使用 AtomicReference<HealthCheckResult> 替代 synchronized 块，HealthCheckResult 为不可变类

---

### FIX-02: HttpClient 复用

**文件**：src/main/java/com/example/aimod/ai/llm/LLMService.java

**问题描述**：每次 LLM 调用创建新 HttpClient

**修复状态**：已修复

**修复方案**：使用 static final HttpClient（HTTP/2 共享实例），connectTimeout 10 秒，followRedirects

---

### FIX-05: WorldScanner 性能优化

**文件**：src/main/java/com/example/aimod/ai/WorldScanner.java

**问题描述**：扫描整个立方体区域

**修复状态**：已修复

**修复方案**：
- 添加 MAX_RESULTS = 16 限制
- 使用球形距离检查替代立方体
- 结果按距离排序后截断

---

### FIX-06: zh_cn.json 中文编码

**文件**：src/main/resources/assets/aimod/lang/zh_cn.json

**修复状态**：已修复，文件使用 UTF-8 编码

---

### FIX-07: pack.mcmeta pack_format

**文件**：src/main/resources/pack.mcmeta

**修复状态**：已修复为 pack_format=34

---

### FIX-08: 1.21.1 API 适配

**问题**：多个 API 在 1.21.1 中签名变更

**修复状态**：已修复

**变更内容**：
- isSolidRender() 改为 isSolidRender(BlockGetter, BlockPos)
- EnchantmentHelper.getItemEnchantmentLevel 参数顺序修复
- BlockPos 构造函数不接受 double，需显式 cast int
- Enchantments.SILK_TOUCH 是 ResourceKey 非 Holder，需通过 registryAccess 查找

---

### FIX-09: 路径系统

**问题**：只有基础移动控制，无 A* 寻路

**修复状态**：已新增 Baritone 风格 A* 寻路系统

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

---

### FIX-10: 命令系统

**问题**：命令数量有限，缺少直接命令

**修复状态**：已新增 16 个子命令 + i18n 支持

**新增命令**：
- stop/cancel/pause/resume（任务控制）
- goto（导航到坐标）
- mine（挖掘方块）
- follow（跟随玩家）
- gather（采集资源）
- craft（制作物品）
- say（说话）
- give（给物品）
- equip（装备物品）
- help（帮助）
- task_all（多机器人任务）

---

### FIX-11: 测试覆盖

**问题**：无单元测试

**修复状态**：已新增 10 个测试文件，87 个测试

**测试文件**：
- TaskTest.java — 任务模型测试
- LLMResponseTest.java — 响应模型测试
- LLMResponseParserTest.java — 解析逻辑测试
- LLMResponseParserRobustnessTest.java — 解析器健壮性测试
- LLMServiceImprovementTest.java — LLM 服务改进测试
- BinaryHeapOpenSetTest.java — 二叉堆测试
- MoveCostTest.java — 移动代价测试
- PathNodeTest.java — 路径节点测试
- GoalsTest.java — 目标系统测试
- DevLogTest.java — 日志工具测试

---

## 修复优先级建议

### 第一批（建议立即修复）
1. **FIX-03** currentTask volatile — 潜在运行时问题
2. **FIX-04** 重复赋值 — 代码清洁度

### 第二批（可选优化）
3. 其他已修复项已验证通过

---

## 验证清单

- [x] 项目编译通过（BUILD SUCCESSFUL）
- [x] 87 个单元测试全部通过
- [x] zh_cn.json 编码正确
- [x] pack.mcmeta pack_format=34
- [x] WorldScanner 性能优化（MAX_RESULTS=16）
- [x] 1.21.1 API 适配完成
- [x] 混合架构（AIBotEntity + FakePlayer）正常工作
- [x] Baritone 风格 A* 寻路系统新增
- [x] 16 个子命令 + i18n 支持
- [x] LLMService HttpClient 复用
- [x] LLMService 健康检查线程安全（AtomicReference）
- [ ] FIX-03: currentTask 标记为 volatile
- [ ] FIX-04: AIBotEntity 删除重复赋值
- [ ] 游戏启动正常，无报错
- [ ] /ai_bot spawn 生成假人正常
- [ ] /ai_bot task 挖 3 个铁矿石 任务执行正常
- [ ] /ai_bot status 显示状态正常
- [ ] 简体中文语言下实体名称和命令提示正确显示
- [ ] 多次执行任务后无内存泄漏或连接泄漏