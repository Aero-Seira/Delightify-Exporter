# 04 - 导出规范（Export Spec）

## 总体原则
- **全量优先**：先保证“导出不失败”，再提升“解析丰富度”。
- **保底策略**：对未知 recipe 类型：
  - 必须导出 `recipe_id/type_id/modid/hash`
  - 标记 `unparsed=1`
  - `raw_json` 如果未来能获取则填充，否则为 NULL

## Mods
来源：Forge loader / ModList（实现时决定）
字段：
- `modid`
- `version`
- `name`（若可得）

## Items
来源：registry（全量）
字段：
- `item_id`：`namespace:path`
- `modid`：`namespace`

## Item Tags
来源：server tags（最终态）
导出：
- `tag_id`：`namespace:path`
- `item_id`：resolved 的成员 item ids

必须做 resolved：
- tag 允许引用 tag（`#tag`），外部工具不希望每次递归展开
- resolved 展开要防循环引用（检测 visited）

## Recipes（基础）
来源：server recipe manager（最终态）
字段：
- `recipe_id`
- `type_id`
- `modid = recipe_id.namespace`
- `hash`
- `unparsed`
- `raw_json`（可选）

## Recipes（结构化 v1+）
采用 parser 插件化：
- `supports(type_id)` → `parse(recipe)` → `inputs/outputs`

原版优先支持：
- crafting_shaped / crafting_shapeless
- smelting/blasting/smoking/campfire_cooking
- stonecutting
- smithing（可选）