# AI Mod 代码审查报告

> 本文档记录项目审查发现的问题及修复状态。

---

## 审查概览

- **审查日期**：2026-05-23（更新）
- **项目版本**：Minecraft 1.21.1 + NeoForge 21.1.176
- **源码路径**：`src/main/java/com/example/aimod/`
- **架构**：FakePlayer-first（FakePlayer extends ServerPlayer，无 AIBotEntity）
- **总体评价**：架构清晰，功能完整，已通过编译和 16 个单元测试

---

## 问题清单

| 编号 | 类别 | 严重程度 | 文件 | 状态 |
|------|------|---------|------|------|
| FIX-01 | 线程安全 | 中 | LLMService.java | ⚠️ 待验证（volatile 建议） |
| FIX-02 | 资源管理 | 中 | LLMService.java | ⚠️ 待修复（HttpClient 复用） |
| FIX-03 | 线程安全 | 中 | FakePlayer.java | ⚠️ 待验证（volatile 建议） |
| FIX-04 | 代码质量 | 低 | FakePlayer.java | ✅ 已修复（删除旧文件） |
| FIX-05 | 线程安全 | 低 | AIBotEntity.java | ✅ N/A（已删除） |
| FIX-06 | 编码 | 低 | zh_cn.json | ⚠️ 待修复（编码问题） |
| FIX-07 | 配置 | 低 | pack.mcmeta | ✅ 已验证 |
| FIX-08 | 代码质量 | 低 | WorldScanner.java | ⚠️ 待优化（MAX_RESULTS 建议） |
| FIX-09 | 代码质量 | 低 | 代码注释 | ⚠️ 部分文件有编码问题 |
| FIX-10 | API 适配 | 中 | 多个文件 | ✅ 已修复（1.21.1 API） |
| FIX-11 | 架构 | 高 | AIBotEntity | ✅ 已删除（重构为 FakePlayer） |
| FIX-12 | 路径系统 | 中 | pathing/ | ✅ 已新增（Baritone 风格） |

---

## 重大架构变更记录 (2026-05-23)

### FakePlayer-first 重构

**Before**：AIBotEntity extends Mob → holds FakePlayer internally
**Now**：FakePlayer IS the bot — extends ServerPlayer, registered with server via `placeNewPlayer()`

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
- `ai/pathing/BinaryHeapOpenSet.java`
- `ai/pathing/MovementHelper.java`
- `ai/pathing/PathExecutor.java`
- `ai/pathing/ToolSet.java`
- `ai/pathing/goals/Goal.java`
- `ai/pathing/goals/GoalBlock.java`
- `ai/pathing/goals/GoalComposite.java`
- `ai/pathing/goals/GoalXZ.java`
- `ai/pathing/goals/GoalYLevel.java`

---

## 已修复问题详情

### FIX-10: 1.21.1 API 适配（本次修复）

**问题**：多个 API 在 1.21.1 中签名变更
- `isSolidRender()` → `isSolidRender(BlockGetter, BlockPos)`
- `EnchantmentHelper.getItemEnchantmentLevel` 参数顺序反转
- `BlockPos` 构造函数不接受 double（需要显式 cast int）
- `Enchantments.SILK_TOUCH` 是 `ResourceKey<Enchantment>` 非 `Holder<Enchantment>`

**修复**：
- `GatherResourceAction.java` — 使用 `isSolidRender(level, pos)`
- `ToolSet.java` — 通过 `registryAccess().lookupOrThrow()` 获取 Holder
- `MineBlockAction.java` — `navigateTo()` 参数修复
- `GatherResourceAction.java` — BlockPos 直接使用 nextWp
- UTF-8 编码清理（U+FFFD 替换字符）

---

## 仍需修复的问题

### FIX-01: LLMService 健康检查线程安全
- `healthy`, `lastHealthCheckTime`, `lastHealthCheckResult` 应标记为 `volatile`
- 影响：低（主要在主线程操作）

### FIX-02: HttpClient 复用
- `callLLM()` 每次创建新 HttpClient（连接池浪费）
- 建议：共享实例，健康检查保留独立超时

### FIX-06: 语言文件编码
- `zh_cn.json` 部分条目有乱码
- 不影响功能，影响用户体验

### FIX-08: WorldScanner 性能
- `findNearbyBlocks` 扫描整个立方体区域
- 建议：添加 `MAX_RESULTS = 64` 限制

---

## 验证清单

- [x] 项目编译通过（BUILD SUCCESSFUL）
- [x] 16 个单元测试全部通过
- [ ] 游戏启动正常，无报错
- [ ] `/ai_bot spawn` 生成假人正常
- [ ] `/ai_bot task chop 3 oak logs` 任务执行正常
- [ ] `/ai_bot status` 显示状态正常
- [ ] 简体中文语言下实体名称和命令提示正确显示
- [ ] 无资源包兼容性警告
- [ ] 多次执行任务后无内存泄漏或连接泄漏