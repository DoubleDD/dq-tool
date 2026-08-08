# AGENTS.md — dq-tool 数据质量检测工具

> 本文件面向 AI 编码代理,描述该项目的一切背景。项目文档与代码注释统一使用中文。

## 项目概述

dq-tool 是一个轻量级单体 Web 应用,用于对关系型数据库做数据质量检测:

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫、Excel 导出(sheet 顺序:概览 / 表列表 / 「字段汇总」单 sheet 合并所有 DONE 表字段 / 每表字段明细多 sheet / 异常表,列可选,表名/表注释固定前列)
- AI 表说明:大模型(OpenAI 兼容接口)根据表结构生成表用途描述,页面配置接口信息,手动触发生成,也支持手动编辑,结果存 H2
- AI 自动打标:扫描时可选(auto_tag 随 scan_job 持久化,断点续扫仍生效);每张表 DONE 后由 `AutoTagService` 独立守护线程池(2 worker)异步执行(与扫描并行,不占扫描 worker)——以表注释/字段注释/AI 表描述为上下文(三者全空且表非空时抽样前 20 列 100 行业务数据,单元格截断 100 字符),连同全局 USER 标记列表发给大模型选一个标记自动打上(幂等 ensureTableTag,只增不删;表已有 USER 标记跳过不覆盖);未配置大模型/无候选标记静默跳过,同 job 首次 LLM 失败后熔断剩余表;前端扫描对话框与库列表整库扫描确认框均有复选(默认勾选 = `GET /api/ai-config` 的 available,即合并默认配置后有效配置完整)
- 表标记:全局表级标记(数据源+库+表名,一表多标记,名称+颜色);「空表」系统标记随扫描结果自动打/摘,用户标记在表列表打标弹窗集中管理;库列表标签块点击筛选 + 独立「标记统计」页(需求 `docs/requirements/表标记与统计需求.md`,实施见 `docs/plans/表标记与统计-实施计划.md`)
- 授权码:离线 Ed25519 签名授权码(payload 为 客户名|有效期|软件版本|server_url|username|sid|签发时间戳,`expires=permanent` 可签发永久授权,兼容两段/六段旧格式),未激活/过期时除 `/api/license/**` 外所有接口 401,前端强制跳激活页;签发用 `scripts/LicenseKeygen.java`(或 `make license`,扩展字段经 SERVER_URL/USERNAME/SID 传入,版本段默认取安装包版本,timestamp 自动取签发时间),公钥单独存文件 `server/src/main/resources/license-public.key`,`application.yml` 的 `dq.license.public-key-file` 只存文件路径(`classpath:` 前缀读 jar 内资源,否则按文件系统路径,兼容旧的内联 `dq.license.public-key`),私钥 `license-private.key` 不入库;**授权码管理(仅管理员)**:yml 配 `dq.license.private-key-file` 指向签发私钥即为管理员实例,开放 `/license-admin` 页与 `/api/license/admin/codes` 接口(生成/留档 `license_record` 表/查看含完整码/删除,不可编辑;SID 未填时服务端自动生成 UUID 去横杠,页面不再提供输入框;删除仅删留档,离线方案无法吊销已分发码;非管理员访问 403 `LicenseAdminRequiredException`),管理端点在 /api/license 前缀下不被激活拦截(管理员实例可不激活使用);软件版本号构建期由 gradle 去 `0.` 前缀注入 `app-version.txt`(与安装包版本一致),状态接口透出 `appVersion`/`admin`,首页底部页脚显示
- 数据源连接信息存本地 H2 文件库,密码 AES-GCM 加密
- 数据源 SSH 隧道:可配置经跳板机连接目标库(password/publickey 认证,私钥内容入库非文件路径;三个 SSH 秘密字段同样 AES-GCM 加密、编辑留空不改),内核 `SshTunnelService`(SSHJ 本地端口转发)+ `JdbcUrlRewriter`(JDBC URL host:port 解析/改写为 127.0.0.1:转发端口);连接池用长驻隧道(evictPool 一并关闭),测试连接/保存前模式探测用一次性隧道
- 数据源库过滤:数据源级库白名单(`data_source.schema_filter` 逗号分隔,空=不过滤),编辑对话框「库过滤」页签(DataGrip Schemas 页风格)拉取目标库库名勾选 —— 编辑态且连接信息未改(前端快照对比,改过连接字段即失效已拉列表)直接调已存数据源的 `databases`/`schemas` 接口(带 `all=true` 旁路白名单拿全量,切到该页签自动加载);新增态或编辑态改过连接信息时用 `POST /api/datasources/preview-databases` 按表单连接参数实时连接(请求带 id 时密码/SSH 秘密留空回落已存值;方言 `listDatabases` 为空时回落 `listSchemas`,因为只有 SQL Server 实现了多库列表);白名单作用层级按方言能力区分 —— 多库方言(`DbDialect.supportsMultiDatabase()`,仅 SQL Server)只过滤 `GET /api/datasources/{dsId}/databases`,单库方言(MySQL 等的 schema 即用户眼中的库)过滤 `listSchemas` 与 schema-stats 概览(写入与读取路径都过滤,旧缓存无需重建;MetadataService.applySchemaFilter);库列表页也有「库过滤」弹窗直接改白名单(`PUT /api/datasources/{id}/schema-filter` 单独更新该列,DataSourceService.updateSchemaFilter);系统库(information_schema/mysql/sys 等)可不勾;白名单随导出/导入 JSON 一并迁移
- 数据源导入/导出:数据源页可勾选导出为 JSON(密码与 SSH 秘密字段用内置固定口令 TransferCrypto 重加密,跨实例可导入,仅防随手打开),导入支持本工具 JSON 与 Navicat .ncx(NavicatCrypto 解 Ver 1.x AES-128-CBC 密码;SSH 隧道配置随数据源一并导入,SSH_PrivateKey 只是用户机器上的文件路径读不到内容,非空时提示导入后手动粘贴私钥),重名自动加「 (2)」后缀不覆盖;导入对话框分「文件导入/粘贴导入」两模式,粘贴模式支持 DataGrip「复制数据源到剪贴板」的 XML(带 `#DataSourceSettings#`/`#BEGIN#` 注释行包裹,密码在 IDE 主密码保险箱拿不到,导入后提示逐个编辑补密码)与本工具 JSON 文本,服务端按内容自动识别;逻辑在内核 `DataSourceTransferService`,接口 `GET /api/datasources/export?ids=`、`POST /api/datasources/import`(multipart,文件字段 `file` 或文本字段 `text`);导入的无密码数据源(DataGrip 导入必然无密码、JSON `passwordEnc` 为空时导入成功并带 warning)在数据源卡片上以「未设密码」标出 —— 列表接口从 `password_enc` 判空返回 `hasPassword`(新建密码必填、编辑留空沿用旧值,故无歧义状态,无需持久化列)
- 桌面安装版生命周期:双击启动立即出现托盘图标(TrayManager.installEarly,main 阶段安装)+ 启动画面(DesktopSplash),服务完全就绪后关启动画面并打开 --app 窗口(BrowserOpener);早期反馈只在显式 `-Djava.awt.headless=false`(打包脚本注入)时启用,普通 java -jar 不触发;托盘右键菜单「打开窗口/退出」,退出=关窗口+结束后端进程;托盘生效时后端以守护进程方式常驻、心跳看门狗停用;托盘不可用(headless/部分 Linux 桌面)时退回心跳看门狗(DesktopSession):前端每 5 秒上报 `/api/heartbeat`,超过 `dq.desktop.shutdown-timeout-seconds`(默认 45s,<=0 禁用)未收到心跳自动退出;只在本进程成功拉起 --app 窗口后才武装,java -jar 服务器部署不受影响;机器休眠超时也会误判退出,进行中的扫描中断后可断点续扫
- 架构决策(2026-08):**彻底放弃并删除 Kotlin Compose Desktop 与 JCEF shell 两套桌面方案**(Compose 生态不足、基础组件需自绘、工作量大;JCEF 体积约 200MB)。桌面分发只保留两条路:server 安装版浏览器 --app 窗口(jpackage)与 Tauri 2 套壳(`tauri/`)
- 注意:应用默认 headless(`DqApplication.main` 首行设置,与原 Spring Boot 行为一致),本地 `java -jar`/`./gradlew :server:run` 调试窗口与托盘需显式加 `-Djava.awt.headless=false`(打包脚本已注入);进程退出统一走 `AppShutdown`(停 Javalin → 关 Hikari → System.exit(0))
- 数据目录与日志:开发/`java -jar` 方式数据目录为 `./data`;安装版为 `~/.dq-tool/data`(带点隐藏目录,打包脚本注入 `-Ddq.data-dir`)。运行日志在 **数据目录同级的 `logs/` 目录**(如安装版 `~/.dq-tool/logs/`,规则单点 `StartupLog.logDirFor`,`-Ddq.log-dir` 可显式覆盖):`dq-tool.log` 追加写、按天滚动为 `dq-tool.yyyy-MM-dd.log`,单个文件最大 100MB、一天内超限按 `%i` 序号拆分,保留 30 天、总量上限 2GB(`SizeAndTimeBasedRollingPolicy`,配置见 `server/src/main/resources/logback.xml`,路径变量 `${dq.log-dir:-./logs}` 由 main 在任何日志输出前设置);启动早期日志(logback 初始化前 + 未捕获异常)在同目录 `startup.log`(`config/StartupLog`,仅追加)

