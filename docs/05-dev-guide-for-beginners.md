# 05 - 新手开发指南（IntelliJ IDEA）

## 环境要求
- JDK 17
- IntelliJ IDEA
- Gradle（随项目 wrapper）

## 打开项目
1. 用 IDEA 打开仓库根目录（含 `build.gradle`、`settings.gradle`）。
2. 等待 Gradle 同步完成。
3. 如果运行配置没生成，执行：
   - `./gradlew genIntellijRuns`（或使用 ForgeGradle 推荐命令）

## 运行本地服务端（开发环境）
- 使用 Gradle run config：`runServer`
- 首次运行会下载依赖并创建 `run/` 目录

## 安装整合包/Mod 进行测试
- 将测试用 mod 放到 `run/mods/`
- 或在 runServer 启动参数/配置中指定 mods 目录（按你本地习惯）

## 触发导出（MVP）
进入世界后在服务器控制台执行：
- `/delightify_export dump`

导出路径：
- `<world>/delightify-exporter/export.sqlite`

## 如何验证导出结果
- 查看日志是否输出“开始导出/完成导出/耗时/数据库路径”
- 使用 sqlite 工具打开导出的数据库：
  - 检查是否存在 tables
  - 检查 `items`、`recipes` 等表是否有行数据

## 常见问题
- 运行失败：确认 JDK 是 17
- Gradle 依赖下载慢：检查网络/镜像
- 导出文件找不到：确认 world 路径与权限；确认命令执行成功