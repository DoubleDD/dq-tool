# AGENTS.md — dq-tool 数据质量检测工具

> 本文件面向 AI 编码代理,是项目文档的**索引**;各领域细节由 `docs/wiki/` 下的子文件展开。项目文档与代码注释统一使用中文。
> 修改了某领域的行为/约定/结构时,必须同步更新对应的 wiki 子文件(而不是堆进本索引)。

## 项目定位

dq-tool 是一个轻量级单体 Web 应用,用于对关系型数据库做数据质量检测(表级行数/体积、字段级空值统计、大表并发分段扫描、Excel/Word 报告导出、AI 表说明与自动打标、表标记)。

支持 7 种数据库:MySQL、PostgreSQL、SQL Server、Oracle、达梦 DM8、人大金仓 KingbaseES、OceanBase(仅 MySQL 模式)。驱动全部来自 Maven 中央仓库。

无登录/权限控制,**仅适合内网单机部署**;任何能访问端口的人都能操作所有数据源,不要暴露到公网。

## 关键红线

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- **业务代码只在 common 模块(Kotlin)写一份**,server 壳层(Javalin)只消费;所有数据库差异收敛在 `dialect/` 包
- 库表结构变更一律新增 Flyway 迁移脚本,**已发布的迁移文件禁止修改**
- 前端构建由 `buildWeb` 任务(增量)作为 `processResources` 的强前置依赖:`:server:shadowJar` 前自动保证 `web/dist` 最新,不再存在"旧版/缺失"的静默坏包
- `data/`(H2 数据文件)不应提交或外发;功能性 `.bat` 注释一律用英文且必须保持 CRLF 行尾

## 快速命令

```bash
make dev          # 开发:后端 10000 + 前端 5173(web/src 有改动先重建 web/dist)
make build        # 交付 fat jar(内嵌前端)
make test         # ./gradlew :common:test :server:test
make package      # macOS dmg 安装包(其他平台见 打包与发布)
```

要求 JDK 25+、Node 24+(仅开发);详见下方「构建运行与测试」。

## 文档索引

### 功能

- [扫描与 Excel 导出](docs/wiki/扫描与Excel导出.md) — 表级/字段级检测、分段扫描与断点续扫、采样估算、Oracle 空串/段视图降级、Excel sheet 结构
- [AI 功能与表标记](docs/wiki/AI功能与表标记.md) — AI 表说明、AI 自动打标(AutoTagService)、全局表标记与标记统计页
- [Word 报告导出](docs/wiki/Word报告导出.md) — poi-tl 数据调研报告:封面+四章正文、DONE 快照口径、409 前置校验、模板改造脚本
- [数据源](docs/wiki/数据源.md) — 连接信息加密存储、SSH 隧道、库过滤白名单、JSON/Navicat/DataGrip 导入导出
- [授权码](docs/wiki/授权码.md) — 离线 Ed25519 授权码、签发工具、管理员实例授权码管理

### 架构与开发

- [技术栈与项目结构](docs/wiki/技术栈与项目结构.md) — Gradle 模块划分、Javalin 薄壳 + common 内核、前端栈、目录树逐层注释
- [前端页面与按钮逻辑](docs/wiki/前端页面与按钮逻辑.md) — 路由/页签体系、页面导航全景、各页面按钮触发逻辑与 API、轮询与只读模式等交互机制
- [构建运行与测试](docs/wiki/构建运行与测试.md) — 开发/交付构建、Makefile、ZGC、JUnit+Testcontainers 测试矩阵与无覆盖区
- [代码约定与安全](docs/wiki/代码约定与安全.md) — 分层与装配约定、配置/迁移新增流程、错误日志纪律、加密与敏感信息边界
- [桌面版与数据目录](docs/wiki/桌面版与数据目录.md) — 托盘/心跳看门狗生命周期、headless 行为、数据目录与日志滚动
- [打包与发布](docs/wiki/打包与发布.md) — jpackage/tauri 安装包、内嵌完整 JRE、版本号映射、CI release.yml 启停状态、.bat 坑

### 子模块自有文档

- [common/AGENTS.md](common/AGENTS.md) — 共享业务内核边界(禁 suspend、禁框架依赖、内部 JSON 只用 Jackson 2)、内核结构、Flyway 迁移规则
- [tauri/AGENTS.md](tauri/AGENTS.md) — Tauri 2 桌面壳:侧车协议、常驻托盘模型、自动更新、打包