支持 7 种数据库:MySQL、PostgreSQL、SQL Server、Oracle、达梦 DM8、人大金仓 KingbaseES、OceanBase(仅 MySQL 模式)。驱动全部来自 Maven 中央仓库。

无登录/权限控制,**仅适合内网单机部署**。

## 技术栈

- 构建:Gradle 多模块(根 wrapper 9.2.1),子模块 `common`(共享业务内核,Kotlin,见 common/AGENTS.md)、`server`(Java Javalin Web 壳),共享依赖版本统一在 `gradle/libs.versions.toml`;`web/` 与 `tauri/`(Tauri 2 桌面壳,另需 cargo)为独立 npm 工程,不纳入 Gradle
- 后端(server/):Java 25、Javalin 7(内嵌 Jetty 12 ee10)+ hibernate-validator + snakeyaml + Logback 的薄壳,只含入口/配置加载/Web 路由/桌面生命周期;**业务逻辑全部在 common 模块**(Kotlin),由 `config/KernelConfigAdapter` 把 yml 配置映射为内核 `AppConfig`,`web/WebServer` 装配内核 `ServiceEnv`;Jackson 3(`tools.jackson` + KotlinModule)只在 Web 层序列化内核 Kotlin data class。2026-08 已从 Spring Boot 4.1 迁移(方案见 `docs/plans/de-spring-javalin-改造方案.md`),同月业务代码下沉 common 后移除 spring-jdbc
- 前端:Vue 3 + Vue Router + Element Plus + axios,Vite 5 构建(无 TypeScript、无状态库,`stores/tabs.js`/`stores/theme.js` 为自写简易 store);明暗双主题:`html.dark` 类 + EP 官方暗色变量包,设计令牌与页面骨架类集中在 `src/style.css`,头栏右侧三档循环切换(自动跟随系统 prefers-color-scheme 并监听变化 / 浅色 / 深色,默认自动),选择存 localStorage(`dq-theme`),UI 颜色一律走 `--el-*`/`--dq-*` 变量不写死色值(用户可配的标记色、数据库图标品牌色除外)
- 测试:JUnit 5 + AssertJ + Testcontainers(MySQL 8 / PG 15 / SQL Server 2019);不使用 Spring 测试框架,集成测试手动装配
- 交付:shadow fat jar(内嵌前端);jpackage 生成 macOS dmg / Linux deb+rpm 安装包与 Windows 免安装 zip(均内嵌 JRE)

