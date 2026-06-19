# 全面审计修复测试用例

> 版本: 1.1.13-r113 | 测试范围: P0-P3 全部 55 项修复
> 前置条件: 服务器已启动，已通过 `/ai_bot spawn` 创建至少 1 个 bot

---

## 一、P0 Critical/High 修复测试（6 项）

### TC-P0-01: 行为链抢占机制
| 项目 | 内容 |
|------|------|
| **修复** | ChainManager 添加链抢占：高优先级链可打断低优先级链 |
| **前置** | bot 已存在，附近有怪物和岩浆 |
| **步骤** | 1. 让 bot 饥饿（触发 FoodChain）<br>2. 立即推 bot 到岩浆附近（触发 DangerChain） |
| **预期** | DangerChain（P90）立即抢占 FoodChain（P55），bot 逃离岩浆而不是继续进食 |
| **边界** | bot 进食过程中被怪物攻击 → DefenseChain（P70）也应抢占 FoodChain |

### TC-P0-02: LLM 嵌套 JSON 解析
| 项目 | 内容 |
|------|------|
| **修复** | LLMResponseParser 支持嵌套花括号/方括号 |
| **步骤** | 1. 给 bot 分配复杂任务如 `"craft iron pickaxe and give to me"`<br>2. 观察 LLM 返回的 JSON 是否包含嵌套对象（如 `require_items` 的 `items` 数组） |
| **预期** | 任务正常解析并执行，不会报 "Failed to parse LLM response" |
| **边界** | LLM 返回含嵌套数组的 JSON 如 `{"actions":[{"type":"require_items","items":[{"item":"iron_ingot","count":3}]}]}` 应正确解析 |

### TC-P0-03: 潜行动作完成状态
| 项目 | 内容 |
|------|------|
| **修复** | SneakAction 完成后无条件取消潜行 |
| **步骤** | 1. 执行 `/ai_bot task <bot> sneak`<br>2. 等待 10 秒（MAX_DURATION=200 ticks）<br>3. 观察 bot 是否停止潜行 |
| **预期** | bot 潜行 10 秒后自动站起，不再蹲走 |
| **边界** | 任务被取消时 bot 也应恢复站立状态 |

### TC-P0-04: 卡住恢复 PILLAR 策略
| 项目 | 内容 |
|------|------|
| **修复** | UnstuckDetector PILLAR 在脚下放置方块（而非脚位置） |
| **步骤** | 1. 让 bot 卡在 1 格深的坑中<br>2. 等待 UnstuckChain 升级到 PILLAR 策略 |
| **预期** | bot 在脚下放置方块然后跳起，不会被窒息 |
| **边界** | 头顶有方块时不应尝试 PILLAR |

### TC-P0-05: 战斗/撤退移动速度
| 项目 | 内容 |
|------|------|
| **修复** | DefenseChain/PlayerDefenseChain 移除双重移动（2x 速度） |
| **步骤** | 1. 让 bot 附近生成怪物（触发 DefenseChain）<br>2. 观察 bot 接近怪物的速度<br>3. 观察 bot 低血量撤退的速度 |
| **预期** | 接近速度约 0.2 blocks/tick（正常），撤退约 0.25 blocks/tick，不会过快 |
| **边界** | 盾牌横移也应是正常速度（约 0.15 blocks/tick） |

### TC-P0-06: LLM 异常后状态机恢复
| 项目 | 内容 |
|------|------|
| **修复** | FakePlayer.assignTask catch 块重置状态机 |
| **步骤** | 1. 配置一个无效的 LLM API 地址<br>2. 给 bot 分配任务<br>3. 等待 LLM 调用失败<br>4. 恢复正确的 API 地址<br>5. 再次给 bot 分配任务 |
| **预期** | 第一次失败后 bot 回到 IDLE 状态，第二次可以正常接受任务 |
| **边界** | bot 名字标签应显示正确的状态（不是永远显示 "PLANNING"） |

---

## 二、P1 High 修复测试（14 项）

