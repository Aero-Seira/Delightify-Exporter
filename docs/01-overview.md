# 01 - 总览（Overview）

## 这是什么？
Delightify-Exporter 是一个 **Forge 1.20.1** 的游戏内数据导出 Mod。它在运行时读取整合包的**最终态（final loaded state / 最终态）**，并导出到数据库（优先 SQLite），供游戏外部项目使用。

本 Mod 包含两个运行上下文的逻辑：
- **服务端逻辑**（server context）：导出配方、物品注册表、标签等数据，适用于专用服务器与单机集成服务端。
- **客户端逻辑**（client context）：在单机集成服务端（integrated server）环境中额外导出物品贴图（离屏渲染）与配方视图布局/背景（`recipe_views`）。

> **推荐在单机集成服务端（`runClient`）下运行** `/delightify_export dump`，以获得包括客户端渲染资源在内的完整导出。

## 为什么强调最终态？
整合包的真实数据不是简单读取 jar 文件或资源文件就能得到的，原因包括：
- 物品（Items）来自运行时代码注册（registry）
- 配方（Recipes）可能被 datapack 覆盖、脚本修改、或运行时注入
- 标签（Tags）同样受 datapack 覆盖与合并规则影响

因此在服务器里导出通常是最权威、最贴近整合包实际效果的方式。

## 与 JEI / ProbeJS 的关系
- JEI：主要是客户端展示层与插件生态。在单机集成服务端环境中，本项目可通过 JEI 的 API 采集 recipe category 的槽位布局与背景信息（详见 `docs/08-recipe-view-export.md`）。
- ProbeJS/KubeJS：偏脚本开发体验与可发现性；可用于快速原型，但不一定保证最终态全量与数据库化结构。

本项目目标是：**最终态 + 可复用快照 + 结构化入库（ETL）**。

## 固定契约
- 命令：`/delightify_export dump`
- 输出：`<world>/delightify-exporter/export.sqlite`