## 项目结构

```
Makefile                     常用命令快捷方式(make 查看全部:dev/build/run/package/clean;macOS 自动探测 JDK 25)
settings.gradle.kts          根多模块配置(include server、common;插件与依赖仓库)
build.gradle.kts             根:仅声明插件版本(kotlin/shadow,apply false)
gradle/libs.versions.toml    版本目录:两模块共享依赖版本(Javalin/Jackson2+3/Flyway 等全部显式钉,7 个 JDBC 驱动、POI、H2、testcontainers)
gradlew / gradle/wrapper/    Gradle 9.2.1 wrapper
common/                      共享业务内核(Kotlin,详见 common/AGENTS.md):config/dialect/model/repository/scan/service/license/util + env/ServiceEnv 组装器 + db/migration Flyway 迁移脚本
server/                      Java Web 版薄壳(Javalin):
  build.gradle.kts           后端构建(java + application + com.gradleup.shadow 插件 + project(":common");shadowJar 产物 server/build/libs/dq-tool-<version>.jar;processResources 拷 web/dist 到 static/;run workingDir=仓库根)
  src/main/java/com/example/dq/
    DqApplication.java       入口:StartupLog 启动早期日志(纯 JDK,main 第一行初始化,不依赖 logback,写 数据目录同级 logs/startup.log,覆盖读配置/端口探测/托盘/启动画面/内核装配/HTTP 监听/打开窗口每一步与未捕获异常,安装版卡死/闪退排障入口)→ 首行默认 headless=true(未显式设置时)→ ConfigLoader 加载配置 → 设置 dq.data-dir 系统属性(必须先于任何日志输出)→ InstanceLock 单实例保护(同数据目录已有实例时:桌面版打开已有实例窗口并 exit(0),headless 报错 exit(1);检测 instance.lock + H2 dqconfig.lock.db 双文件锁,后者识别旧版本残留进程,防止经 AUTO_SERVER 连上旧实例跨版本崩)→ 端口解析(--server.port= > SERVER_PORT > yml)与占用避让(向后探测 100 个,server.port=0 跳过)→ 装配 WebServer(内含内核 ServiceEnv:H2 池 + Flyway 迁移)并启动;main 全程 try/catch,启动失败落 startup.log + stderr 后 exit(1)
    config/                  DqProperties(dq.* 配置,纯 POJO)、AiProperties(ai.* 默认配置,纯 POJO)、ConfigLoader(snakeyaml 读 application.yml + 系统属性覆盖,展开 ${user.home})、KernelConfigAdapter(ConfigLoader.AppConfig → 内核 AppConfig 映射)、StartupLog(启动早期日志)、InstanceLock(单实例保护 + findRunningInstancePort 探测已运行实例端口)、AppShutdown(进程退出封装:停 Javalin → 关 Hikari → exit(0))、BrowserOpener(安装版自动开浏览器,优先 Chrome/Edge --app 应用模式,用独立 --user-data-dir ~/.dq-tool/browser-profile 保证进程句柄有效、退出时能关闭窗口;open/closeWindow 供托盘复用,reopenExisting 静态入口供第二实例带出已有窗口)、TrayManager(系统托盘图标:打开窗口/退出,图标运行时绘制,installEarly 支持 main 阶段提前安装;右键菜单全平台统一用 Swing JPopupMenu + 系统外观 —— Windows 的 AWT 原生菜单 peer 渲染中文必现方块,设字体无效;托盘图标本身只有 AWT SystemTray API)、DesktopSplash(安装版启动画面,就绪后由 BrowserOpener 关闭)、DesktopSession(页面心跳看门狗 ScheduledExecutorService,托盘不可用时的进程退出兜底)
    web/                     WebServer(装配内核 ServiceEnv + Javalin 路由注册 + 授权 beforeMatched 前置校验 + 统一异常映射为 {message}(401/400/409/502/500,前端 axios 拦截器直接弹 message)+ 静态资源与 SPA 回退)、Validators(@Valid 替代的校验工具)、ValidationException(继承 IllegalArgumentException,走 400 映射)
    controller/              REST handler(方法签名为 Javalin Context,路由集中在 WebServer 注册;消费内核 Kotlin service/model):/api/datasources、/api/scans、/api/ai-config、/api/license(授权状态/激活,不被拦截)、/api/heartbeat(页面心跳,不被拦截)、/api/tags(TagController:标记 CRUD + {id}/stats),以及 /api/datasources/{dsId}/ 下的 databases、schemas、schema-stats、schema-tag-stats、schemas/{schema}/{tables,column-count,latest-scan-jobs,running-scans,table-docs,table-tags}、schemas/{schema}/tables/{table}/doc(POST 生成 / PUT 手动编辑)与 schemas/{schema}/tables/{table}/tags(PUT 整体替换打标)
  src/main/resources/
    application.yml          全部可调配置(server.port / dq.data-dir 数据目录 / dq.scan.* / dq.security.secret / dq.license.public-key-file 授权公钥文件路径 / dq.license.private-key-file 签发私钥文件路径(配了即管理员实例) / ai.* 默认配置)
    license-public.key       授权码验签公钥(Ed25519 base64,单行文件;由 scripts/LicenseKeygen.java --gen-keypair 生成)
    app-version.txt          软件版本号占位文件,构建期由 gradle 注入(去 0. 前缀,与安装包版本一致)
    logback.xml              日志配置:控制台 + 数据目录 logs/ 按天滚动(dq-tool.yyyy-MM-dd.log,30 天,100MB/2GB);路径变量 ${dq.data-dir:-./data},变量名必须与打包脚本注入的系统属性一致
  src/test/java/com/example/dq/
    web/WebServerSmokeTest.java  WebServer 起停 + 关键端点冒烟(临时数据目录,验证内核装配/Flyway/Jackson 3 序列化 Kotlin 模型/授权拦截/表标记端点全链路;授权用测试临时 Ed25519 密钥对签发永久授权码激活)
tauri/                       Tauri 2 桌面壳(Rust 侧车拉起 java -jar server fat jar 子进程 + 系统 WebView 加载 Web UI;常驻模型 —— 关窗只隐藏、托盘退出才杀后端,单实例;headless 默认值天然抑制 Java 侧浏览器/托盘;npm + cargo 工程不纳入 Gradle;详见 tauri/AGENTS.md)
web/                         前端 Vue 工程:
  src/views/                 九个页面:Dashboard(任务看板)、Datasources、Schemas、Tables、Scans、ScanDetail、TableColumns、TagStats(标记统计,/tags)、Activate(授权激活,全屏独立页不进页签体系)
  src/components/            AiConfigDialog、DbTypeIcon、ExportButton、JobTimeline(对应 scan_job_event 的任务时间线)、LicenseFooter(首页底部授权信息 + 更换授权码弹窗)、TableTagDialog(表打标弹窗:勾选 + 标记新建/改名/改色/删除)
  src/api/index.js           axios 封装(统一错误弹窗;401 非授权接口时整页跳 /activate),非 API 方法集合
  src/router/                beforeEach 授权守卫(原生 fetch 查 /api/license/status 并缓存,避免与 axios 循环依赖) src/stores/ src/utils/ src/assets/dbicons/(数据库 SVG 图标)
docker-test-env/             手动验证用的 SQL Server / Oracle docker-compose(非 CI 使用)
scripts/                     package-mac.sh / package-win.bat / package-linux.sh(jpackage 打包,--main-class com.example.dq.DqApplication)、package-tauri-{mac.sh,win.bat}(Tauri 套壳的 dmg / Windows NSIS 打包)、LicenseKeygen.java(授权码签发工具,纯 JDK 源码模式运行;Makefile 提供 make license-keypair / make license 快捷命令)
.github/workflows/release.yml  推 v* tag 或 workflow_dispatch 手动触发全平台安装包构建
data/                        运行期生成的 H2 数据文件(勿提交改动)
```

