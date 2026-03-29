# 08 - 配方视图导出指南（Recipe View Export）

> 本文档面向专门负责"配方渲染规则/背景导出"功能的 Code Agent，提供从需求到实现的完整路径，无需反复确认需求。

---

## 0. 固定契约（不可更改）

- 命令入口：`/delightify_export dump`（统一入口，不新增命令）
- 输出路径：`<world>/delightify-exporter/export.sqlite`
- 新增表：`recipe_views`（见第 3 节）
- **仅在物理客户端（integrated server / `runClient`）下采集 JEI/REI 布局**；dedicated server 下写入 `{unavailable: true, reason: "no_client"}`，不抛出异常，不阻塞整体导出。

---

## 1. 目标（Goals）

- 对整合包中出现的每个 `type_id`（recipe type），导出一份**可序列化的视图规则**，包括：
  - 输入/输出槽位坐标（x, y, width, height）与语义角色（input / output / catalyst）
  - 配方视图背景（纹理资源引用或导出的 PNG）
  - 通用控件（箭头、火焰、能量条等，以 DSL 描述）
  - 元信息映射（energy / time 等字段从 recipe json 中的路径）
- 外部配方编辑器只需读取此表，即可将 recipe `raw_json` 中的物品/流体资源按槽位坐标渲染出来，无需了解每个 mod 的 GUI 代码。

## 2. 非目标（Non-goals）

- 不复刻 JEI/REI 的完整渲染效果（不支持自定义 draw 代码中的帧动画、粒子特效等）
- 不支持专用服务器（dedicated server）采集 JEI/REI 布局（JEI 是客户端 mod）
- 不保证 100% 覆盖所有 mod 的 recipe category（优先支持原版与主流 mod；未知类型保底导出空布局）
- 不导出物品/流体的具体内容（已由 `recipes.raw_json` 覆盖）

---

## 3. 运行环境（Client vs Server）

| 环境 | 能否采集 JEI 布局 | 说明 |
|------|-----------------|------|
| 单机集成服务端（`runClient`）| ✅ 可以 | JEI 已加载，可访问 `IRecipeManager` / `IRecipeCategory` |
| 专用服务器（dedicated server）| ❌ 不可以 | JEI 通常不加载；`Dist.CLIENT` 代码不可执行 |

**实现要求**：
- `RecipeViewSource` 必须用 `@OnlyIn(Dist.CLIENT)` 标注，且调用前通过 `FMLEnvironment.dist == Dist.CLIENT` 或 `DistExecutor` 进行保护。
- 若无法采集，对应 `type_id` 写入 `layout_json = {"unavailable":true,"reason":"no_client"}`，`version = 0`。

---

## 4. 数据模型（Schema）

### 新增表：`recipe_views`

```sql
CREATE TABLE IF NOT EXISTS recipe_views (
    type_id        TEXT    PRIMARY KEY,
    layout_json    TEXT    NOT NULL,
    background_ref TEXT,
    version        INTEGER NOT NULL DEFAULT 1
);
```

- `type_id`：`namespace:path`，与 `recipes.type_id` 一致
- `layout_json`：JSON 字符串，结构见下方 §4.1
- `background_ref`：背景资源引用，格式见 §4.2（无背景时为 NULL）
- `version`：schema 版本号（当前为 1）

### 3.1 `layout_json` 结构

