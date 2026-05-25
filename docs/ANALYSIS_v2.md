# 项目审阅分析 v2 — 差异、优缺点、改进方向

## 本次变动总览（r47-r54，10 次提交）

| 维度 | 变更 | 文件 |
|------|------|------|
| **LLM 缓存** | 新增 `PlanCache`，本地持久化 LLM 计划，Jaccard 相似度匹配 | `PlanCache.java` (new), `BotAIManager.java` |
| **位置保持** | `travel()` 不再清零 XZ 速度，只覆写重力 | `FakePlayer.java` |
| **脱困增强** | 新增 PILLAR 策略：脚下搭柱 + 跳出坑 | `UnstuckDetector.java` |
| **放置回退** | 目标→下方→附近→脚下+搭柱 6 次尝试链 | `PlaceBlockAction.java` |
| **采集增强** | 半径渐进扩张(32→128)、障碍物挖掘、头顶搭柱、连锁砍树 | `GatherResourceAction.java` |
| **空闲采集** | 检查背包存量后再采集，600tick 冷却，满 16 停止 | `FakePlayer.java` |
| **增量重规划** | 跳过已失败的重复动作 | `BotAIManager.java` |
| **LLM 解析** | 接受裸单动作对象（非仅 `{"actions":[...]}`） | `LLMResponseParser.java` |
| **清理** | 移除 orphans：`tryPlaceAtFeet`、`.bak`、参考项目 | `InteractBlockAction.java`, git rm |

## 一、与参考项目关键差距

### 1. 移动系统 vs Baritone

| 维度 | aimod | Baritone |
|------|-------|----------|
| A* 开放集 | `PriorityQueue`（`remove()` O(n)） | `BinaryHeapOpenSet`（`update()` O(log n)） |
| 有效位置 | ✅ `calculateValidPositions()` 精确落脚点 | `calculateValidPositions()` 精确落脚点 |
| 增量寻路 | ✅ `nextPath` + `planningTickLookahead` | 当前路径快结束时自动触发下一段 |
| 路径拼接 | ✅ `trySplice()` + `snipsnapifpossible()` | `trySplice()` + `snipsnapifpossible()` |
| 疾跑优化 | ✅ 平地/下坡智能疾跑 | 平地/下坡智能疾跑 |
| 运行时成本验证 | ✅ 执行中重算，世界变化取消 | 执行中重算，世界变化取消 |
| MovementHelper | 布尔值 | 三值逻辑 YES/MAYBE/NO |

### 2. ChunkCache vs Baritone CachedChunk

| 维度 | aimod | Baritone |
|------|-------|----------|
| 存储 | `BlockState[384][16][16]` (~917KB/区块) | 2-bit BitSet 压缩 (~16KB/区块) |
| 总内存 | ~1.8 GB (2048 区块) | ~32 MB |
| 持久化 | 无 | GZIP 存 `r.X.Z.bcr` 文件 |
| 高度图 | 无 | 每列最高方块 |
| 特殊方块索引 | 无 | 箱子/刷怪笼位置索引 |

### 3. WorldScanner vs Baritone

| 维度 | aimod | Baritone |
|------|-------|----------|
| 扫描方式 | `BlockPos.betweenClosed()` O(n³) | 按区块 + PalettedContainer 直读 long[] |
| Y 优化 | 全部检查 | 按 Y 接近度排序截面 |
| 并行 | 单线程 | FasterWorldScanner 用 `parallelStream()` |

## 二、aimod 独有优势（参考项目没有的）

### 1. LLM 集成（唯一）
- 健康检查 + 指数退避重试 + 滑动窗口限流 + 流式 SSE
- 3 层解析后备：标准 JSON → 宽松数组 → 单动作对象
- **PlanCache**：本地持久化，Jaccard 相似度匹配，跳过重复 API 调用
- `CommandParser` 本地后备：中英文动词 + 模糊物品匹配

### 2. BehaviorChain 优先级系统
```
DangerChain   (90) → 熔岩/火/溺水/跌落 → 预占 AI
DefenseChain  (70) → 敌对生物 → 战斗
FoodChain     (55) → 饥饿 < 14 → 自动进食
UnstuckChain  (50) → 卡住 → 分级恢复(含 PILLAR)
```
预占模型（priority > 50 暂停主任务）参考项目独一无二。

### 3. MaterialTree + SequencePlanner
自然语言 "造一把钻石剑" → 完整动作序列（含工具前置/熔炼/清理）。

### 4. RecipeIndex Tag+NBT 双索引
同时支持 Tag 展开和 NBT 感知 UID（附魔书/药水）。

## 三、改进方向（优先级排序）

### P0 — 基础设施性能

| # | 问题 | 改进方案 | 参考 |
|---|------|----------|------|
| 1 | ✅ **ChunkCache 内存爆炸** | `BlockState[][][]` → `int[]` + 2-bit 类型标签 | Baritone CachedChunk |
| 2 | ✅ **WorldScanner O(n³)** | 按区块螺旋遍历 + Y 排序 + PalettedContainer 直读 | Baritone FasterWorldScanner |
| 3 | ✅ **A* 开放集** | `BinaryHeapOpenSet` 已存在但未集成到 Pathfinder | Baritone BinaryHeapOpenSet |
| 4 | ✅ **ChunkCache 无持久化** | 添加 GZIP 磁盘持久化 (r.X.Z.bcr 格式) | Baritone CachedRegion |