## 构建与运行

要求:JDK 25+、Node 24+(仅开发模式)。构建用仓库自带 Gradle wrapper,无需安装 Maven/Gradle。

```bash
# 开发模式:后端 10000 + 前端 5173(代理 /api 到 10000)
./gradlew :server:run
cd web && npm install && npm run dev

# 交付:单 jar 内嵌前端 —— 必须先构建前端,再 shadowJar
cd web && npm install && npm run build     # 产物 web/dist
cd .. && ./gradlew :server:shadowJar        # processResources 自动把 web/dist 拷进 jar 的 static/
java -jar server/build/libs/dq-tool-0.1.6.jar   # 访问 http://localhost:10000
```

注意:`./gradlew :server:shadowJar` 不会自动构建前端;`web/dist` 缺失或过期时 jar 内静态资源即为旧版/缺失。

所有 JVM 启动入口(gradle run、Makefile、jpackage 安装包)已统一使用 ZGC(`-XX:+UseZGC`,JDK 25 默认即为分代模式,无需其他参数)。

日常操作也可以用根目录的 `Makefile`(`make` 查看全部):`make dev` / `make dev-web`(开发;`dev`/`dev-headless` 会在 `web/src` 有改动时先自动重建 `web/dist`,纯前端调试热更新用 `dev-web`)、`make build` / `make run`(jar)、`make test`、`make package`(mac dmg)/ `make package-linux`(deb)/ `make package-win`(Windows 免安装 zip,仅 Windows 可用)、`make tauri` / `make package-tauri`(Tauri 2 套壳版开发运行 / Tauri 套壳 mac dmg,Windows 对应 `make package-tauri-win`,均带 `-skip` 跳过构建变体)、`make clean`;macOS 上会自动探测 JDK 25 覆盖 JAVA_HOME,`dev`/`run` 显式带 `-Djava.awt.headless=false`(否则应用默认 headless,窗口和托盘都不会启动),服务器方式调试用 `make dev-headless` / `make run-headless`。

