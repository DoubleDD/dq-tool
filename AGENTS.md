# AGENTS.md — dq-tool 数据质量检测工具

> 本文件面向 AI 编码代理,是项目文档的**索引**:只保留定位、红线与一句话导引,细节一律写进 `docs/wiki/` 下的对应子文件。
> 项目文档与代码注释统一使用中文。修改了某领域的行为/约定/结构时,必须同步更新对应的 wiki 子文件(而不是堆进本索引)。

## 项目定位

dq-tool 是一个轻量级单体应用:交付的 fat jar 是**纯 API 服务**(不含前端资源,只有 `/api/**` 与桌面/托盘能力),同时提供 Tauri 2 桌面壳(webview 从本地 `web/dist` 直载,API 走 `127.0.0.1` 动态端口)与 jpackage 浏览器形态(同一 jar 加 `-Ddq.web.static-dir` 从磁盘发页面)。用于对关系型数据库做数据质量检测(表级行数/体积、字段级空值统计、大表并发分段扫描、Excel/Word 报告导出、AI 表说明与自动打标、表标记)。

支持 8 种数据库:MySQL、PostgreSQL、SQL Server、Oracle、达梦 DM8、人大金仓 KingbaseES、OceanBase(仅 MySQL 模式)、瀚高 HighGo。驱动全部来自 Maven 中央仓库。

无登录/权限控制,**仅适合内网单机部署**;任何能访问端口的人都能操作所有数据源,不要暴露到公网。配置 `dq.access-token` 后开启浏览器访问门禁(header/query/Cookie 三选一命中才放行),Tauri 每次启动随机生成注入,用于挡住同机浏览器里的恶意网页读取本地 API——它是门闩而非鉴权,豁免清单保持最小。

## 关键红线

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- **业务代码只在 common 模块(Kotlin)写一份**,server 壳层(Javalin)只消费;所有数据库差异收敛在 `dialect/` 包
- 库表结构变更一律新增 Flyway 迁移脚本,**已发布的迁移文件禁止修改**
- **导入导出格式向后兼容是铁律**:旧版本导出的文件必须能被新版本导入(客户拿到导出文件后的处理方式不可控);格式演进优先在同版本内追加可选字段(导入忽略未知字段+缺省值兜底),确需破坏性变更时在导出文件升 `version` 并在导入端做版本检测、分版本解析,禁止让旧文件静默报错(细则见 代码约定与安全)
- 前端构建与后端运行解耦:`make dev` / `make dev-headless`(`:server:run`,走 classpath 静态)不构建前端,前端开发走 `make dev-web`(vite 5173);`:server:shadowJar` **排除 `static/**`**,交付 jar 是纯 API 服务;`processResources` 仍把 `web/dist` 拷入 dev/测试 classpath;前端产物由 Tauri(`frontendDist` 直载 `web/dist`)与 jpackage(脚本 xcopy `web/dist` + `-Ddq.web.static-dir`)各自构建
- Tauri 交付形态:webview 从本地 `frontendDist` 直载,跨域访问 `127.0.0.1:<动态端口>`;后端 `dq.access-token` 由 Rust 每次启动随机生成并经 `-Ddq.access-token` 注入,前端从 IPC `api_base()` 取 `{base,token}` 后走 `X-Dq-Token` 头(SSE 走 `?token=`);CORS 用 `anyHost()` 只对 `/api/*` 开放,门禁豁免清单固定为 `/api/health`、`/api/license/status`、`/api/lan/share/**`,不得扩大
- `data/`(H2 数据文件)不应提交或外发;功能性 `.bat` 注释一律用英文且必须保持 CRLF 行尾

## 快速命令

```bash
make dev          # 开发:后端 10000(不构建前端);前端开发另起 make dev-web(5173)
make build        # 交付 fat jar(纯 API 服务,不含前端;前端由 tauri/各打包脚本构建)
make test         # ./gradlew :common:test :server:test
make package      # macOS dmg 安装包(其他平台见 打包与发布)
```

要求 JDK 25+、Node 24+(仅开发);详见下方「构建运行与测试」。

## 文档索引

### 功能

