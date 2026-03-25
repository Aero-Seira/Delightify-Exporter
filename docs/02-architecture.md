# 02 - 架构（Architecture）

## 设计原则
1. **server-only**：不依赖客户端类与渲染逻辑。
2. **永远可导出**：任何 recipe 至少导出基础字段（id/type/source），解析失败不阻塞全量导出。
3. **核心逻辑与平台逻辑分离**：即使目前只做 Forge，也尽量让“写库/规范化/模型”与“从 MC 取数据”分开，便于未来扩展（NeoForge / 版本簇）。
4. **批量事务写入**：SQLite 以事务 + batch 为主，避免逐行 commit。

## 建议分层（包结构）
- `command/`
  - 命令注册与参数解析（MVP 只要 dump）
- `export/`
  - `ExporterService`：导出总控（创建 DB、写 manifest、调用各 source）
- `source/`
  - `ModListSource`：mod 列表
  - `RegistrySource`：items（registry）
  - `TagSource`：item tags（resolved）
  - `RecipeSource`：recipes（基础字段 + 可选结构化）
- `parse/`
  - `RecipeParser` 接口
  - `Vanilla*Parser`：原版类型解析器（v1+）
- `db/`
  - `Schema`：DDL 与 schema_version
  - `SqliteDatabase`：连接、事务、prepared statements、batch
- `model/`
  - POJO：`ItemRow` / `RecipeRow` / `TagRow`...
- `util/`
  - hash、计时、字符串规范化等

## 数据流（Data Flow）
1. 玩家/管理员执行：`/delightify_export dump`
2. `ExportCommand` 调用 `ExporterService.dump(server)`
3. `ExporterService`：
   - 定位输出路径：`<world>/delightify-exporter/export.sqlite`
   - 打开 SQLite，创建/迁移 schema
   - 写入 `manifest` 与 `mods`
   - 导出 items、tags、recipes
   - 关闭连接，输出日志与统计

## 线程与一致性
MVP 推荐全部在服务器主线程执行（避免并发一致性问题）。
如未来数据量很大，可考虑：
- 收集阶段在主线程构建中间结构（POJO）
- 写库阶段异步（但要保证读取阶段完成后再异步写）