## 测试

```bash
./gradlew :common:test :server:test
```

- common 模块(业务测试主战场):方言 SQL 生成、规则谓词转义、分段键选择、分段累加 == 全表单条 SQL、任务删除级联清理(ScanJobDeleteTest)、授权码编解码(LicenseCodecTest)、表标记(TagServiceTest/TagRepositoryTest:重名冲突、空表标记保护与联动、级联解除、统计口径)、Flyway 迁移新库/老库/已最新跳过三条路径(FlywayMigrationTest)、Jackson 3 消费 Kotlin 模型的 spike(Jackson3KotlinSpikeTest)
- server 模块:WebServer 起停 + 关键端点冒烟(WebServerSmokeTest,临时数据目录)
- Testcontainers 集成测试(需要 Docker):MySQL 8 / PG 15 / SQL Server 2019 真实容器上的并发分段全链路、空值规则、注释、导出
  - OrbStack 用户若报 "Could not find a valid Docker environment":
    `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew :common:test`
  - Testcontainers 版本固定 1.21.4(gradle/libs.versions.toml 有注释:旧版默认 API 1.32 被 Docker 29+ 拒绝;不用 2.x,模块坐标有变化)
- **达梦 / 人大金仓 / OceanBase / Oracle 无自动化覆盖**,相关方言改动只能接真实环境手动验证(`docker-test-env/` 有 SQL Server 和 Oracle 的 compose)
- **LLM 实际调用无自动化覆盖**(prompt 组装有单测),AI 表说明功能需配置真实接口后手动验证