```json
{
  "schema": 1,
  "type_id": "minecraft:crafting_shaped",
  "size": { "w": 176, "h": 85 },

  "slots": [
    { "id": "in_0", "role": "input",  "x": 30, "y": 17, "w": 18, "h": 18 },
    { "id": "in_1", "role": "input",  "x": 48, "y": 17, "w": 18, "h": 18 },
    { "id": "in_2", "role": "input",  "x": 66, "y": 17, "w": 18, "h": 18 },
    { "id": "in_3", "role": "input",  "x": 30, "y": 35, "w": 18, "h": 18 },
    { "id": "in_4", "role": "input",  "x": 48, "y": 35, "w": 18, "h": 18 },
    { "id": "in_5", "role": "input",  "x": 66, "y": 35, "w": 18, "h": 18 },
    { "id": "in_6", "role": "input",  "x": 30, "y": 53, "w": 18, "h": 18 },
    { "id": "in_7", "role": "input",  "x": 48, "y": 53, "w": 18, "h": 18 },
    { "id": "in_8", "role": "input",  "x": 66, "y": 53, "w": 18, "h": 18 },
    { "id": "out_0","role": "output", "x": 124,"y": 35, "w": 18, "h": 18 }
  ],

  "widgets": [
    { "kind": "arrow", "x": 90, "y": 35, "style": "crafting" }
  ],

  "meta": {
    "time":   { "source": "json_path", "path": "$.cookingtime", "unit": "tick" },
    "energy": { "source": "json_path", "path": "$.energy",      "unit": "FE" }
  },

  "unavailable": false
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `schema` | int | ✅ | 当前为 1 |
| `type_id` | string | ✅ | 与表主键一致，冗余存储便于解析 |
| `size` | object | ✅ | 配方视图整体尺寸（像素） |
| `slots[]` | array | ✅ | 槽位列表（空数组代表未知布局） |
| `slots[].id` | string | ✅ | 槽位唯一名，如 `in_0`, `out_0` |
| `slots[].role` | enum | ✅ | `input` / `output` / `catalyst` |
| `slots[].x,y,w,h` | int | ✅ | 槽位左上角坐标及尺寸（相对视图） |
| `widgets[]` | array | — | 通用控件（箭头/火焰/能量条等） |
| `widgets[].kind` | enum | ✅ | `arrow` / `flame` / `energy_bar` / `progress_bar` / `custom` |
| `meta` | object | — | 元信息字段映射，key 为语义名 |
| `unavailable` | bool | — | `true` 表示此 type_id 布局无法采集 |

### 3.2 `background_ref` 格式

优先采用**资源引用**（轻量，外部工具自行解包）：

```
minecraft:textures/gui/container/crafting_table.png
modid:textures/gui/jei/background_smelting.png
```

若 JEI 背景为自定义 drawable（无直接纹理路径），可选：
- 将 drawable 离屏渲染为 PNG 并存入 `recipe_view_backgrounds` 表（见 §4.3），`background_ref` 填 `file:<type_id_hash>.png`
- 或填 `null` 并在 `layout_json.size` 中保留尺寸信息

### 3.3 可选表：`recipe_view_backgrounds`（背景 PNG）

```sql
CREATE TABLE IF NOT EXISTS recipe_view_backgrounds (
    type_id   TEXT PRIMARY KEY,
    png       BLOB NOT NULL,
    sha1      TEXT NOT NULL
);
```

仅在 JEI 背景为自定义 drawable 且无法获取纹理路径时使用。MVP 阶段可暂不实现，标记 `TODO`。

---

## 5. 从 JEI 获取布局（高层步骤）

> JEI 版本：mezz.jei:jei-1.20.1-forge（按 `build.gradle` 中的实际依赖版本为准）

### 步骤概览

1. **检测 JEI 是否可用**  
   使用 Forge 的 `ModList.get().isLoaded("jei")` 判断，结合 `FMLEnvironment.dist == Dist.CLIENT`。

2. **获取所有 Recipe Category**  
   通过 JEI 的 `IRecipeManager`（在 `IModPlugin.registerRecipeCatalysts` 或 `IGuiHandler` 时机之后可用）获取 `List<IRecipeCategory<?>>`。  
   在 Forge 1.20.1 JEI 中，入口通常是 `Internal.getJeiRuntime().getRecipeManager()`。

3. **获取 Category 的 Background**  
   调用 `IRecipeCategory.getBackground()`，返回 `IDrawable`。  
   - 若 `IDrawable` 实现为 `DrawableIngredient` 或已知纹理子类：可反射读取 `ResourceLocation` 和 UV 区域  
   - 若为自定义 `IDrawable`（无法获取路径）：回退到离屏渲染（见 §5.1）

4. **获取 Slot 坐标**  
   对每个 category，取该 type 的至少一个 recipe 实例（从 `RecipeManager` 中获取），  
   构造 `IRecipeLayoutBuilder` 并调用 `category.setRecipe(builder, recipe, focuses)`（JEI 15.x API）。  
   遍历 `builder` 中注册的 slots，读取坐标、尺寸与 role（`RecipeIngredientRole`：INPUT / OUTPUT / CATALYST）。

5. **获取 Widget（可选）**  
   调用 `IRecipeCategory.getArrows()`（若存在），或对常见 category 硬编码箭头位置。

6. **组装 layout_json 并写入 `recipe_views`**

### 4.1 背景不是简单纹理时的回退策略

当 `IDrawable` 无法获取纹理路径时：
- **回退 A（推荐 MVP）**：写入 `background_ref = null`，在 `layout_json.size` 保留 `{w, h}`。外部编辑器用空白背景渲染槽位。
- **回退 B（可选，v2+）**：创建离屏 FBO，在 `ClientTickEvent` 中调用 `drawable.draw(guiGraphics, 0, 0)`，读取像素，存入 `recipe_view_backgrounds`。实现参考 `ItemRenderHelper` 的 FBO 逻辑。

---

## 6. 保底策略（Fallback）

| 情况 | 处理方式 |
|------|----------|
| JEI 未加载 | `layout_json = {"unavailable":true,"reason":"jei_not_loaded"}`，`background_ref = null` |
| 当前为 dedicated server | `layout_json = {"unavailable":true,"reason":"no_client"}`，`background_ref = null` |
| category 存在但 slot 读取失败 | `slots = []`，写入警告日志，继续处理其他 category |
| background drawable 无法获取路径 | 回退 A（见 §5.1），不阻塞导出 |
| 未知 recipe type（无对应 JEI category）| `layout_json = {"unavailable":true,"reason":"no_jei_category"}`，不阻塞导出 |

**全量优先原则**：任何单个 type_id 的失败都不得阻塞整体导出流程。

---

## 7. 原版 Recipe Type 手工适配模板

对于无 JEI 的环境（或 JEI 未适配的原版类型），提供内置静态模板：

| type_id | 槽位布局描述 |
|---------|-------------|
| `minecraft:crafting_shaped` | 3×3 输入（in_0~8）+ 1 输出（out_0）|
| `minecraft:crafting_shapeless` | 3×3 输入（按顺序填充）+ 1 输出 |
| `minecraft:smelting` | 1 输入 + 1 燃料（可选）+ 1 输出，fire widget |
| `minecraft:blasting` | 同 smelting |
| `minecraft:smoking` | 同 smelting |
| `minecraft:campfire_cooking` | 1 输入 + 1 输出 |
| `minecraft:stonecutting` | 1 输入 + 1 输出 |
| `minecraft:smithing_transform` | 2 输入（模板+基础材料）+ 1 输出 |

这些静态模板在 JEI 不可用时作为保底，可单独维护在 `RecipeViewTemplates` 类中。

---

## 8. Schema 版本策略

- `recipe_views.version` 字段标记当前 layout_json 的 schema 版本（当前为 1）
- schema 升级时：
  1. 在 `Schema.java` 中增加 `SCHEMA_VERSION` 常量
  2. 在 `schema_version` 表中更新版本
  3. 提供迁移 SQL（MVP 可先 DROP + RECREATE）
  4. 更新本文档第 3 节的字段表

---

## 9. 实现步骤（推荐顺序）

### Step 1：Schema 扩展
在 `SqliteDatabase.initializeSchema()` 中添加：
```sql
CREATE TABLE IF NOT EXISTS recipe_views (
    type_id TEXT PRIMARY KEY,
    layout_json TEXT NOT NULL,
    background_ref TEXT,
    version INTEGER NOT NULL DEFAULT 1
);
```

### Step 2：RecipeViewSource（client 包）
- 创建 `client/RecipeViewSource.java`，标注 `@OnlyIn(Dist.CLIENT)`
- `collect(Set<String> typeIds)` → `List<RecipeViewRow>`
- 先实现 JEI 不可用时的保底路径（返回 unavailable 行）
- 再实现 JEI 可用路径（枚举 categories → 构建 layout）

### Step 3：RecipeViewRow（model 包）
```java
public record RecipeViewRow(
    String typeId,
    String layoutJson,
    @Nullable String backgroundRef,
    int version
) {}
```

### Step 4：SqliteDatabase 写入方法
```java
void insertRecipeViews(List<RecipeViewRow> rows);
// 使用 INSERT OR REPLACE 策略，事务批量提交
```

### Step 5：ExporterService 集成
在 `ExporterService.dump()` 中，recipes 导出完成后：
```java
Set<String> typeIds = collectTypeIds(recipes); // 从 RecipeRow 去重
if (FMLEnvironment.dist == Dist.CLIENT) {
    List<RecipeViewRow> views = DistExecutor.unsafeCallWhenOn(
        Dist.CLIENT, () -> () -> new RecipeViewSource().collect(typeIds)
    );
    db.insertRecipeViews(views);
} else {
    // 写入 unavailable 行
    db.insertRecipeViews(buildUnavailableRows(typeIds));
}
```

---

## 10. 验收标准与测试计划

### 手动验收步骤

1. 环境：Forge 1.20.1，安装 JEI + 至少 1 个有自定义 recipe category 的 mod（如 Create / Thermal Expansion）
2. 启动方式：`./gradlew runClient`，进入单人世界
3. 执行：`/delightify_export dump`
4. 打开 `export.sqlite`，执行以下查询并验证：

```sql
-- 验收 1：recipe_views 表存在且非空
SELECT COUNT(*) FROM recipe_views;
-- 预期：行数 >= 整合包 recipe type 去重数

