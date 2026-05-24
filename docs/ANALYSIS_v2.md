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
| 有效位置 | 无 | `calculateValidPositions()` 精确落脚点 |
| 增量寻路 | 路径走完才算下一个 | 当前路径快结束时自动触发下一段 |
| 路径拼接 | 无 | `trySplice()` + `snipsnapifpossible()` |
| 疾跑优化 | 始终步行 | 平地/下坡智能疾跑 |
| 运行时成本验证 | 无 | 执行中重算，世界变化取消 |
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
| 1 | **ChunkCache 内存爆炸** | `BlockState[][][]` → `int[]` + 2-bit 类型标签 | Baritone CachedChunk |
| 2 | **WorldScanner O(n³)** | 按区块螺旋遍历 + Y 排序 + PalettedContainer 直读 | Baritone FasterWorldScanner |
| 3 | **A* 开放集** | `BinaryHeapOpenSet` 已存在但未集成到 Pathfinder | Baritone BinaryHeapOpenSet |
| 4 | **ChunkCache 无持久化** | 添加 GZIP 磁盘持久化 (r.X.Z.bcr 格式) | Baritone CachedRegion |

### P1 — 路径执行质量

| # | 问题 | 改进方案 |
|---|------|----------|
| 5 | 无有效位置 | `calculateValidPositions()` 精确落脚点 |
| 6 | 无增量寻路 | `nextPath` + `planningTickLookahead` |
| 7 | 无路径拼接 | `trySplice()` + `snipsnapifpossible()` |
| 8 | 无疾跑 | 平地/下坡智能疾跑 |
| 9 | 无运行时成本验证 | 执行中重算, 世界变化取消 |

### P2 — 新增功能改进

| # | 问题 | 建议 |
|---|------|------|
| 10 | PlanCache Jaccard 误匹配 | TF-IDF 或嵌入相似度替代 |
| 11 | PlanCache 无 TTL | 加 expireAfterDays |
| 12 | PlaceBlockAction `level.setBlock()` 直写 | 改用 `gameMode.useItemOn()` 模拟真实放置 |
| 13 | UnstuckDetector PILLAR 跳过砂/砾石但漏混凝土粉 | 检查 `falling` tag |
| 14 | GatherResourceAction 674 行超长 | 拆出 VeinMiningHelper/ObstacleBreaker |
| 15 | GatherResourceAction scanEnvironment 13 次遍历 | 单次多过滤 |
| 16 | `convertCachedToActions` 与 `convertResponseToActions` 重复 | 提取公共 switch |

### P3 — 架构耐久性

| # | 问题 | 建议 |
|---|------|------|
| 17 | 双实体位置同步脆弱 | 统一位置源 |
| 18 | ChainManager 与 BotAIStateMachine 独立 | 预占时通知状态机 → PAUSED |