## 代码约定

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- 分层:controller(薄,Javalin handler)→ service(业务)→ repository(内核 Jdbc.kt 薄封装)→ dialect(库差异抽象)。**所有数据库差异都必须收敛在 `dialect/` 包内**,service/scan 层不允许出现库特定的 SQL 或分支
- **业务代码只在 common 模块(Kotlin)写一份**,server 壳层只消费;内核边界约定见 common/AGENTS.md(禁 suspend、禁框架依赖、内部 JSON 只用 Jackson 2)
- **server 无 Spring 容器、无注解扫描、无 DI 框架**:业务组件由内核 `ServiceEnv` 构造装配,server 侧只装配 Web/桌面生命周期组件(WebServer 构造器);需要事务时用内核 `Jdbc.tx`
- 新增数据库支持 = 实现 `DbDialect` 接口 + 在 `DialectFactory` 注册 + `DbType` 枚举 + `gradle/libs.versions.toml` 加驱动 + README 更新
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 修改库表结构时:在 `common/src/main/resources/db/migration/` 新增 `V{n}__描述.sql`(Flyway),已发布的迁移文件禁止修改,不允许破坏性变更
- 配置新增:在 `application.yml` 加键 + `DqProperties`/`AiProperties` 加字段 + `ConfigLoader` 加显式绑定(无反射绑定)+ `KernelConfigAdapter` 映射进内核 `AppConfig`
- 前端无 lint/格式化配置,跟随现有代码风格(2 空格缩进、单文件组件)
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug
- Oracle 表体积统计依赖段视图:23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在);受限账号看不到段视图时(无权限对象 Oracle 也报 ORA-00942)按 ALL_SEGMENTS → DBA_SEGMENTS → USER_SEGMENTS(仅当前用户)→ 不统计 逐级降级(23ai 链从 DBA_SEGMENTS 起),记 warn 日志;探测结果按「用户名@JDBC URL」内存缓存(OracleDialect.segViewCache,换账号/换服务器自动重探,进程重启重置),非首选落点超过 1 小时(SEG_VIEW_REPROBE_MS)从链头重探一次以捕获权限变更
- 所有错误(程序异常、业务错误)都必须落日志:WebServer 异常映射各分支 warn(带堆栈)/error,业务 catch 不允许静默吞掉;预期内的降级(驱动能力缺失、DM/Oracle ALL_SEGMENTS 受限)用 debug/warn 说明降级后果;唯一例外是未激活期间的授权拦截(每请求触发,只记 debug 防刷屏)