-- 验收 2：原版类型有完整布局
SELECT layout_json FROM recipe_views WHERE type_id = 'minecraft:crafting_shaped';
-- 预期：slots 数组包含 9 个 input + 1 个 output

-- 验收 3：JEI 适配的 mod 类型有布局
SELECT type_id, json_extract(layout_json, '$.unavailable') AS unavail
FROM recipe_views WHERE type_id LIKE 'create:%';
-- 预期：unavail IS NULL 或 false（表示布局已采集）

-- 验收 4：background_ref 存在（至少部分类型）
SELECT COUNT(*) FROM recipe_views WHERE background_ref IS NOT NULL;
-- 预期 > 0

-- 验收 5：专用服务器保底（可选）
-- 若在 runServer 环境重复导出，所有行应有 unavailable=true
```

5. 日志验收：导出日志中应包含：
   - `[RecipeViewSource] collected N recipe_view entries`
   - 若有失败：`[RecipeViewSource] WARN: failed to get layout for <type_id>: <reason>`

### 边界用例

- 没有安装 JEI：所有行为 `unavailable=true, reason=jei_not_loaded`，导出不报错
- JEI category 的 background 为自定义 drawable：`background_ref=null`，布局正常写入
- recipe type 在 JEI 中有多种 category（极少见）：取第一个非空 category，记录警告日志

---

## 11. Agent 行为准则

- 不修改固定契约（命令/输出路径）
- 客户端代码必须用 `@OnlyIn(Dist.CLIENT)` 和 `FMLEnvironment.dist` 隔离，不得让 dedicated server 崩溃
- 任何单个 type_id 采集失败都必须 catch 并记录，不影响整体导出
- 每次改动都要能被"runClient → 执行命令 → 查 recipe_views 行数与内容"验证
- 文档与实现同步更新（README / docs/tasks.md 勾选）
- JEI API 调用路径视具体依赖版本确认（`build.gradle` 中的 JEI 依赖坐标）；若 API 不同，以实际编译通过为准，不要硬猜方法签名