### P1 — 路径执行质量

| # | 问题 | 改进方案 |
|---|------|----------|
| 5 | ✅ 无有效位置 | `calculateValidPositions()` 精确落脚点 |
| 6 | ✅ 无增量寻路 | `nextPath` + `planningTickLookahead` |
| 7 | ✅ 无路径拼接 | `trySplice()` + `snipsnapifpossible()` |
| 8 | ✅ 无疾跑 | 平地/下坡智能疾跑 |
| 9 | ✅ 无运行时成本验证 | 执行中重算, 世界变化取消 |

### P2 — 新增功能改进

| # | 问题 | 建议 |
|---|------|------|
| 10 | ✅ PlanCache Jaccard 误匹配 | TF-IDF 加权 + 中文字符支持 + N-gram 奖励 |
| 11 | ✅ PlanCache 无 TTL | 加 expireAfterDays (7天) |
| 12 | ✅ PlaceBlockAction `level.setBlock()` 直写 | 改用 `gameMode.useItemOn()` 模拟真实放置 |
| 13 | ✅ UnstuckDetector PILLAR 跳过砂/砾石但漏混凝土粉 | `instanceof FallingBlock` 通用检测 |
| 14 | ✅ GatherResourceAction 674 行超长 | 拆出 VeinMiningHelper (34行) + ObstacleBreaker (66行) → 主文件 516 行 |
| 15 | ✅ GatherResourceAction scanEnvironment 13 次遍历 | 单次多过滤 `findNearbyBlocksBatched()` |
| 16 | ✅ `convertCachedToActions` 与 `convertResponseToActions` 重复 | 提取 `parseActionFromJson()` 公共方法 |

### P3 — 架构耐久性

| # | 问题 | 建议 |
|---|------|------|
| 17 | ✅ 双实体位置同步脆弱 | FakePlayer 权威 → AIBotEntity 跟随 |
| 18 | ✅ ChainManager 与 BotAIStateMachine 独立 | 预占时通知状态机 → PAUSED，取消时 reset |

---

## 三、后续发现 Bug 修复 (r55)

### B1 — GameTest 服务器崩溃
- **症状**: `ResourceLocationException: gametestregistry.aimod:empty` — 路径含 `:`
- **原因**: NeoForge 21.1.176 的 `event.register(GameTestRegistry.class)` 自动生成 batch 名 `classname.modid:template` 含 `:`，`ResourceLocation.parse()` 拒绝路径中的冒号
- **修复**: 从 `build.gradle` 移除 `forge.enabledGameTestNamespaces`，GameTestRegistry 保留为桩类，JUnit 134 测试为主测试方案

### B2 — 近距离采集走很远
- **症状**: 垫脚方块需求1块，bot 扫描128格远去找
- **原因**: `GatherResourceAction` 初始 radius=32，渐进扩张至128，对1块土也走很远
- **修复**: 
  - `count≤2 → searchRadius≤24`，`count≤8 → ≤48`，`max≤64`
  - `INITIAL_SEARCH_RADIUS=16`，`EXPAND_STEP=16`

### B3 — WorldScanner 提前返回排序不正确
- **症状**: `findNearbyBlocksBatched()` 达到 16 结果即提前返回，只排了部分 chunk 的结果，可能漏掉更近的方块
- **原因**: chunk 按 raster 顺序(min→max)遍历，非距离优先；早期满 16 即截断+排序
- **修复**: 
  - 去掉提前返回，全扫描后统一排序
  - chunk 按距离 bot 排序（螺旋向外）
  - `MAX_RESULTS=16→64`

### B4 — Task 永远不完成
- **症状**: 任务在 EXECUTING 状态停滞，动作不推进
- **原因**:
   - `PREEMPT_THRESHOLD=50→65`：FoodChain(P55) 不再抢占，与 task 共存
   - 破坏进度改用 `System.currentTimeMillis()` 实时时间，不受 tick 频率影响
   - DefenseChain 加 `MAX_ACTIVE_TICKS=120` + `POST_COMBAT_COOLDOWN=40`

## 四、运行时 Bug 修复 (r58)

### B5 — VeinMineAction 空矿脉无限旋转
- **症状**: 日志 `[VEIN_STUCK] skipping=$(pos)` 后，task 0/1 永久 IN_PROGRESS，动作永不推进
- **原因**: VeinMine 唯一个矿脉块卡住被 skip → `veinBlocks` 为空 → Phase3 跳过（空集合）→ 再无代码将状态设为 COMPLETED/FAILED → execute() 每 tick 空转
- **修复**: 
  - VEIN_SCANNED 后检查 veinBlocks 为空 → 立即 FAILED
  - VEIN_STUCK skip 后 veinBlocks 变空 → 立即 FAILED

### B6 — FEEDBACK_SENT 风暴
- **症状**: 日志 30+ 条 `[FEEDBACK_SENT] ... Action failed` 在 400ms 内刷屏
- **原因**: Action FAILED 后触发 `incrementalReplan()`，LLM 请求 30s。每 tick `executeTask()` 检查 `isComplete()==true` → `reportActionFailed()` 重发。`replanning=true` 防止了重复 `incrementalReplan` 调用，但 `reportActionFailed` 没有防护
- **修复**: `reportActionFailed` + `incrementalReplan` 外包裹 `if (!replanning)` 防止重入
