# Delightify-Exporter

**Forge 1.20.1 / Java 17 / Server-only** 的游戏内数据导出 Mod，用于把整合包在服务器中的**最终态（final loaded state / 最终态）**导出成数据库（优先 SQLite），供游戏外部项目进行查询、渲染与分析。

## 目标（Goals）
- 从 **服务端（server）**读取并导出整合包最终态数据：
  - Mods 列表（mod id / version / name）
  - Items 注册表（registry item ids：`namespace:path`）
  - Item Tags（tag → resolved items）
  - Recipes（recipe id / type id / source mod；并逐步补全 inputs/outputs 结构化）
- 输出一个**可复用的数据快照**，外部工具只需读取数据库即可工作

## 不做的事（Non-goals, 目前）
- 不导出 JEI/REI 等客户端 GUI 数据
- 不尝试穷举所有 NBT 变体（NBT variants 不可穷举；后续可选扩展）
- 不保证所有 mod 自定义 recipe 类型都能结构化解析（先保底记录 id/type/source，后续逐步加 parser）

---

## 使用方式（MVP）
1. 将本 Mod 安装到 Forge 1.20.1 服务器（或单机集成服务端）。
2. 启动世界后，在服务器执行命令：
   - `/delightify_export dump`
3. 导出器会写出 SQLite 数据库到：
   - `<world>/delightify-exporter/export.sqlite`

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
- `docs/tasks.md`：可执行任务清单（含验收标准）

---

## 许可证（License）
仓库当前配置为 GPL-3.0（见 `gradle.properties`）。