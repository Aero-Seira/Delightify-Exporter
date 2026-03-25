# 01 - 总览（Overview）

## 这是什么？
Delightify-Exporter 是一个 **Forge 1.20.1 的 server-only 导出 Mod**。它在服务器运行时读取整合包的**最终态（final loaded state / 最终态）**，并导出到数据库（优先 SQLite），供游戏外部项目使用。

## 为什么强调“最终态”？
整合包的真实数据不是简单读取 jar 文件或资源文件就能得到的，原因包括：
- 物品（Items）来自运行时代码注册（registry）
- 配方（Recipes）可能被 datapack 覆盖、脚本修改、或运行时注入
- 标签（Tags）同样受 datapack 覆盖与合��规则影响

因此“在服务器里导出”通常是最权威、最贴近整合包实际效果的方式。

## 与 JEI / ProbeJS 的关系
- JEI：主要是客户端展示层与插件生态；不作为本项目的权威数据源。
- ProbeJS/KubeJS：偏脚本开发体验与可发现性；可用于快速原型，但不一定保证最终态全量与数据库化结构。

本项目目标是：**最终态 + 可复用快照 + 结构化入库（ETL）**。

## 固定契约
- 命令：`/delightify_export dump`
- 输出：`<world>/delightify-exporter/export.sqlite`