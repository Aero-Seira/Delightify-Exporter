# 06 - Agent / Vibecoding 指南（Forge 1.20.1 / Java）

本文档面向协助开发的 Agent（或自动化编码工具），目标是让 Agent 在**不反复确认需求**的前提下，按固定契约与里程碑实现 Delightify-Exporter。

---

## 0. 固定契约（不可更改）

### 命令（Command）
- `/delightify_export dump`

### 输出路径（Output）
- `<world>/delightify-exporter/export.sqlite`

> 说明：除非仓库 owner 明确提出变更，否则任何实现与文档都必须遵循该契约。

---

## 1. 项目目标（Goals）

实现一个 **Forge 1.20.1** 的数据导出 Mod，在运行时读取整合包的**最终态（final loaded state / 最终态）**并导出到 SQLite，供外部项目渲染/查询。

本 Mod 同时包含服务端与客户端逻辑：
- **服务端**：导出 recipes/items/tags，适用于专用服务器与单机集成服务端。
- **客户端**（`Dist.CLIENT`）：在单机集成服务端环境下额外导出物品贴图与配方视图布局。

最终态包括：
- 注册表（registry）中的 items（全量）
- item tags 的 resolved 展开结果（全量）
- recipe manager 中的 recipes（全量，至少导出 recipe_id/type_id/source；解析失败不阻塞）

---

## 2. 非目标（Non-goals, 目前）

- 不尝试穷举所有 NBT 变体（NBT variants 不可穷举）
- 专用服务器（dedicated server）环境下不导出依赖客户端渲染 API 的内容（贴图/视图布局）
- 配方视图导出（JEI/REI 布局）需在单机集成服务端运行，不支持纯 dedicated server（详见 `docs/08-recipe-view-export.md`）
- 不保证所有 mod 自定义 recipe 类型都能结构化解析（先保底导出基础信息）

---

## 3. 开发策略（Strategy）

### 核心原则
1. **全量优先**：先保证所有条目都能导出“基础字段”，再逐步增强解析。
2. **保底不失败**：任何 recipe 解析失败，只影响该 recipe 的 inputs/outputs 结构化，不影响全量 dump。
3. **写库批量事务**：SQLite 必须使用事务（BEGIN/COMMIT）与 batch/PreparedStatement，避免逐行 commit。
4. **结构化解析插件化**：Recipe 解析器按 `type_id` 插件化注册，避免写一条巨大 if-else。

### 推荐实现顺序（最短可验证路径）
- M1-1：命令可执行并能创建 sqlite 文件
- M1-2：写入 schema_version + manifest + mods
- M1-3：导出 items
- M1-4：导出 item_tags（resolved）
- M1-5：导出 recipes 基础信息

然后再做：
- M2：原版 recipe 解析器（shaped/shapeless/smelting 等） → 写入 recipe_inputs/outputs

---

## 4. 推荐包结构（便于维护与 vibecoding）

建议将实现拆成以下包（类名仅示意）：

- `io.github.aeroseira.delightify_exporter.command`
  - `ExportCommand`：注册 `/delightify_export dump`
- `io.github.aeroseira.delightify_exporter.export`
  - `ExporterService`：导出总控（组装上下文、调用各 dumper、统计耗时）
- `io.github.aeroseira.delightify_exporter.source`
  - `ModListSource`：mods
  - `ItemRegistrySource`：items
  - `ItemTagSource`：item tags（resolved）
  - `RecipeSource`：recipes（基础 + 可选解析）
- `io.github.aeroseira.delightify_exporter.db`
  - `SqliteDatabase`：连接/事务/批量写入
  - `Schema`：DDL、schema_version
- `io.github.aeroseira.delightify_exporter.parse`
  - `RecipeParser` 接口 + `Vanilla*Parser`
- `io.github.aeroseira.delightify_exporter.model`
  - POJO：`ItemRow/RecipeRow/TagRow/...`
- `io.github.aeroseira.delightify_exporter.util`
  - hash、计时器、字符串规范化等
