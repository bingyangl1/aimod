# 代码审计报告 v2

> 审计日期: 2026-06-18 | 版本: 1.1.09-r109 | 范围: 131 个源文件（19,438 行）

## 审计方法

- 6 个并行审计代理，按模块分工扫描全部源文件
- 审计维度：逻辑正确性、线程安全、状态管理、资源管理、边界条件、错误处理、架构合理性
- 与 r105-r109 前次审计对比，本次覆盖了之前未审计的 ~110 个文件

## 发现汇总

| 严重度 | 数量 | 说明 |
|--------|------|------|
| 🔴 Critical | 2 | 必须立即修复：链优先级抢占缺失、LLM 响应解析缺陷 |
| 🟠 High | 15 | 功能错误、卡死、双重移动、状态机不一致 |
| 🟡 Medium | 43 | 边界条件、性能、配置验证、资源管理 |
| 🟢 Low | 38 | 代码质量、冗余、防御性编程 |
| **总计** | **98** | |

## 与前次审计对比

前次审计 (r105-r109) 发现 22 个问题，修复 18 个。本次审计发现 **98 个新问题**（其中 Critical 2 个、High 15 个），覆盖了前次未涉及的大量文件。

---

## 🔴 Critical 级问题（2 个）

### C1. ChainManager 无链抢占机制
- **文件**: `ai/chain/ChainManager.java:48-50`
- **问题**: 一旦某个行为链激活，更高优先级的链无法抢占。例如 FoodChain(55) 激活时，bot 走入岩浆，DangerChain(90) 无法介入。
- **影响**: bot 在进食时被烧死、卡住时被怪物杀死
- **修复**: 在 tick() 中添加抢占检查：如果更高优先级链的 shouldActivate() 为 true，停止当前链并激活更高优先级链

### C2. LLMResponseParser 正则无法解析嵌套 JSON
- **文件**: `ai/llm/LLMResponseParser.java:17`
- **问题**: `ACTIONS_ARRAY_PATTERN` 使用 `[^\]]*` 正则，在遇到嵌套数组（如 `require_items` 的 `items` 字段）时会截断 JSON
- **影响**: 包含嵌套结构的 LLM 响应解析失败，bot 无法执行复杂任务
- **修复**: 替换为 Gson 基于状态机的括号计数器解析

---

## 🟠 High 级问题（15 个）

### H1. SneakAction 完成后不取消潜行
- **文件**: `ai/action/SneakAction.java:38-42`
- **问题**: `sneak=true` 完成后未调用 `bot.setShiftKeyDown(false)`，bot 永久保持潜行状态
- **修复**: 在 COMPLETED 状态时无条件调用 `bot.setShiftKeyDown(false)`

### H2. CraftAction 忽略 count 参数
- **文件**: `ai/action/CraftAction.java:118-150`
- **问题**: 用户请求 "craft 64 planks" 时，只合成一个配方产出，而非 64 个
- **修复**: 循环执行合成直到达到目标数量

### H3. BotMovement 工厂回退创建不可能的移动
- **文件**: `ai/movement/BotMovement.java:129`
- **问题**: 多步跳跃（如 dy=2）无匹配 case，回退创建 MovementTraverse，但 Traverse 的单次跳跃（0.42）无法到达 dy=2
- **修复**: 回退时返回 null 而非创建不可能完成的移动

### H4. MovementTraverse PREPPING 状态未验证方块放置成功
- **文件**: `ai/movement/MovementTraverse.java:61-68`
- **问题**: 放置桥方块后直接进入 RUNNING 状态，未检查是否真的放成功了。无方块时 bot 走向虚空
- **修复**: 检查放置结果，失败时设为 FAILED 状态

### H5. MovementDigDown 穿过气隙时无限下落
- **文件**: `ai/movement/MovementDigDown.java:162-172`
- **问题**: 挖掘过程中遇到空气层，bot 持续下落穿过目标深度，永远无法完成
- **修复**: 添加过冲检查：`if (bot.getY() <= dest.getY())` 时立即完成

### H6. UnstuckDetector PILLAR 策略在 bot 脚下放置方块
- **文件**: `ai/movement/UnstuckDetector.java:150-155`
- **问题**: PILLAR 恢复在 `bot.blockPosition()` 放置方块（bot 所在位置），导致 bot 被窒息
- **修复**: 改为 `bot.blockPosition().below()`

### H7-H9. DefenseChain 双重移动（3 处）
- **文件**: `ai/chain/DefenseChain.java:116-118, 134-137, 155-156`
- **问题**: 同时调用 `setDeltaMovement()` 和 `move()`，bot 以双倍速度移动（近战 0.4 而非 0.2，撤退 0.5 而非 0.25）
- **修复**: 只使用 `move()` 进行即时移动

### H10. PlayerDefenseChain 双重移动
- **文件**: `ai/chain/PlayerDefenseChain.java:148-149`
- **问题**: 同 H7-H9，撤退速度翻倍且速度在后续 tick 持续
- **修复**: 只使用 `move()`