## 安全考虑

- 数据源密码用 AES-GCM 加密存 H2(`password_enc` 列),密钥在 `application.yml` 的 `dq.security.secret`,默认值 `change-me-...`,改动密钥逻辑时注意向后兼容已存数据
- AI 配置的 API Key 同样 AES-GCM 加密存 H2(`ai_config.api_key_enc`),GET 接口只回传 hasKey 不回传明文;`application.yml` 的 `ai.*` 是默认配置(AiProperties 绑定),页面未设置的字段逐字段回落到默认值(默认 key 为明文,仅适合内网);默认配置不对外暴露 —— 不回显、报错不带细节,默认不可用时只提示用户自行配置;「生成表说明」会把表结构元数据(表名/字段/注释)发给配置的第三方 LLM 接口,不发送业务数据,属用户显式触发;「AI 自动打标」在表无任何注释/描述时会把抽样业务数据(前 20 列、100 行、单元格截断 100 字符)一并发给 LLM,由用户在扫描对话框/整库扫描确认框显式勾选授权(UI 复选框 tooltip 已明示)
- 授权码为离线 Ed25519 验签:私钥 `license-private.key` 只存分发方本地(.gitignore 已排除),公钥在 `license-public.key` 文件(yml 只配路径 `dq.license.public-key-file`);配置 `dq.license.private-key-file` 的实例即管理员(开放授权码管理),**私钥内容只进配置对象,不进日志、不回传任何接口**;激活后授权码 AES-GCM 加密存 H2(`license_info.code_enc`),状态接口只回传客户标识/有效期/username/sid/签发时间/admin/appVersion,不回传授权码,**server_url 属敏感信息禁止回传前端**(`LicenseStatusView` 刻意不含该字段;仅管理员实例的 `/api/license/admin/codes` 留档可见);到期日当天仍有效;纯离线方案防不了逆向破解,仅作分发门槛
- 应用无认证,任何能访问端口的人都能操作所有数据源,不要暴露到公网
- 启动时重新允许 TLS 1.0/1.1(`config/LegacyTlsSupport`,从 `jdk.tls.disabledAlgorithms` 摘掉 TLSv1/TLSv1.1,由 JVM 入口 DqApplication 调用):兼容未打 TLS 1.2 补丁的老 SQL Server(mssql-jdbc 登录阶段强制 TLS 握手,encrypt=false 绕不过);全局生效,支持 TLS 1.2+ 的对端仍优先协商高版本
- 自定义空值规则(如 `status IN (0,-1)`)会原样拼进统计 SQL,属设计如此的"信任内网用户"行为;不要在方言层之外引入新的 SQL 拼接,标识符必须经 `DbDialect.quote()` 处理
- H2 数据文件含连接信息与扫描结果,`data/` 目录不应提交或外发

## 发布流程

