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

## v2-v3: 物品资源内容
### item_resources
存储物品资源文件元数据和内容：
- `item_resources(item_id TEXT, resource_type TEXT, namespace TEXT, path TEXT, content TEXT, PRIMARY KEY (item_id, resource_type, namespace, path))`

**resource_type 类型：**

| 类型 | 说明 | content 内容 |
|------|------|-------------|
| `lang_name` | 本地化显示名称 | 实际文本（如 "钻石"） |
| `model` | 模型文件内容 | 完整模型 JSON（仅客户端环境） |
| `model_path` | 模型文件路径 | 空，用于外部工具定位 |
| `model_generated` | 默认模型标记 | "builtin/generated" |
| `model_parent` | 父模型引用 | 父模型 ID |
| `texture` | 材质文件内容 | Base64 编码的 PNG（仅客户端环境） |
| `texture_path` | 材质文件路径 | 纹理变量名 |
| `texture_main` | 主要/正面顶层材质路径 | `top/north`（方块）或 `layer0`（物品） |
| `blockstate` | 方块状态文件 | 完整的 blockstate JSON（仅方块） |

### 关于资源文件导出的重要说明

**⚠️ 环境限制：**
- **客户端环境**（`runClient`）：可以导出完整的模型 JSON 和材质图片
- **服务端环境**（`runServer`）：只能导出资源路径信息，无法读取实际文件内容

**解决方案：**

1. **使用客户端环境导出**（推荐）
   ```bash
   ./gradlew runClient
   # 进入单人游戏后执行 /delightify_export dump
   ```

2. **外部工具自行解析**（服务端导出时）
   ```sql
   -- 获取模型路径，然后从客户端 jar 提取
   SELECT namespace, path FROM item_resources 
   WHERE item_id = 'minecraft:diamond' AND resource_type = 'model_path';
   -- 对应文件: assets/minecraft/models/item/diamond.json
   
   -- 获取材质路径
   SELECT namespace, path FROM item_resources 
   WHERE item_id = 'minecraft:diamond' AND resource_type = 'texture_path';
   -- 对应文件: assets/minecraft/textures/item/diamond.png
   ```

**方块物品材质提取策略：**

对于方块类物品（如石头、泥土），系统会：
1. 读取 `blockstates/<name>.json` 找到对应的模型
2. 解析模型文件，提取材质映射
3. **优先选择正面/侧面材质**（方便展示）：`north` > `south` > `east` > `west` > `side` > `up` > `top` > `all` > `particle`
4. 将选中的材质作为 `texture_main` 导出

```sql
-- 获取方块的正面顶层材质（用于图标展示）
SELECT content FROM item_resources 
WHERE item_id = 'minecraft:stone' AND resource_type = 'texture_main';
-- content = "top/north" 表示这是顶层或正面材质

-- 获取实际材质图片
SELECT content FROM item_resources 
WHERE item_id = 'minecraft:stone' AND resource_type = 'texture';
-- content = Base64 编码的 PNG 图片
```

**示例查询：**
```sql
-- 获取物品显示名称（始终可用）
SELECT content FROM item_resources 
WHERE item_id = 'minecraft:diamond' AND resource_type = 'lang_name';

-- 获取所有资源路径
SELECT resource_type, namespace, path FROM item_resources 
WHERE item_id = 'minecraft:diamond';
```

索引建议：
- `CREATE INDEX idx_item_resources_item_id ON item_resources(item_id);`

## v3: 配方内容导出
### recipes
配方表现在包含完整的 `raw_json` 字段，存储序列化的配方内容：
- `recipes(recipe_id TEXT PRIMARY KEY, type_id TEXT NOT NULL, modid TEXT NOT NULL, hash TEXT NOT NULL, raw_json TEXT, unparsed INTEGER NOT NULL)`

**raw_json 格式示例：**
```json
{
  "id": "minecraft:diamond_sword",
  "type": "minecraft:crafting_shaped",
  "result": {"item": "minecraft:diamond_sword", "count": 1},
  "ingredients": [
    {"items": ["minecraft:diamond"], "is_empty": false},
    ...
  ],
  "pattern_width": 3,
  "pattern_height": 3
}
```

索引建议：
- `CREATE INDEX idx_recipes_type_id ON recipes(type_id);`
- `CREATE INDEX idx_recipes_modid ON recipes(modid);`

## 稳定主键与 hash
- `item_id` / `tag_id` / `recipe_id` 都使用 `namespace:path`。
- `hash` 用于对比快照/增量更新：对（type_id + 解析后的结构或基础字段）做 hash。