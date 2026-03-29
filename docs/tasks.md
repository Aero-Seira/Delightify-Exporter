# Tasks（可执行任务清单）

## M0：文档与仓库卫生
- [ ] README 完整化（目标、使用、契约、docs 索引）
- [ ] 新增 `.gitignore`
- [ ] 完整 docs/ 文档体系

## M1：MVP（导出可用）
- [ ] 注册命令 `/delightify_export dump`
  - 验收：服务器可执行命令，提示开始导出
- [ ] SQLite writer + schema_version + manifest/mods
  - 验收：生成 sqlite 文件且表存在
- [ ] 导出 items（registry）
  - 验收：items 表行数>0
- [ ] 导出 item tags（resolved）
  - 验收：item_tags 表行数>0，且能反查某些常见 tag
- [ ] 导出 recipes 基础信息
  - 验收：recipes 表行数>0，type_id/modid 正常

## M2：v1（原版配方结构化）
- [ ] shaped/shapeless 解析器
- [ ] smelting/blasting/smoking/campfire 解析器
- [ ] stonecutting 解析器
- [ ] recipe_inputs/recipe_outputs 写入
  - 验收：外部工具可做“按 item_id 反查 recipes”
## M3：v2（工程化）
- [x] Schema 升级到 v4
- [x] 新增 `recipe_views` 表
- [x] 新增 `recipe_view_backgrounds` 表（可选）

## M4：v3（配方视图导出）
- [x] `RecipeViewRow` 数据模型
- [x] `RecipeViewSource` 客户端采集器（@OnlyIn(Dist.CLIENT)）
  - JEI 检测与反射采集
  - 内置原版配方模板（crafting/smelting/stonecutting/smithing）
  - 专用服务器保底（unavailable=true）
- [x] `SqliteDatabase.insertRecipeViews()` 批量写入
- [x] `ExporterService` 集成配方视图导出
  - 从 recipes 表提取 type_id 集合
  - 客户端环境通过反射调用 RecipeViewSource
  - 服务端环境生成不可用占位行
- [x] 验证：
  - `recipe_views` 表存在且非空
  - 原版类型有完整槽位布局
  - 服务端导出标记为 unavailable