### TC-P1-01: 合成数量循环
| 项目 | 内容 |
|------|------|
| **修复** | CraftAction 按 count 参数循环合成 |
| **步骤** | 1. 执行 `/ai_bot task <bot> craft 4 oak planks`（确保有原木）<br>2. 观察合成过程 |
| **预期** | bot 合成 4 个木板（1 个原木 = 4 个木板），而不是只合成 1 个 |
| **边界** | 请求 `craft 64 oak planks` 应循环合成直到达到 64 个 |

### TC-P1-02: 不可识别移动类型
| 项目 | 内容 |
|------|------|
| **修复** | BotMovement 工厂对无法识别的移动返回 null |
| **步骤** | 1. 让 bot 在复杂地形中导航<br>2. 观察是否有卡死或异常行为 |
| **预期** | 路径中的异常移动类型会被跳过，bot 使用 moveToward 回退 |

### TC-P1-03: 桥梁放置验证
| 项目 | 内容 |
|------|------|
| **修复** | MovementTraverse PREPPING 验证方块放置成功 |
| **步骤** | 1. 让 bot 需要过一个峡谷<br>2. 清空 bot 背包中的方块<br>3. 让 bot 尝试过峡谷 |
| **预期** | bot 没有方块时标记移动为 FAILED，不会走向虚空 |
| **边界** | 背包有方块时应正常放置桥梁并通过 |

### TC-P1-04: 挖掘过冲保护
| 项目 | 内容 |
|------|------|
| **修复** | MovementDigDown 空气层下落不过冲 |
| **步骤** | 1. 让 bot 挖掘向下 5 格的目标<br>2. 中间有 2 格空气层 |
| **预期** | bot 不会穿过目标深度，到达目标 Y 后停止 |
| **边界** | 多层空气间隙也能正确停止 |

### TC-P1-05: 直接任务分配取消 replan
| 项目 | 内容 |
|------|------|
| **修复** | assignDirectTask 先取消运行中的 replan |
| **步骤** | 1. 给 bot 分配一个会失败的任务<br>2. 在 replan 进行中时，立即用 `/ai_bot task <bot> mine stone` 分配新任务 |
| **预期** | 新任务立即生效，不会被旧 replan 覆盖 |

### TC-P1-06: 最大重试后状态机重置
| 项目 | 内容 |
|------|------|
| **修复** | TaskReplanner 超过最大重试后重置状态机 |
| **步骤** | 1. 给 bot 分配一个不可能完成的任务（如 "mine bedrock"）<br>2. 等待 replan 重试耗尽 |
| **预期** | bot 最终回到 IDLE 状态，名字标签正确显示 FAILED |

### TC-P1-07: 导航到达判断
| 项目 | 内容 |
|------|------|
| **修复** | hasArrived() 添加距离检查 |
| **步骤** | 1. 执行 `/ai_bot task <bot> goto 100 64 100`<br>2. 等待 bot 到达 |
| **预期** | bot 只有在距离目标 2 格以内时才报告到达 |

### TC-P1-08: stopAll 清除状态
| 项目 | 内容 |
|------|------|
| **修复** | ChainManager.stopAll() 清除 lastActiveChain |
| **步骤** | 1. 让 DefenseChain 激活<br>2. 执行 `/ai_bot stop <bot>`<br>3. 让怪物再次接近 |
| **预期** | DefenseChain 可以重新激活（不被 lastActiveChain 阻止） |

### TC-P1-09: 卡住恢复不再取消任务
| 项目 | 内容 |
|------|------|
| **修复** | UnstuckChain SKIP 不再调用 cancelTask() |
| **步骤** | 1. 让 bot 执行一个任务<br>2. 让 bot 卡住直到 SKIP 策略触发 |
| **预期** | bot 停止导航但任务不被取消，任务通过自然失败路径处理 |

### TC-P1-10: FollowAction 行为
| 项目 | 内容 |
|------|------|
| **修复** | 导航逻辑从 isComplete() 移到 execute() |
| **步骤** | 1. 执行 `/ai_bot task <bot> follow <玩家名>`<br>2. 移动玩家，观察 bot 是否跟随 |
| **预期** | bot 正常跟随玩家移动，玩家死亡后 bot 停止跟随 |

