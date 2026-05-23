# EMI & JEI 配方系统分析报告

> 参考项目：
> - [emilyploszaj/emi](https://github.com/emilyploszaj/emi) (Architectury, Fabric+NeoForge, 1.21.1)
> - [mezz/JustEnoughItems](https://github.com/mezz/JustEnoughItems) (Fabric/NeoForge, 1.21.1)
> 分析日期：2026-05-23
> 分析目的：借鉴配方查找、配方树、可合成检测等逻辑

---

## 项目概况

| 特性 | EMI | JEI | AIMod (我们) |
|------|-----|-----|-------------|
| 定位 | 配方查看器+物品管理 | 配方查看器 | AI助手模组 |
| 配方查找 | 按输出/输入索引 | 按 RecipeType 索引 | 遍历全部配方 O(n) |
| 配方树 | Bill of Materials (BoM) | 无内置 | 无 |
| 可合成检测 | 支持（基于库存） | 无内置 | 无 |
| 配方分类 | EmiRecipeCategory | RecipeType | 仅 CraftingRecipe |
| 输入/催化物区分 | 有（inputs vs catalysts） | 有（INPUT vs CATALYST） | 无 |
| Tag 支持 | 完整（TagEmiIngredient） | 完整 | 无 |
| 多步配方规划 | BoM 树形解析 | 无 | 无 |

---

## 当前 CraftAction 的问题

```java
// 当前实现：遍历所有配方，O(n) 查找
for (RecipeHolder<?> recipe : recipes) {
    if (recipeValue instanceof CraftingRecipe) {
        ItemStack result = recipeValue.getResultItem(level.registryAccess());
        if (result.getItem() == resultItem) {
            return recipe;  // 返回第一个匹配
        }
    }
}
```

**问题清单**：
1. 遍历全部配方查找，效率低
2. 返回第一个匹配，不考虑最优配方（如原材料最少的）
3. 不支持 Tag（如 `#minecraft:planks` 可以用任意木板）
4. 不区分 inputs（消耗）和 catalysts（不消耗，如工作台）
5. 无配方树——无法规划"合成钻石镐需要先合成木棍"
6. 无"可合成"检测——不知道当前背包能合成什么
7. 只支持 CraftingRecipe，不支持熔炉/高炉/烟熏炉等

---

## EMI 的核心设计模式

### 1. EmiRecipe 接口（配方抽象）

```java
public interface EmiRecipe {
    EmiRecipeCategory getCategory();      // 配方分类
    Identifier getId();                    // 唯一ID
    List<EmiIngredient> getInputs();       // 输入（会被消耗）
    List<EmiIngredient> getCatalysts();    // 催化物（不消耗，如工作台）
    List<EmiStack> getOutputs();           // 输出
    boolean supportsRecipeTree();          // 是否支持配方树
    boolean hideCraftable();               // 是否从"可合成"菜单隐藏
    RecipeEntry<?> getBackingRecipe();     // 获取原版配方
}
```

**关键设计**：
- 输入 vs 催化物分离——催化物是"需要但不消耗"的（如工作台、熔炉）
- `supportsRecipeTree()` 判断配方是否可纳入配方树
- `getBackingRecipe()` 桥接到原版 RecipeEntry

### 2. EmiIngredient（材料抽象）

```java
public interface EmiIngredient {
    List<EmiStack> getEmiStacks();   // 支持多个等价物品（Tag）
    long getAmount();                 // 需要数量
    boolean isEmpty();
    
    // 工厂方法
    static EmiIngredient of(TagKey<?> key);          // 从 Tag 创建
    static EmiIngredient of(Ingredient ingredient);   // 从原版 Ingredient 创建
    static EmiIngredient of(List<EmiIngredient> list); // 组合
}
```

**关键设计**：
- 一个 EmiIngredient 可以代表多个等价物品（如任意木板）
- 支持数量（amount）和概率（chance）
- 从原版 `Ingredient` 和 `TagKey` 直接转换

### 3. EmiApi 的配方查找

```java
// 按输出查找配方（"这个物品怎么合成"）
getRecipeManager().getRecipesByOutput(EmiStack stack)

// 按输入查找配方（"这个物品能合成什么"）
getRecipeManager().getRecipesByInput(EmiStack stack)

// 按催化物查找（"这个工作台能做什么"）
EmiRecipes.byWorkstation.getOrDefault(stack, List.of())

// 按分类获取所有配方
getRecipeManager().getRecipes(category)
```

**关键设计**：
- 配方按输出/输入/催化物预先索引，查找 O(1)
- 按分类组织，支持批量查询

### 4. Bill of Materials (BoM) — 配方树

EMI 独有的 BoM 系统，可以解析完整的配方依赖树：
- 给定目标物品，递归查找所有需要的原材料
- 支持多步合成规划
- 可以显示"从原木到钻石镐"的完整路径

---

## JEI 的核心设计模式

### 1. RecipeType 类型系统

```java
// 每种配方类型有唯一的 RecipeType
RecipeType<CraftingRecipe> CRAFTING = ...;
RecipeType<SmeltingRecipe> SMELTING = ...;
RecipeType<StonecuttingRecipe> STONECUTTING = ...;
```

### 2. IRecipeManager 查找接口

```java
// 创建类型化的查找
IRecipeLookup<CraftingRecipe> lookup = recipeManager.createRecipeLookup(CRAFTING);
lookup.limitFocus(focuses);  // 按焦点过滤
Stream<CraftingRecipe> results = lookup.get();

// 按 RecipeType 获取分类
IRecipeCategory<CraftingRecipe> category = recipeManager.getRecipeCategory(CRAFTING);
```

### 3. RecipeCatalystBuilder（催化物映射）

```java
// 建立分类 → 催化物的映射
// 例如：CRAFTING → 工作台, SMELTING → 熔炉
recipeCatalystBuilder.addCategoryCatalysts(recipeCategory, catalystIngredients);
```

---

## 对我们项目的具体建议

### 立即可做

#### 1. 配方索引系统
参考 EMI 的 `getRecipesByOutput`，建立输出物品索引：

```java
public class RecipeIndex {
    // 输出物品 → 配方列表
    private Map<Item, List<RecipeHolder<CraftingRecipe>>> byOutput = new HashMap<>();
    // 输入物品 → 配方列表
    private Map<Item, List<RecipeHolder<CraftingRecipe>>> byInput = new HashMap<>();
    
    public void build(ServerLevel level) {
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (holder.value() instanceof CraftingRecipe recipe) {
                Item output = recipe.getResultItem(...).getItem();
                byOutput.computeIfAbsent(output, k -> new ArrayList<>()).add(holder);
                for (Ingredient ing : recipe.getIngredients()) {
                    for (ItemStack match : ing.getItems()) {
                        byInput.computeIfAbsent(match.getItem(), k -> new ArrayList<>()).add(holder);
                    }
                }
            }
        }
    }
    
    // O(1) 查找
    public List<RecipeHolder<CraftingRecipe>> getRecipesFor(Item output) { ... }
    public List<RecipeHolder<CraftingRecipe>> getRecipesUsing(Item input) { ... }
}
```

#### 2. Tag 感知的材料匹配
参考 EMI 的 `EmiIngredient.of(TagKey)`：

```java
// 当前：只检查具体物品
ingredient.getItems()[0].getItem()

// 改进：支持 Tag
if (ingredient.is(ItemTags.PLANKS)) {
    // 任意木板都可以
    return InventoryUtils.findItem(bot, item -> item.builtInRegistryHolder().is(ItemTags.PLANKS));
}
```

#### 3. 催化物区分
参考 EMI 的 inputs vs catalysts：

```java
// 区分消耗物和催化物
// 合成木棍：木板=输入（消耗），工作台=催化物（不消耗）
public class ResolvedRecipe {
    List<IngredientEntry> consumedInputs;  // 需要消耗的
    List<Item> catalysts;                   // 需要但不消耗的（工作台、熔炉）
}
```

### 短期（3-5天）

#### 4. 可合成检测
参考 EMI 的 `hideCraftable` 概念：

```java
// 检查当前背包能合成什么
public List<RecipeHolder<CraftingRecipe>> getCraftableRecipes(FakePlayer bot) {
    List<RecipeHolder<CraftingRecipe>> result = new ArrayList<>();
    for (var entry : byOutput.entrySet()) {
        for (var recipe : entry.getValue()) {
            if (hasAllIngredients(bot, recipe)) {
                result.add(recipe);
            }
        }
    }
    return result;
}
```

#### 5. 多步配方树解析
参考 EMI 的 BoM：

```java
// 给定目标物品，递归解析所有需要的原材料
public class RecipeTree {
    private Item target;
    private int count;
    private RecipeHolder<?> recipe;      // 使用的配方
    private List<RecipeTree> children;   // 子依赖（需要先合成的物品）
    private List<Item> rawMaterials;     // 叶子节点（不需要合成的原材料）
    
    // 递归构建树
    public static RecipeTree resolve(Item target, int count, RecipeIndex index, InventoryState inv) {
        RecipeHolder<?> recipe = index.getRecipesFor(target).get(0);
        RecipeTree tree = new RecipeTree(target, count, recipe);
        for (Ingredient ing : recipe.value().getIngredients()) {
            Item ingItem = ing.getItems()[0].getItem();
            if (index.getRecipesFor(ingItem).isEmpty()) {
                tree.rawMaterials.add(ingItem); // 原材料，不需要合成
            } else {
                tree.children.add(resolve(ingItem, 1, index, inv)); // 需要先合成
            }
        }
        return tree;
    }
}
```

#### 6. 支持更多配方类型
```java
// 当前只支持 CraftingRecipe
// 需要添加：
// - SmeltingRecipe（熔炉）
// - BlastingRecipe（高炉）
// - SmokingRecipe（烟熏炉）
// - StonecuttingRecipe（切石机）
// - SmithingTransformRecipe（锻造台）
```

### 长期

#### 7. 完整的合成规划引擎
综合以上所有改进，构建类似 EMI BoM 的完整规划：

```
目标：合成钻石镐 × 1
├─ 钻石镐 = 3钻石 + 2木棍 (crafting)
│  ├─ 钻石 = 已有 ✓
│  └─ 木棍 = 2木板 (crafting)
│     └─ 木板 = 1橡木原木 (crafting)
│        └─ 橡木原木 = 需要采集 ✗
├─ 步骤1：采集橡木原木
├─ 步骤2：合成木板
├─ 步骤3：合成木棍
├─ 步骤4：合成钻石镐
└─ 步骤5：交给玩家
```

---

## 推荐开发优先级

| 优先级 | 功能 | 复杂度 | 价值 |
|--------|------|--------|------|
| P0 | 配方索引（byOutput/byInput） | 低 | 高（查找速度 O(1)） |
| P0 | Tag 感知材料匹配 | 低 | 高（不再硬编码物品） |
| P1 | 催化物区分 | 低 | 中（正确消耗逻辑） |
| P1 | 可合成检测 | 中 | 中（LLM可询问"能做什么"） |
| P2 | 多步配方树 | 中 | 高（核心规划能力） |
| P2 | 多配方类型支持 | 中 | 中（熔炉/切石机等） |
| P3 | 完整合成规划引擎 | 高 | 高（终极目标）