- `io.github.aeroseira.delightify_exporter.client` *(仅 Dist.CLIENT)*
  - `ItemRenderHelper`：离屏 FBO 渲染物品贴图（异步批量）
  - `RecipeViewSource`（规划中）：JEI/REI 布局采集，见 `docs/08-recipe-view-export.md`

> 注意：当前仓库的 `Delightify_exporter.java` 是 Forge MDK 示例模板，包含 client setup / creative tab / example item。可逐步删除或隔离这些示例内容，避免误导；客户端渲染相关代码应放在 `client/` 包下并用 `@OnlyIn(Dist.CLIENT)` 标注。

---

## 5. SQLite Schema（MVP）

Agent 在实现时，至少需要创建以下表：

- `schema_version(version INTEGER PRIMARY KEY)`
- `manifest(key TEXT PRIMARY KEY, value TEXT NOT NULL)`
- `mods(modid TEXT PRIMARY KEY, version TEXT, name TEXT)`
- `items(item_id TEXT PRIMARY KEY, modid TEXT NOT NULL)`
- `item_tags(tag_id TEXT NOT NULL, item_id TEXT NOT NULL, PRIMARY KEY(tag_id, item_id))`
- `recipes(recipe_id TEXT PRIMARY KEY, type_id TEXT NOT NULL, modid TEXT NOT NULL, hash TEXT NOT NULL, raw_json TEXT, unparsed INTEGER NOT NULL)`

索引建议（MVP 可以先不做，v1 再补）：
- `item_tags(item_id)`
- `recipes(type_id)`
- `recipes(modid)`

### 字段约定
- `item_id / tag_id / recipe_id / type_id` 使用 `namespace:path`
- `modid` 一般取 `namespace`
- `unparsed`：0/1
- `hash`：用于未来做增量/对比快照；MVP 可以先用 `sha1(recipe_id + "|" + type_id)` 或更丰富的结构 hash

---

## 6. Tag resolved 展开（必须）

`item_tags` 必须导出 resolved 展开结果：
- tag 可能引用 tag（例如 `#forge:ingots`）
- 外部项目不希望每次递归展开
- 实现时要防止循环引用（visited set）

---

## 7. 验收标准（Acceptance Criteria）

### M1（MVP）验收
在一个 Forge 1.20.1 server 环境中：
1. 执行 `/delightify_export dump` 不报错
2. 生成文件：`<world>/delightify-exporter/export.sqlite`
3. sqlite 中至少存在上述 MVP 表
4. 行数满足：
   - `mods` > 0
   - `items` > 0
   - `item_tags` > 0（在 modpack 下通常很大）
   - `recipes` > 0

### 日志要求
导出过程必须输出关键日志：
- 开始导出：世界名、输出路径
- 每个阶段：导出数量与耗时（items/tags/recipes）
- 结束导出：总耗时

---

## 8. 实现注意事项（Forge / 运行环境区分）

- 命令注册要保证在 dedicated server 可用（不依赖 client classes）
- 输出路径必须定位到 world 目录（不要写到 jar 或只写 run/ 根目录）
- 客户端相关代码（渲染、贴图、JEI API）必须用 `@OnlyIn(Dist.CLIENT)` 标注，并在调用前通过 `FMLEnvironment.dist == Dist.CLIENT` 检查
- 服务端数据（recipes/items/tags）在 dedicated server 与 integrated server 下均应正常导出
- 物品贴图渲染（`ItemRenderHelper`）和配方视图采集仅在 integrated server（物理客户端）可用；dedicated server 下跳过并记录警告日志

---

## 9. 后续扩展（v1+）

- 增加 `recipe_inputs` / `recipe_outputs` 表并实现原版 recipe 解析器：
  - shaped/shapeless
  - smelting/blasting/smoking/campfire_cooking
  - stonecutting
- unknown recipe 类型：
  - 保持 `recipes` 基础行
  - `unparsed=1`
  - 不阻塞导出

---

## 10. Agent 行为准则（很重要）

- 不修改固定契约（命令/输出路径）
- 不引入大型跨平台框架（如 Architectury）以免拖慢 MVP
- 每次改动都要能被“跑 server → 执行命令 → 查看 sqlite 行数/日志”验证
- 文档与实现同步更新（README / docs/tasks.md 勾选）