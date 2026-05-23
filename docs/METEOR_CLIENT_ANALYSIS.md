# Meteor Client 项目分析报告

> 参考项目：https://github.com/MeteorDevelopment/meteor-client (Fabric/NeoForge 外挂端)
> 分析日期：2026-05-23
> 分析目的：提取战斗AI、背包管理、方块交互等自动化逻辑

---

## 项目概况

Meteor Client 是 Minecraft 最知名的外挂端之一，以模块化设计著称。
虽然用途不同（PVP外挂 vs AI助手），但其自动化逻辑高度可复用。

**核心价值**：Meteor 的 KillAura、AutoEat、BlockUtils、InvUtils 等模块
本质上就是"自动化的玩家行为"——与我们的 AI Bot 需求完全一致。

---

## 可借鉴的模式

### ⭐⭐⭐ 高价值

#### 1. IPathManager 路径管理接口
文件：pathing/IPathManager.java

Meteor 定义了清晰的路径管理接口，支持插件化替换实现：

```java
public interface IPathManager {
    String getName();
    boolean isPathing();
    void pause();
    void resume();
    void stop();
    void moveTo(BlockPos pos, boolean ignoreY);
    void moveInDirection(float yaw);
    void mine(Block... blocks);
    void follow(Predicate<Entity> entity);
    float getTargetYaw();
    float getTargetPitch();
    ISettings getSettings(); // walkOnWater, walkOnLava, step, noFall
}
```

还有 NopPathManager（空实现）作为默认。

**借鉴价值**：我们的 PathExecutor 应该抽象为接口，支持不同寻路实现切换。
当前直接耦合在 Action 中，不利于测试和替换。

#### 2. KillAura 战斗系统
文件：systems/modules/combat/KillAura.java (19KB)

极其成熟的战斗逻辑：

**目标筛选**：
- 实体类型过滤（玩家/怪物/动物/全部）
- 距离范围（可视范围/穿墙范围）
- 忽略命名实体、已驯服实体、被动生物
- 创造模式玩家、好友白名单
- 幼崽/成体过滤

**目标排序**（SortPriority 枚举）：
- Closest / LowestHealth / HighestHealth / LowestDistance / HighestDistance

**攻击策略**：
- TPS 同步（根据服务器TPS调整攻击间隔）
- 攻击冷却（使用原版攻击蓄力机制）
- 自动切换武器（找到最佳武器后切换，攻击完切回）
- 盾牌破防（检测目标持盾 → 切换斧头破盾）
- 多目标模式（按顺序攻击多个目标）

**旋转控制**：
- Always — 始终面向目标
- OnHit — 仅攻击时旋转
- None — 不旋转

**借鉴到我们**：
```java
// 新增 TargetUtils 工具类
public class TargetUtils {
    // 筛选可攻击实体（参考 Meteor 的 isValidTarget）
    public static List<LivingEntity> findTargets(FakePlayer bot, double range, TargetFilter filter) { ... }
    
    // 排序（最近/最低血/最高血）
    public static LivingEntity sortByPriority(List<LivingEntity> targets, SortPriority priority) { ... }
    
    // 判断目标是否持盾
    public static boolean isBlocking(LivingEntity entity) { ... }
}
```

#### 3. InvUtils 背包工具
文件：utils/player/InvUtils.java (10KB)

**查找系统**：
```java
// 按条件查找物品（返回 slot + count）
find(Predicate<ItemStack> isGood)
findInHotbar(Predicate<ItemStack> isGood)
findEmpty()
find(Item... items)

// 查找最佳挖掘工具
findFastestTool(BlockState state) // 考虑工具类型、挖掘速度
```

**交换系统**：
```java
// 切换快捷栏
swap(int slot, boolean swapBack)

// 流式 API 容器操作
click().from(x).to(y)         // 移动物品
quickMove().from(x)           // Shift+点击
quickSwap().from(x).to(y)     // 交换
shiftClick().from(x)          // 快速转移
drop().from(x)                // 丢弃
```

**借鉴到我们**：
我们的 InventoryUtils 比较粗糙。应参考 InvUtils 重构：
- `findBestTool(BlockState)` — 考虑附魔和效率
- `swapTo(slot, swapBack)` — 支持切换后切回
- 流式 API 简化容器操作

#### 4. BlockUtils 方块交互
文件：utils/world/BlockUtils.java (17KB)

