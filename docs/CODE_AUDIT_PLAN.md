# 代码审计修复计划

> 审计日期: 2026-06-12 | 版本: 1.1.03-r103

## 审计发现汇总

| 严重度 | 数量 | 说明 |
|--------|------|------|
| Critical | 12 | 线程安全、数据损坏、功能错误 |
| High | 15 | 状态机卡住、资源泄漏、逻辑错误 |
| Medium | 25 | 性能、边界情况、配置验证 |
| Low | 22 | 代码质量、冗余代码 |
| **总计** | **74** | |

---

## Phase 1: Critical 线程安全修复

| # | 文件 | 问题 | 工作量 |
|---|------|------|--------|
| 1 | `PlanCache.java` | `invalidate()` 未同步，构造函数未同步 | 小 |
| 2 | `BotMemoryStore.java` | 所有方法无同步，多线程访问 ArrayDeque | 中 |
| 3 | `CraftAction.java` | 输出数量 = recipe * count，但只消耗一次材料 | 中 |

## Phase 2: Critical 功能修复

| # | 文件 | 问题 | 工作量 |
|---|------|------|--------|
| 4 | `DangerChain.java` | 桥梁放置 bug — `pos.relative(facing, 0)` 始终是同一位置 | 小 |
| 5 | `FoodChain.java` | 被抢占时不停止进食/恢复快捷栏 | 小 |
| 6 | `DefenseChain.java` | 配置项被忽略（使用硬编码值） | 小 |

## Phase 3: High 线程安全修复

| # | 文件 | 问题 | 工作量 |
|---|------|------|--------|
| 7 | `TaskReplanner.java` | 后台线程直接修改 Task 对象 | 中 |
| 8 | `TaskReplanner.java`, `FakePlayer.java` | `cancelTask()` 未协调 replan 线程 | 中 |
| 9 | `BotAIStateMachine.java` | getter 方法未同步 | 小 |

## Phase 4: High 功能修复

| # | 文件 | 问题 | 工作量 |
|---|------|------|--------|
| 10 | `TaskReplanner.java` | `incrReplanCount` 未重置、ownerName 为 null、状态机卡 REPLAN | 中 |
| 11 | `LLMService.java` | `parseResponse()` NPE、重复代码 | 小 |
| 12 | `MovementDigDown.java` | 无工具耐久度检查 | 小 |

## Phase 5: High 移动系统修复 ✅

| # | 文件 | 问题 | 工作量 | 状态 |
|---|------|------|--------|------|
| 13 | `MovementPillar.java` | `stuckTicks` 进度时不重置 | 小 | ✅ 放块/跳跃时重置 stuckTicks |
| 14 | `PlaceBlockAction.java` | 副手交换后 stack 引用失效 | 小 | ✅ 交换后重新读取 mainHandItem |
| 15 | `PlayerDefenseChain.java` | retreat 未调用 `bot.move()` | 小 | ✅ 添加 bot.move(SELF, delta) |
| 16 | `GatherResourceAction.java` | Pillar 在 bot 脚下放置方块 | 小 | ✅ 代码已正确，无需修复 |

## Phase 6: Medium 修复

| # | 文件 | 问题 | 工作量 |
|---|------|------|--------|
| 17 | `GatherResourceAction.java` | `tryDigDown` 无深度限制 | 小 |
| 18 | `VeinMiningHelper.java` | 无硬度检查 | 小 |
| 19 | `MovementFall.java` | 残余速度问题 | 小 |
| 20 | `FakePlayer.java` | 死亡状态无检查 | 小 |
| 21 | `NameTagFormatter.java` | `%` 格式化问题 | 小 |
| 22 | `FakePlayer.java` | `pickupNearbyItems` 无节流 | 小 |

---

## 实施顺序

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6
```

每完成一个任务：
1. `./gradlew compileJava` 编译通过
2. `./gradlew test` 测试通过
3. 更新 `gradle.properties` 版本号
4. 更新 `build_info.json`
5. 更新 `README.md` 和 `docs/ANALYSIS_v3.md`
6. Git 提交