### H11. FakePlayer LLM 线程异常后状态机卡在 PLANNING
- **文件**: `fakeplayer/FakePlayer.java:499-501`
- **问题**: `assignTask()` 中 LLM 解析异常时，catch 块未重置状态机，bot 永久不响应新任务
- **修复**: catch 块中添加 `stateMachine.reset()`

### H12. assignDirectTask 未取消运行中的 replan
- **文件**: `fakeplayer/FakePlayer.java:515-536`
- **问题**: 直接分配任务时未调用 `cancelReplan()`，后台 replan 线程可能覆盖新任务
- **修复**: 在 `assignDirectTask()` 开头添加 `cancelReplan()`

### H13. TaskReplanner 最大重试后状态机不重置
- **文件**: `ai/TaskReplanner.java:65-72`
- **问题**: 超过 MAX_INCR_REPLAN 时任务设为 FAILED，但状态机停留在 REPLAN 状态
- **修复**: 添加 `stateMachine.reset()`

### H14. Mixin disconnect() 在配置阶段被调用
- **文件**: `mixin/ServerConfigurationPacketListenerImplMixin.java:29-33`
- **问题**: FakePlayer 的配置完成事件触发完整的 disconnect() 流程，可能导致 NPE
- **修复**: 用最小化的 no-op 替代 disconnect()

### H15. ConnectionMixin 暴露 Channel 字段替换方法
- **文件**: `mixin/ConnectionMixin.java:16-18`
- **问题**: `aimod$setChannel()` 允许任意代码替换 Connection 的 Channel，存在安全风险和数据竞争
- **修复**: 移除公共 setter，限制为特定创建上下文

---

## 🟡 Medium 级问题（43 个，按类别分组）

### 状态一致性（8 个）
| # | 文件 | 问题 |
|---|------|------|
| M1 | `MovementController:222` | `hasArrived()` 在导航失败时也返回 true |
| M2 | `TaskReplanner:171-178` | server.execute catch 块未重置状态机 |
| M3 | `TaskReplanner:256` | replan 无结果时状态机不重置 |
| M4 | `FakePlayer:458-509` | parsingTask 重置与 server.execute 赋值之间的竞态 |
| M5 | `ChainManager:84-89` | `stopAll()` 不清除 `lastActiveChain` |
| M6 | `UnstuckChain:44-48` | SKIP 策略取消任务，与 DangerChain 自动重试冲突 |
| M7 | `FollowAction:41-72` | `isComplete()` 有副作用（触发导航），违反方法契约 |
| M8 | `CommandParser:53` | 未知命令默认回退到 CRAFT，导致错误意图 |

### 线程安全（5 个）
| # | 文件 | 问题 |
|---|------|------|
| M9 | `BotSkinManager:65-113` | 从 ForkJoinPool 线程修改 GameProfile 属性 |
| M10 | `TaskReplanner:237-267` | `checkDeficitsAndReplan` 未原子化检查 replanning 标志 |
| M11 | `RecipeIndex:47-117` | `build()` 清空并重建 map，并发读取看到空状态 |
| M12 | `ChunkCache:83-84` | `lastChunk` 空间局部性缓存非线程安全 |
| M13 | `ChunkCache:163-184` | `prune()` 持锁期间排序，阻塞其他线程 |

### 资源管理（6 个）
| # | 文件 | 问题 |
|---|------|------|
| M14 | `MineBlockAction:181-182` | 同步寻路阻塞服务器 tick 线程 |
| M15 | `GatherResourceAction:366-367` | 同 M14 |
| M16 | `FakePlayerManager:106-113` | `removeFakePlayer` 不取消任务就 kill |
| M17 | `FakePlayerManager:119-128` | `removeAll` 不取消任务、不清理持久化文件 |
| M18 | `AutoFish:33` | 静态 instances map 永不清理，内存泄漏 |
| M19 | `LLMService:462-470` | 流式响应错误路径未关闭 InputStream |

### LLM 解析（3 个）
| # | 文件 | 问题 |
|---|------|------|
| M20 | `LLMResponseParser:27` | 缺少 message/content 字段时 NPE |
| M21 | `LLMService:539-576` | SSE 流读取器 StringBuilder 无大小限制，OOM 风险 |
| M22 | `CommandParser:86-106` | 模糊匹配阈值过低（3），误匹配率高 |

### 移动/物理（4 个）
| # | 文件 | 问题 |
|---|------|------|
| M23 | `MovementClimb:96-101` | 爬梯使用固定速度，忽略重力，可能脱落 |
| M24 | `MovementController:81` | `moveToward` 魔法常量 4.317 无解释 |
| M25 | `MovementPillar:57` | 完成检查使用 getY() 比较，可能提前触发 |
| M26 | `MovementFall:118` | 落地检查要求 onGround()，水中永远不触发 |

### 配置/命令（3 个）
| # | 文件 | 问题 |
|---|------|------|
| M27 | `BotCommandConfig:76` | `toggleFeature` 不实际保存切换值 |
| M28 | `ModConfig:352` | 运行时 setter 绕过 NeoForge 范围限制 |
| M29 | `SequencePlanner:75-93` | 无工作台时跳过镐合成，后续挖掘失败 |