### TC-P1-11: 功能开关持久化
| 项目 | 内容 |
|------|------|
| **修复** | toggleFeature 实际保存配置值 |
| **步骤** | 1. 执行 `/ai_bot config toggle autoFish`<br>2. 重启服务器<br>3. 检查 autoFish 配置值 |
| **预期** | 配置值在重启后保持切换后的状态 |

### TC-P1-12: 配置范围限制
| 项目 | 内容 |
|------|------|
| **修复** | ModConfig setter 范围限制 |
| **步骤** | 1. 执行 `/ai_bot config movementSpeed 5.0`（超出范围 0.1-1.0）<br>2. 读取配置值 |
| **预期** | 值被限制为 1.0（上限） |
| **边界** | `/ai_bot config movementSpeed 0.01` 应限制为 0.1（下限） |

### TC-P1-13: 无工作台时镐合成
| 项目 | 内容 |
|------|------|
| **修复** | SequencePlanner 移除工作台前置检查 |
| **步骤** | 1. 给 bot 一些原木，没有工作台<br>2. 执行 `/ai_bot task <bot> craft stone pickaxe` |
| **预期** | bot 自动合成木板→工作台→木镐→挖石头→石头镐，完整流程 |

---

## 三、P2 Medium 修复测试（17 项）

### TC-P2-01: 缓存线程安全
| 项目 | 内容 |
|------|------|
| **修复** | ChunkCache volatile lastChunk/lastChunkKey |
| **步骤** | 1. 多个 bot 同时在不同区域活动<br>2. 观察是否有 "wrong block state" 相关错误 |
| **预期** | 无缓存不一致导致的异常 |

### TC-P2-02: LLM 响应空字段处理
| 项目 | 内容 |
|------|------|
| **修复** | LLMResponseParser null 检查 |
| **步骤** | 1. 配置 LLM 返回不含 content 的响应（如 function-call 格式）<br>2. 给 bot 分配任务 |
| **预期** | 返回 "Missing message/content in response" 错误，不会 NPE |

### TC-P2-03: 模糊匹配阈值
| 项目 | 内容 |
|------|------|
| **修复** | CommandParser 阈值从 3 提高到 5 |
| **步骤** | 1. 使用本地规划器执行模糊命令如 `/ai_bot task <bot> mine i` |
| **预期** | 不会匹配到无关物品，返回 "未知物品" 或更精确的匹配 |

### TC-P2-04: 流式响应错误处理
| 项目 | 内容 |
|------|------|
| **修复** | LLMService 流式错误路径关闭 InputStream |
| **步骤** | 1. 配置 LLM 返回非 200 状态码<br>2. 给 bot 分配任务 |
| **预期** | 错误正确报告，无连接泄漏警告 |

### TC-P2-05: 流式响应大小限制
| 项目 | 内容 |
|------|------|
| **修复** | SSE 流 1MB 大小限制 |
| **步骤** | 1. 正常使用，不应触发限制 |
| **预期** | 正常任务不受影响 |

### TC-P2-06: 数据包阻止日志
| 项目 | 内容 |
|------|------|
| **修复** | PacketDistributorMixin 添加日志 |
| **步骤** | 1. 创建 bot<br>2. 查看服务器日志中的 "PACKET_BLOCKED" 记录 |
| **预期** | 日志中能看到被阻止的数据包类型和目标 bot |

### TC-P2-07: 合成树循环依赖
| 项目 | 内容 |
|------|------|
| **修复** | MaterialTree visited 不再移除已处理项 |
| **步骤** | 1. 执行 `/ai_bot task <bot> craft iron pickaxe`（涉及多层依赖） |
| **预期** | 合成树正确构建，不会无限递归或重复解析 |

### TC-P2-08: bot 移除时资源清理
| 项目 | 内容 |
|------|------|
| **修复** | FakePlayerManager remove 先 cancelTask + AutoFish.remove |
| **步骤** | 1. 创建 bot 并分配任务<br>2. 执行 `/ai_bot remove <bot>`<br>3. 检查服务器日志 |
| **预期** | 任务正确取消，无后台线程残留，AutoFish 实例被清理 |

### TC-P2-09: 移除所有 bot
| 项目 | 内容 |
|------|------|
| **修复** | removeAll 先取消任务再 kill |
| **步骤** | 1. 创建多个 bot 并分配任务<br>2. 执行 `/ai_bot remove_all` |
| **预期** | 所有 bot 正确移除，无异常日志 |