- 推 `v*` tag(如 `git tag v1.2 && git push origin v1.2`)触发 `.github/workflows/release.yml`;也支持 workflow_dispatch 手动触发(产物以 artifact 下载,保留 30 天,名称带版本号)
- **tauri 安装包自动更新**(tauri-plugin-updater,详见 tauri/AGENTS.md):覆盖 Windows(NSIS 安装包 `setup.exe` 本身即更新包,tauri CLI 2.11+ 对 v2 updater 不再打 `.nsis.zip`,直接签出 `setup.exe.sig`;updater 插件 2.x 支持裸 exe 下载安装)与 macOS(`.app.tar.gz`,仅 Apple Silicon;mac 打包须 `--bundles app,dmg`,dmg 本身不产生更新包);各构建任务产出更新包 + `.sig` 挂 Release,`latest.json` 由收尾任务 `updater-manifest` 合并全平台签名生成,应用启动时后台预下载、弹窗确认后重启生效;签名私钥直接入库 `scripts/updater-private.key`(单行 base64 空口令,环境须显式给 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD=` 空值否则 CLI 交互式询问失败;分发方多机打包需要;仓库公开,验签仅作形式约束),公钥在 tauri.conf.json;**每次发版必须递增 tauri.conf.json 的 version(semver 比较)**
- 并行构建:Windows 免安装 zip(x64 + ARM64,jpackage `--type app-image`,**已不用 exe/WiX**)、macOS dmg(Apple Silicon + Intel,Intel 用 macos-15-intel runner)、Linux deb + rpm,tag 触发时全部挂到 GitHub Release。**当前 CI 启用:Windows x64 jpackage zip + windows-tauri(Tauri 2)NSIS 安装包(tauri 启动闪退 2026-08 已定位修复:`bundled_resources_dir` 漏查 Tauri 2 实际落盘的 `<exe>/resources/` 目录,安装版找不到内嵌 jar 直接退出)+ macos-tauri dmg/更新包(仅 Apple Silicon,Intel 已在矩阵与 updater-manifest 同步注释,需要时两边一起恢复);ARM64 / macOS jpackage / Linux 任务在 release.yml 中注释停用,需要时取消注释恢复**
- jpackage 不支持交叉编译,各平台包在对应系统的 runner 上原生构建
- 本地打包用 `scripts/package-{mac,linux}.sh` / `scripts\package-win.bat`(内部走 `./gradlew :server:shadowJar` + jpackage `--main-class com.example.dq.DqApplication`),可加 `--skip-build` 只重打包;Linux 脚本默认打 deb,加 `--type rpm` 打 rpm(需 rpmbuild);`package-win.bat` 为 UTF-8 中文注释文件,**单行 `if (...)` 块内禁止写中文**(中文 Windows 上 cmd 按 GBK 解析,错位字节配对会破坏块结构,多行块则安全);更准确的教训(2026-08 实测):UTF-8 中文注释被 cmd 按 GBK 误读时,任何错位字节配对出危险尾字节(0x7C `|`、0x5E `^` 等)的注释行都会破坏解析,是否踩中纯属字节运气——**`package-tauri-win.bat` 的注释已全面改为 ASCII 英文,功能性 .bat 新增注释一律用英文**,中文说明放 AGENTS.md,重复打包自动清理旧 app-image 目录;**.bat 还必须保持 CRLF 行尾**(core.autocrlf=true 下 git 检出即 CRLF,编辑器/写入工具不要写成 LF):LF-only 的 .bat 在 cmd 下 `call` 子脚本返回后按字节偏移恢复解析会错位,2026-08 实测错位落在 "build" 中间,把 `ild` 当命令执行报「'ild' 不是内部或外部命令」,整个构建只剩最后打包一步前功尽弃;`JAVA_HOME` 未设时自动探测常见位置的 JDK 25+(`~/.jdks`、Program Files 各发行版),`TMP`/`TEMP` 缺失时回落到 `build\tmp`(经 MSYS2 的 make 调 bat 环境变量会被剥光,不设 TMP 时 Java tmpdir 回落 `C:\Windows` 导致 Kotlin 编译写 .alive 失败)
- 安装包内嵌**完整 JRE**(全部 69 个运行库模块,非 jdeps/jlink 裁剪):复制本机 JDK 后只删开发工具(bin 工具启动器 + jmods),经 jpackage `--runtime-image` / tauri resources 打进包。不做模块裁剪的原因:JDBC 驱动大量反射/按名加载,静态分析覆盖不全 —— 实测达梦驱动初始化要 `jdk.charsets` 的 EUC-KR,裁剪后运行时才炸(2026-08);复制+裁剪不依赖 jmods(部分 JDK 发行版无 jmods,jlink 不可用)
- 安装包要求主版本号 ≥ 1,脚本把项目版本 `0.1.6` 映射为安装包版本 `1.6`
- 安装版数据目录固定为 `~/.dq-tool/data`(jar 方式为 `./data`),由打包脚本注入 `--java-options "-Ddq.data-dir=..."`(H2 URL 与日志路径都引用该配置,`${user.home}` 由 ConfigLoader 启动时展开),修改打包脚本时保持这一区分
- 运行日志输出到数据目录 `logs/` 子目录,按天滚动(`dq-tool.yyyy-MM-dd.log`,保留 30 天,配置见 `logback.xml`);Windows 包无控制台窗口,日志文件是唯一排障入口;启动早期日志(logback 初始化前 + 未捕获异常)在同目录 `startup.log`(`config/StartupLog`,main 第一行初始化,追加不滚动,量极小)
