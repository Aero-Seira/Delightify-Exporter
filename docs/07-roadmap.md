# 07 - 路线图（Roadmap）

## Milestone 1：MVP（尽快可用）
- 命令 `/delightify_export dump`
- 写出 sqlite 到 `<world>/delightify-exporter/export.sqlite`
- 导出 manifest/mods/items/item_tags/recipes(basic)

## Milestone 2：v1（配方结构化）
- 原版常见 recipe 解析器
- 输出 recipe_inputs/recipe_outputs
- unknown recipe 保底（unparsed=1）

## Milestone 3：v2（工程化）
- 增量导出（hash 对比）
- 多快照（timestamp/世界维度）
- 更多数据类型（按外部渲染需求）：fluids、loot、worldgen