- [扫描与 Excel 导出](docs/wiki/扫描与Excel导出.md) — 表级/字段级检测、分段扫描与断点续扫、采样估算、Oracle 空串/段视图降级、Excel 与表结构 Word 导出
- [AI 功能与表标记](docs/wiki/AI功能与表标记.md) — AI 表说明与自动打标(SKIP/APPEND/OVERWRITE)、空表/备份表系统标记、表标记统计与 AI 用量
- [人工采集](docs/wiki/人工采集.md) — 表列表页采集与跨数据源关注清单(数据源/库过滤、全列排序、Excel 导出)
- [ER 关系推导](docs/wiki/ER关系推导.md) — 名字/语义双通道推导 + 值交集/基数验证,候选/确认/否决三态与 G6 乌鸦脚 ER 图
- [对象管理(数据目录)](docs/wiki/对象管理.md) — 目录树挂载表与关系表登记、树内拖动/「移动」变更所属、同级排序
- [Word 报告导出](docs/wiki/Word报告导出.md) — poi-tl 数据调研报告:封面+四章、DONE 快照口径、部分导出(异步任务,所选库全无快照置 FAILED)
- [数据源](docs/wiki/数据源.md) — 连接信息加密、SSH 隧道、库过滤、导入导出、连接状态与断网降级、元数据批量同步
- [元数据导入导出](docs/wiki/元数据导入导出.md) — 结构缓存 + 标注数据打包 JSON,供连不上业务库的人离线交接
- [表格批量导入与抽样导出](docs/wiki/批量导入与抽样导出.md) — Excel 批量导入 → 数据源检测 → 按 数据源×类别 抽样导出 zip 的两步流程
- [数据比对](docs/wiki/数据比对.md) — 对象匹配对齐 + 逐字段比对,差异明细与质量报告、xlsx 导出、行级/列级两模式;受控功能(授权码需含 `compare`)
- [系统诊断](docs/wiki/系统诊断.md) — 环境/AI/数据源连通实测/失败记录/错误日志聚合与 Markdown 报告(授权仅内部聚合,页面不展示)
- [错误中心](docs/wiki/错误中心.md) — 统一错误收集:前端 JS/接口、后端异常、数据库错误、任务与启动异常落 H2 按指纹聚合,可筛选/标记处理/导出
- [SQL 控制台](docs/wiki/SQL控制台.md) — 选数据源执行任意 SQL 与结果预览,另含只读的「本地 H2 库」
- [局域网共享](docs/wiki/局域网共享.md) — UDP 广播自动发现 + 按 IP 手动添加,按任务勾选互拉标记/描述/扫描记录
- [授权码](docs/wiki/授权码.md) — 离线 Ed25519 授权码、签发工具与管理员留档管理、菜单级控制与免鉴权标记
- [更新日志](docs/wiki/更新日志.md) — CHANGELOG.md 构建期拷入 jar,首启「本次更新」与页脚「更新记录」页签、verifyChangelog 构建硬校验

### 架构与开发

- [技术栈与项目结构](docs/wiki/技术栈与项目结构.md) — Gradle 模块划分、Javalin 纯 API 壳 + common 内核、前端栈、目录树逐层注释
- [前端页面与按钮逻辑](docs/wiki/前端页面与按钮逻辑.md) — 路由/页签体系、页面导航全景、各页面按钮与 API、轮询与只读模式等交互机制
- [构建运行与测试](docs/wiki/构建运行与测试.md) — 开发/交付构建、Makefile、GC 参数、JUnit+Testcontainers 测试矩阵与无覆盖区
- [代码约定与安全](docs/wiki/代码约定与安全.md) — 分层与装配约定、配置/迁移新增流程、错误日志纪律、加密与敏感信息边界
- [桌面版与数据目录](docs/wiki/桌面版与数据目录.md) — 托盘/心跳看门狗生命周期、headless 行为、数据目录与日志滚动
- [打包与发布](docs/wiki/打包与发布.md) — jpackage/tauri 安装包、内嵌完整 JRE、版本号映射、CI release.yml 启停状态、.bat 坑
- [发布 skill](.agents/skills/dq-tool-release/SKILL.md) — AI 代理发布全流程:提交改动到 main → 推 GitHub → 指定 tag 指向最新提交并推送

### 实施计划与存档

- [de-spring-javalin 改造方案](docs/plans/de-spring-javalin-改造方案.md) — **已执行**(2026-08-06):Spring Boot 4.1 → Javalin,决策留档
- [表标记与统计实施计划](docs/plans/表标记与统计-实施计划.md) — **已完成**(需求源 `docs/requirements/表标记与统计需求.md`)
- [ER 关系推导讨论稿](docs/plans/ER关系推导-讨论稿.md) / [实施计划](docs/plans/ER关系推导-实施计划.md) — **已实施**;讨论稿留作术语/决策/流程图溯源
- [浏览器访问管控实施计划](docs/plans/浏览器访问管控-实施计划.md) — **已实施**(token 门禁;前端令牌出口最终落在 `web/src/api/base.js`)
- [后台任务中心实施计划](docs/plans/后台任务中心-实施计划.md) — **已实施**(2026-09-14 随 2.0.7 发布)
- [Tauri 直载前端 · jar 转纯 API 服务实施计划](docs/plans/Tauri直载前端-jar转纯API服务-实施计划.md) — **已实施**(T14 三平台真机 Origin 待回填);[现场交接说明](docs/plans/Tauri直载前端-jar转纯API服务-交接说明.md) 收尾后可删
- [导出中心实施计划](docs/plans/导出中心-实施计划.md) — **待办**(2026-09-14 调研定稿):15 处导出统一登记可查
- [错误收集系统(错误中心)实施计划](docs/plans/错误收集系统-实施计划.md) — **已实施**:统一采集/落库/聚合/查看

### 其他文档

- [数据比对需求还原报告](docs/knowledge/req-compare.md) — 从代码逐行反推的数据比对业务规则细节(概览见 数据比对 wiki)
- `docs/reports/` — 手动验收报告(局域网共享端到端、SSH 隧道、数据比对接口测试),按需查阅

### 子模块自有文档

- [common/AGENTS.md](common/AGENTS.md) — 共享业务内核边界(禁 suspend、禁框架依赖、内部 JSON 只用 Jackson 2)、内核结构、Flyway 迁移规则
- [tauri/AGENTS.md](tauri/AGENTS.md) — Tauri 2 桌面壳:侧车协议、常驻托盘模型、自动更新、打包
