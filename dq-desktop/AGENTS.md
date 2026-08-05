# AGENTS.md — dq-desktop(Compose Desktop 桌面版)

> 本文件面向 AI 编码代理。这是 dq-tool 的 Kotlin + Compose Desktop 重写版,项目文档与代码注释统一使用中文。原 Web 版(Spring Boot + Vue)见仓库根目录的 AGENTS.md。

## 项目概述

与 Web 版相同的业务:关系型数据库数据质量检测(表级行数/体积估算、字段级 NULL/空串/自定义空值规则统计、大表并发分段扫描、断点续扫、Excel 导出、AI 表说明)。差异在形态:

- **纯 JVM 桌面单体**,无 Web 前端、无 REST 层、无 Spring;UI 直接调用 service
- UI 用 JetBrains Compose Multiplatform Desktop + Material3,多页签模型(平移自原 web/src/stores/tabs.js)
- 无登录/权限控制,仅适合内网单机使用

## 技术栈

- Kotlin 2.4、Compose Multiplatform 1.11.1(Gradle 插件)、kotlinx-coroutines(UI 异步/轮询)
- H2 文件库 + HikariCP;手写 JDBC 薄封装 `repository/Jdbc.kt`(无 ORM、无 JdbcTemplate)
- Jackson(jackson-module-kotlin)、Apache POI(SXSSF 流式导出)、SLF4J + Logback
- 扫描并发保留原 `ThreadPoolExecutor` 模型(阻塞 JDBC,不用协程)
- 打包:Compose 插件 `packageDmg/packageMsi/packageDeb`(内嵌 JRE),替代原 jpackage 脚本
- Gradle 9.2.1 wrapper,JDK toolchain 25

## 项目结构

```
build.gradle.kts           全部依赖与打包配置(7 个 JDBC 驱动版本与原 pom.xml 对齐)
src/main/kotlin/com/example/dq/
  Main.kt                  入口:AppConfig.load() → AppEnv 组装 → Compose 窗口
  config/AppConfig.kt      配置(默认值 + data/config.properties 覆盖;替代 application.yml)
  dialect/                 DbDialect + AbstractDialect + 7 方言 + DialectFactory(object 单例)
  model/                   data class/enum(与 Java 版同名同字段)
  repository/              Jdbc.kt 薄封装 + 5 个仓储 + SchemaInit(启动建表/老库升级)
  scan/                    ScanExecutor(线程池)/ ChunkRunner(分段执行)/ InterruptRecovery(main 显式调用)
  service/                 7 个服务(构造器注入,AiService 用 JDK HttpClient)
  util/CryptoUtil.kt       AES-GCM(与 Web 版算法逐行等价,可解密老库密文)
  ui/
    AppEnv.kt              服务容器(替代 Spring DI,构造顺序即依赖顺序)
    TabsModel.kt + Screen.kt   多页签 + 页签内下钻导航(含回退栈)
    theme/                 Material3 主题 + 状态色(对齐 Element Plus 配色)
    components/            DataTable/StatusTag/ConfirmDialog/JobTimeline/ExportButton/AiConfigDialog/Format.kt
    views/                 Datasources/Dashboard/Schemas/Tables/Scans/ScanDetail/TableColumns
src/main/resources/
  schema.sql               与 Web 版同一份(含 ALTER ... IF NOT EXISTS 老库升级)
src/test/kotlin/           单元测试 + Testcontainers 端到端(MySQL/PG/SQLServer)
```

## 构建与测试

```bash
./gradlew run / test / packageDmg
```

- Testcontainers 需 Docker;OrbStack 用户:`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew test`
- 达梦/金仓/OceanBase/Oracle 方言仍无自动化覆盖,改动只能接真实环境手动验证
- LLM 实际调用无自动化覆盖(prompt 组装有单测 AiServiceTest)

## 代码约定

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- **所有数据库差异收敛在 `dialect/` 包**,service/scan 层不允许出现库特定 SQL
- 标识符必须经 `DbDialect.quote()`;自定义空值规则原样拼 SQL(信任内网用户,同 Web 版)
- 扫描调度单元是分段(chunk),状态持久化在 scan_chunk,断点续扫只重跑未完成分段
- 修改库表结构:`schema.sql` 用 IF NOT EXISTS + 文件末尾追加 ALTER,与 Web 版保持兼容(两版共享库文件口径)
- UI:服务调用全部阻塞,必须 `withContext(Dispatchers.IO)`;轮询用 `LaunchedEffect { while(isActive){ load(); delay(...) } }`,切走页签自动停止
- 页签导航:固定页签 home/dashboard;数据源、扫描任务各占一个可关闭页签;页签内用 `TabsModel.navigate/back` 下钻
- 数据源密码与 AI Key 用 AES-GCM 加密存 H2,GET 只回 hasKey 不回显;密钥在 config.properties 的 dq.security.secret,改动时注意向后兼容已存数据
- data/ 目录含连接信息与扫描结果,不应提交或外发(见 dq-desktop/.gitignore)

## 与 Web 版的已知功能差异

- 导出列选择对话框保留(ExportButton);Scans 页导出为默认全列
- 表格排序仅 Tables/TableColumns 页支持(自绘 SortableTable)
- 无浏览器/端口避让逻辑;卡片无数据库 SVG 图标(文字标签代替)