### TC-P2-10: 重复 replan 防护
| 项目 | 内容 |
|------|------|
| **修复** | TaskReplanner AtomicBoolean compareAndSet |
| **步骤** | 1. 让 bot 执行一个会多次失败的任务<br>2. 观察 replan 是否有序进行 |
| **预期** | 不会出现两个 replan 线程同时运行 |

### TC-P2-11: 皮肤下载线程安全
| 项目 | 内容 |
|------|------|
| **修复** | BotSkinManager synchronized(profile) |
| **步骤** | 1. 使用 `/ai_bot spawn <bot> --skin <URL>` 创建带皮肤的 bot |
| **预期** | 皮肤正常应用，无 ConcurrentModificationException |

### TC-P2-12: 缓存清理性能
| 项目 | 内容 |
|------|------|
| **修复** | ChunkCache prune 锁外排序 |
| **步骤** | 1. 让 bot 大范围移动，触发大量区块加载<br>2. 观察服务器 TPS |
| **预期** | 缓存清理时不造成明显卡顿 |

### TC-P2-13: 配方索引并发安全
| 项目 | 内容 |
|------|------|
| **修复** | RecipeIndex 原子交换 |
| **步骤** | 1. 多个 bot 同时执行合成任务 |
| **预期** | 无 "empty recipe index" 错误 |

### TC-P2-14: 木材转换安全
| 项目 | 内容 |
|------|------|
| **修复** | MaterialSubstitute 检查空位再消耗 |
| **步骤** | 1. 给 bot 满背包，只有 1 个原木<br>2. 执行需要木板的合成任务 |
| **预期** | 背包满时不消耗原木（不会丢失物品） |

### TC-P2-15: 同步寻路超时
| 项目 | 内容 |
|------|------|
| **修复** | MineBlockAction/GatherResourceAction 寻路超时 500ms |
| **步骤** | 1. 让 bot 在复杂地形中挖矿<br>2. 观察服务器 TPS |
| **预期** | 寻路不会阻塞服务器超过 500ms |

---

## 四、P3 Low 修复测试（12 项）

### TC-P3-01: 攻击动作简化
| 项目 | 内容 |
|------|------|
| **修复** | AttackAction 移除冗余 null 检查 |
| **步骤** | 1. 执行 `/ai_bot task <bot> attack zombie` |
| **预期** | bot 正常攻击僵尸，行为不变 |

### TC-P3-02: 方块交互简化
| 项目 | 内容 |
|------|------|
| **修复** | InteractBlockAction 移除冗余 null 检查 |
| **步骤** | 1. 执行 `/ai_bot task <bot> interact chest at 100 64 100` |
| **预期** | bot 正常交互方块 |

### TC-P3-03: 破坏方块简化
| 项目 | 内容 |
|------|------|
| **修复** | BreakBlockAction 移除冗余 null 检查 |
| **步骤** | 1. 执行 `/ai_bot task <bot> mine stone` |
| **预期** | bot 正常破坏方块 |

### TC-P3-04: 连锁挖矿简化
| 项目 | 内容 |
|------|------|
| **修复** | VeinMineAction 移除冗余 null 检查 |
| **步骤** | 1. 执行 `/ai_bot task <bot> vein iron_ore` |
| **预期** | bot 正常连锁挖矿 |

### TC-P3-05: 危险链状态清理
| 项目 | 内容 |
|------|------|
| **修复** | DangerChain.stop() 清除 escapeTarget/mlgDeployed |
| **步骤** | 1. 让 bot 掉入岩浆（触发 DangerChain）<br>2. bot 逃离后再次掉入岩浆 |
| **预期** | 第二次也能正常触发 MLG 水桶逃生 |

### TC-P3-06: 食物链状态清理
| 项目 | 内容 |
|------|------|
| **修复** | FoodChain.stop() 清除 lastBot |
| **步骤** | 1. 让 bot 饥饿触发自动进食<br>2. 停止任务<br>3. 再次让 bot 饥饿 |
| **预期** | 第二次也能正常触发自动进食 |

