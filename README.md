# Delightify-Exporter

**Forge 1.20.1 / Java 17** 的游戏内数据导出 Mod，用于把整合包的**最终态（final loaded state / 最终态）**导出成数据库（优先 SQLite），供游戏外部项目进行查询、渲染与分析。

本 Mod 同时支持**服务端数据导出**（配方/物品/标签等注册表数据）与**客户端渲染数据导出**（物品贴图、配方视图布局/背景等），可在单机集成服务端（integrated server）环境下获取最完整的导出内容。

## 目标（Goals）
- 从**服务端**读取并导出整合包最终态数据：
  - Mods 列表（mod id / version / name）
  - Items 注册表（registry item ids：`namespace:path`）
  - Item Tags（tag → resolved items）
  - Recipes（recipe id / type id / source mod；并逐步补全 inputs/outputs 结构化）
- 在**客户端**环境下额外导出渲染资源：
  - 物品贴图（离屏渲染为 Base64 PNG）
  - 配方视图布局与背景（per recipe type 的渲染规则，供外部编辑器使用）
- 输出一个**可复用的数据快照**，外部工具只需读取数据库即可工作

## 不做的事（Non-goals, 目前）
- 不尝试穷举所有 NBT 变体（NBT variants 不可穷举；后续可选扩展）
- 不保证所有 mod 自定义 recipe 类型都能结构化解析（先保底记录 id/type/source，后续逐步加 parser）
- 专用服务器（dedicated server）环境下不导出依赖客户端渲染 API 的内容（贴图/视图布局），仅导出注册表与配方数据

---

## 使用方式（MVP）

### 完整导出（推荐：单机集成服务端 / integrated server）
1. 将本 Mod 安装到 Forge 1.20.1 单机环境，使用 `./gradlew runClient` 启动。
2. 进入单人世界后，在聊天框执行命令：
   - `/delightify_export dump`
3. 导出器会写出 SQLite 数据库到：
   - `<world>/delightify-exporter/export.sqlite`

> **推荐此方式**：客户端环境下可额外导出物品贴图与配方视图布局（`item_resources` / `recipe_views`）。

### 仅导出注册表/配方数据（专用服务器）
1. 将本 Mod 安装到 Forge 1.20.1 专用服务器。
2. 启动后执行：`/delightify_export dump`
3. 导出内容：mods / items / item_tags / recipes（无贴图与配方视图）。

外部渲染/查询项目直接读取这个 sqlite 文件即可。

---

## 固定契约（已确认，不随实��变更）
- 命令：`/delightify_export dump`
- 输出：`<world>/delightify-exporter/export.sqlite`

---

## 文档（Docs）
- `docs/01-overview.md`：项目总览、术语与最终态解释
- `docs/02-architecture.md`：架构分层与数据流
- `docs/03-database-schema.md`：SQLite schema（DDL/索引/迁移）
- `docs/04-export-spec.md`：导出字段定义与规范化规则
- `docs/05-dev-guide-for-beginners.md`：新手开发指南（IDEA/运行/验证）
- `docs/06-agent-guide.md`：面向 Agent/vibecoding 的开发指南与约束
- `docs/07-roadmap.md`：路线图与里程碑
- `docs/08-recipe-view-export.md`：配方视图导出指南（per-recipe-type 渲染规则/背景/布局）
- `docs/tasks.md`：可执行任务清单（含验收标准）

---

## 许可证（License）
仓库当前配置为 GPL-3.0（见 `gradle.properties`）。