### 合成/配方（4 个）
| # | 文件 | 问题 |
|---|------|------|
| M30 | `MaterialSubstitute:119-149` | `autoConvert` 就地修改背包，无事务安全 |
| M31 | `MaterialTree:81` | visited set 提前删除，允许循环重入 |
| M32 | `VeinScanner:62-92` | BFS 无边界限制，大树可能遍历数千位置 |
| M33 | `BotMemoryStore:36` | `exploredChunks` 无大小限制 |

### Mixin/安全（3 个）
| # | 文件 | 问题 |
|---|------|------|
| M34 | `PacketDistributorMixin:29-31` | 全面取消 FakePlayer 数据包，破坏服务端可见性 |
| M35 | `EquipItemAction:79-81` | 旧装备替换时背包满则丢失 |
| M36 | `GiveItemAction:88-89` | 条件 drop 逻辑可能引用错误的 ItemStack |

### 路径规划（3 个）
| # | 文件 | 问题 |
|---|------|------|
| M37 | `Pathfinder:248-256` | nodeMap 无上限，最差 ~19MB/请求 |
| M38 | `Pathfinder:185-246` | 多格下落只检查四方向，缺对角线 |
| M39 | `AsyncPathfinder:49-66` | 无并发寻路线程上限 |

### 其他（4 个）
| # | 文件 | 问题 |
|---|------|------|
| M40 | `AutoReplaceTool:32` | 固定耐久度阈值而非百分比 |
| M41 | `TaskFeedback:50-51` | `sendToOwner` 未检查 bot level 是否为 null |
| M42 | `FollowAction:61` | bot 与玩家重叠时 normalize() 零向量 |
| M43 | `MovementTraverse:104-109` | 游泳速度可将 bot 推入实心方块 |

---

## 🟢 Low 级问题（38 个，摘要）

| 类别 | 数量 | 典型问题 |
|------|------|----------|
| 冗余 null 检查 | 4 | AttackAction/InteractBlockAction/BreakBlockAction/VeinMineAction 中 `FakePlayer fakePlayer = bot; if (fakePlayer != null)` 永远为 true |
| 状态泄漏 | 5 | DangerChain/FoodChain/PlayerDefenseChain/DefenseChain 的 stop() 未完全清理状态 |
| 不安全类型转换 | 3 | ObstacleBreaker/MineBlockAction/GatherResourceAction 未做 instanceof 检查 |
| 死代码 | 3 | LLMService.readSSEStream(String)、防御链中的冗余冷却写入 |
| 物理精度 | 2 | MovementDiagonal/MovementDescend 的速度计算不精确 |
| 缓存/性能 | 4 | WorldScanner 重复解析方块 ID、BotMemoryStore 过度磁盘 I/O |
| 合成逻辑 | 3 | RecipeIndex 假设所有替代品数量相同、MaterialTree 总选第一个匹配项 |
| 其他 | 14 | SayAction 空消息、UseItemAction 未找到物品仍使用、PlanCache 空字符串相似度等 |

---

## 修复优先级建议

### 立即修复（P0）
1. **C1 ChainManager 抢占** — bot 生死攸关
2. **C2 LLMResponseParser 嵌套 JSON** — 影响复杂任务解析
3. **H1 SneakAction** — bot 永久潜行
4. **H6 UnstuckDetector PILLAR** — bot 自杀
5. **H7-H10 双重移动** — 4 处相同模式，批量修复
6. **H11 状态机卡 PLANNING** — bot 变砖

### 短期修复（P1）
7. **H2 CraftAction count** — 功能缺失
8. **H3-H5 移动系统** — 卡死/过冲
9. **H12-H13 状态机不重置** — 多处相同模式
10. **M1-M8 状态一致性** — 系统性问题
11. **M27 toggleFeature** — 配置完全无效

### 中期修复（P2）
12. **M9-M13 线程安全** — 5 个竞态条件
13. **M14-M19 资源管理** — 同步阻塞、内存泄漏
14. **M20-M22 LLM 解析** — 健壮性
15. **M30-M33 合成系统** — 边界条件

### 长期改进（P3）
16. **Low 级问题** — 代码质量提升
17. **M34-M36 Mixin 安全** — 防御性编程
18. **M37-M39 路径规划** — 性能优化

---

## 架构建议

1. **状态机重置应集中管理**: 多处 catch 块遗漏 `stateMachine.reset()`，建议使用 try-finally 包装
2. **行为链 stop() 应有统一的清理模板**: 每个链的 stop() 清理不一致，建议基类提供模板方法
3. **move() vs setDeltaMovement() 应统一**: 4 处相同的双重移动 bug，说明缺少移动工具方法
4. **后台线程任务取消应协调**: assignTask/assignDirectTask/cancelTask/cancelReplan 之间的交互复杂，建议使用统一的任务生命周期管理器
5. **Mixin 注入应更精确**: 当前的 PacketDistributor 和 Connection 注入范围过广