### TC-P3-07: PvP 防御链状态清理
| 项目 | 内容 |
|------|------|
| **修复** | PlayerDefenseChain.stop() 清除 hostilePlayer/activeTicks |
| **步骤** | 1. 启用 PvP 防御<br>2. 让玩家攻击 bot<br>3. 停止任务<br>4. 再次让玩家攻击 bot |
| **预期** | 第二次也能正常触发 PvP 防御 |

### TC-P3-08: 说话动作边界
| 项目 | 内容 |
|------|------|
| **修复** | SayAction execute() 添加 canExecute 检查 |
| **步骤** | 1. 执行 `/ai_bot task <bot> say`（空消息） |
| **预期** | 任务失败，不会广播空消息 |

### TC-P3-09: 使用物品未找到
| 项目 | 内容 |
|------|------|
| **修复** | UseItemAction 物品未找到时 FAILED |
| **步骤** | 1. 执行 `/ai_bot task <bot> use diamond`（背包没有钻石） |
| **预期** | 任务失败，不会使用错误的物品 |

### TC-P3-10: 自动补充尊重最大堆叠
| 项目 | 内容 |
|------|------|
| **修复** | AutoReplenish 尊重 getMaxStackSize() |
| **步骤** | 1. 给 bot 末影珍珠（maxStackSize=16）<br>2. 让 bot 使用到只剩 3 个<br>3. 背包中有额外的末影珍珠 |
| **预期** | 补充到 16 个（而非 32 个） |

### TC-P3-11: 空命令缓存相似度
| 项目 | 内容 |
|------|------|
| **修复** | PlanCache 空字符串相似度返回 1.0 |
| **步骤** | 正常使用即可，此为内部逻辑修正 |
| **预期** | 无异常行为 |

### TC-P3-12: 缓存文件原子写入
| 项目 | 内容 |
|------|------|
| **修复** | PlanCache 原子文件写入 |
| **步骤** | 1. 让 bot 执行多个任务（触发缓存写入）<br>2. 检查 config/aimod/plan_cache.json 文件完整性 |
| **预期** | 缓存文件格式正确，无损坏 |

---

## 五、回归测试（确保原有功能不受影响）

### TC-REG-01: 基础任务执行
| 步骤 | 预期 |
|------|------|
| `/ai_bot task <bot> mine stone` | bot 正常挖石头并返回 |
| `/ai_bot task <bot> craft oak planks` | bot 正常合成木板 |
| `/ai_bot task <bot> goto 100 64 100` | bot 正常移动到目标位置 |
| `/ai_bot task <bot> follow <玩家>` | bot 正常跟随玩家 |

### TC-REG-02: 行为链
| 步骤 | 预期 |
|------|------|
| 让 bot 饥饿 | FoodChain 自动触发进食 |
| 让怪物接近 bot | DefenseChain 触发战斗 |
| 让 bot 掉入岩浆 | DangerChain 触发逃生 |
| 让 bot 卡住 | UnstuckChain 触发恢复 |

### TC-REG-03: 命令系统
| 步骤 | 预期 |
|------|------|
| `/ai_bot spawn test` | 正常创建 bot |
| `/ai_bot remove test` | 正常移除 bot |
| `/ai_bot list` | 正常列出所有 bot |
| `/ai_bot stop <bot>` | 正常停止 bot 任务 |
| `/ai_bot status <bot>` | 正常显示 bot 状态 |

### TC-REG-04: 持久化
| 步骤 | 预期 |
|------|------|
| 创建 bot 并分配任务 | 任务正常执行 |
| 重启服务器 | bot 自动恢复，任务状态正确 |

---

## 测试统计

| 类别 | 用例数 | 优先级 |
|------|--------|--------|
| P0 Critical/High | 6 | 必须通过 |
| P1 High | 13 | 必须通过 |
| P2 Medium | 15 | 应该通过 |
| P3 Low | 12 | 建议通过 |
| 回归测试 | 4 组 | 必须通过 |
| **总计** | **50+** | |

## 测试环境要求

- Minecraft 1.21.1 + NeoForge 21.1.176
- 至少 1 个 bot（通过 `/ai_bot spawn` 创建）
- LLM API 可用（用于测试 LLM 相关用例）
- 服务器日志可查看（用于验证日志输出）
