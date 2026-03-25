# 03 - 数据库 Schema（SQLite）

> 目标：外部项目易读、可索引、可扩展、可迁移。

## 版本管理
- 使用 `schema_version` 表管理版本：
  - `schema_version(version INTEGER PRIMARY KEY)`
- 每次更改 schema：
  - 增加 version
  - 提供迁移（MVP 可先“删除重建”，v2 再做迁移脚本）

## MVP 表（建议）
### manifest
用于存储快照元信息（key-value）：
- `manifest(key TEXT PRIMARY KEY, value TEXT NOT NULL)`

建议 keys：
- `exported_at_utc`
- `minecraft_version`
- `forge_version`
- `world_name`
- `modlist_hash`（可选）

### mods
- `mods(modid TEXT PRIMARY KEY, version TEXT, name TEXT)`

### items
- `items(item_id TEXT PRIMARY KEY, modid TEXT NOT NULL)`

### item_tags
- `item_tags(tag_id TEXT NOT NULL, item_id TEXT NOT NULL, PRIMARY KEY(tag_id, item_id))`

索引建议：
- `CREATE INDEX idx_item_tags_item_id ON item_tags(item_id);`

### recipes
基础字段（解析失败也要写）：
- `recipes(recipe_id TEXT PRIMARY KEY, type_id TEXT NOT NULL, modid TEXT NOT NULL, hash TEXT NOT NULL, raw_json TEXT, unparsed INTEGER NOT NULL)`

索引建议：
- `CREATE INDEX idx_recipes_type_id ON recipes(type_id);`
- `CREATE INDEX idx_recipes_modid ON recipes(modid);`

## v1+：结构化输入输出
### recipe_inputs
- `recipe_inputs(recipe_id TEXT, slot INTEGER, kind TEXT, ref TEXT, count_min INTEGER, count_max INTEGER, PRIMARY KEY(recipe_id, slot, kind, ref))`

`kind` 建议值：
- `item`（ref = item_id）
- `tag`（ref = tag_id）

索引建议：
- `CREATE INDEX idx_recipe_inputs_ref ON recipe_inputs(ref);`

### recipe_outputs
- `recipe_outputs(recipe_id TEXT, slot INTEGER, item_id TEXT, count INTEGER, nbt_json TEXT, PRIMARY KEY(recipe_id, slot, item_id, nbt_json))`

## 稳定主键与 hash
- `item_id` / `tag_id` / `recipe_id` 都使用 `namespace:path`。
- `hash` 用于对比快照/增量更新：对（type_id + 解析后的结构或基础字段）做 hash。