**放置系统**：
```java
// 智能放置（自动找最佳放置面）
place(BlockPos pos, FindItemResult item, boolean rotate, int priority, boolean checkEntities)

// 放置面检测
getPlaceSide(BlockPos pos)  // 检查6个面哪个可放置

// 判断是否可以放置
canPlaceBlock(BlockPos pos, boolean checkEntities, Block block)
```

**挖掘速度计算**：
```java
// 考虑工具、附魔、药水效果、水下、不在地面等
getBreakDelta(int slot, BlockState state)
getDestroySpeed(int slot, BlockState block)
```

**暴露检测**：
```java
// 检查方块是否至少有一个面暴露在空气中
isExposed(BlockPos pos) // 遍历6个方向检查 isSolidRender
```

**借鉴到我们**：
- `getPlaceSide()` — 我们的 PlaceBlockAction 需要这个逻辑
- `getBreakDelta()` — 精确计算挖掘时间，用于任务规划
- `isExposed()` — 挖矿时判断方块是否可达

### ⭐⭐ 中价值

#### 5. AutoEat 自动进食
文件：systems/modules/player/AutoEat.java

- 饥饿/血量阈值触发
- 食物优先级（饱和度/营养值）
- 黑名单（毒马铃薯、河豚等）
- 进食时暂停战斗模块

**借鉴**：新增 AutoEatAction 或在 BotAIManager tick 中检测饥饿自动进食。

#### 6. EntityUtils 实体工具
文件：utils/entity/EntityUtils.java

- `isAttackable()` — 过滤不可攻击实体（经验瓶、钓鱼浮漂等）
- `getTotalHealth()` — 血量 + 吸收（用于目标优先级）
- `intersectsWithEntity()` — 使用 SectionStorage 的快速 AABB 碰撞检测
- `getClosestBlockBelow()` — 找最近实心方块（防摔落）

**借鉴**：
- `intersectsWithEntity()` — 高效实体碰撞检测，可用于放置方块前检查
- `getClosestBlockBelow()` — 路径规划中的摔落检测

#### 7. 工具选择逻辑
文件：utils/player/InvUtils.java → findFastestTool()

```java
public static FindItemResult findFastestTool(BlockState state) {
    float bestScore = 1;
    int slot = -1;
    for (int i = 0; i < 9; i++) {
        ItemStack stack = mc.player.getInventory().getItem(i);
        if (!stack.isCorrectToolForDrops(state)) continue;
        float score = stack.getDestroySpeed(state);
        if (score > bestScore) {
            bestScore = score;
            slot = i;
        }
    }
    return new FindItemResult(slot, 1);
}
```

简洁高效。我们的 ToolSet 有类似逻辑但更复杂。

---

## 代码模式总结

### Meteor 的设计哲学

1. **工具类优先** — InvUtils, BlockUtils, EntityUtils, PlayerUtils 都是静态工具类
2. **Predicate 驱动** — 大量使用 Predicate<ItemStack> 做灵活筛选
3. **FindItemResult 模式** — 返回 (slot, count) 对，统一处理查找结果
4. **Setting 系统** — 所有配置通过链式 Builder 构建，自动序列化
5. **事件驱动** — 通过 @EventHandler 注册 tick 事件

### 对我们项目的具体建议

| Meteor 模式 | 我们当前 | 建议 |
|-------------|---------|------|
| IPathManager 接口 | 路径耦合在 Action 中 | 抽取 PathingManager 接口 |
| KillAura 目标筛选 | AttackAction 简单查找 | 新增 TargetUtils |
| InvUtils.find() | InventoryUtils 较粗糙 | 重构为 Predicate 驱动 |
| BlockUtils.place() | PlaceBlockAction 基础 | 添加 getPlaceSide() |
| AutoEat 阈值 | 无 | 新增饥饿检测 |
| SortPriority | 无 | 目标排序策略 |

---

## 推荐实施

### 立即可用（复制逻辑）
1. TargetUtils — 目标筛选和排序（从 KillAura 提取）
2. getPlaceSide() — 放置面检测（从 BlockUtils 提取）
3. findFastestTool() — 最佳工具查找（从 InvUtils 提取）

### 短期重构
4. IPathManager 接口化
5. InventoryUtils 重构为 Predicate 驱动
6. AutoEat 集成

### 中期增强
7. ShieldBreak 逻辑（切斧破盾）
8. TPS 同步攻击间隔
9. 多目标攻击支持