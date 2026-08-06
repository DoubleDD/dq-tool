# AGENTS.md — dq-tool 数据质量检测工具

> 本文件面向 AI 编码代理,描述该项目的一切背景。项目文档与代码注释统一使用中文。

## 项目概述

dq-tool 是一个轻量级单体 Web 应用,用于对关系型数据库做数据质量检测:

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫、Excel 导出(概览/表列表/每表字段明细多 sheet,列可选,表名/表注释固定前列)
- AI 表说明:大模型(OpenAI 兼容接口)根据表结构生成表用途描述,页面配置接口信息,手动触发生成,也支持手动编辑,结果存 H2
- 授权码:离线 Ed25519 签名授权码(含客户标识+有效期,`expires=permanent` 可签发永久授权),未激活/过期时除 `/api/license/**` 外所有接口 401,前端强制跳激活页;签发用 `scripts/LicenseKeygen.java`(或 `make license`),公钥在 `application.yml` 的 `dq.license.public-key`,私钥 `license-private.key` 不入库
- 数据源连接信息存本地 H2 文件库,密码 AES-GCM 加密
- 桌面安装版生命周期:双击启动立即出现托盘图标(TrayManager.installEarly,main 阶段安装)+ 启动画面(DesktopSplash),服务完全就绪后关启动画面并打开 --app 窗口(BrowserOpener);早期反馈只在显式 `-Djava.awt.headless=false`(打包脚本注入)时启用,普通 java -jar 不触发;托盘右键菜单「打开窗口/退出」,退出=关窗口+结束后端进程;托盘生效时后端以守护进程方式常驻、心跳看门狗停用;托盘不可用(headless/部分 Linux 桌面)时退回心跳看门狗(DesktopSession):前端每 5 秒上报 `/api/heartbeat`,超过 `dq.desktop.shutdown-timeout-seconds`(默认 45s,<=0 禁用)未收到心跳自动退出;只在本进程成功拉起 --app 窗口后才武装,java -jar 服务器部署不受影响;机器休眠超时也会误判退出,进行中的扫描中断后可断点续扫
- 注意:Spring Boot 默认把 `java.awt.headless` 设为 true,本地 `java -jar`/`./gradlew :server:bootRun` 调试窗口与托盘需显式加 `-Djava.awt.headless=false`(打包脚本已注入)

支持 7 种数据库:MySQL、PostgreSQL、SQL Server、Oracle、达梦 DM8、人大金仓 KingbaseES、OceanBase(仅 MySQL 模式)。驱动全部来自 Maven 中央仓库。

无登录/权限控制,**仅适合内网单机部署**。

## 技术栈

- 构建:Gradle 多模块(根 wrapper 9.2.1),子模块 `server`(Java Web 版)与 `desktop`(Kotlin Compose 桌面重写版,见 desktop/AGENTS.md),共享依赖版本统一在 `gradle/libs.versions.toml`;`web/` 为独立 npm 工程,不纳入 Gradle
- 后端(server/):Java 25、Spring Boot 4.1.0(web / jdbc / validation,内嵌容器用 Jetty 而非默认 Tomcat,本地单机更轻量;Undertow 不支持 Servlet 6.1 已被移除)、Jackson 3(`tools.jackson` 包名,Boot 4 起默认)、H2(本地存储)、Apache POI(Excel 导出)
- 前端:Vue 3 + Vue Router + Element Plus + axios,Vite 5 构建(无 TypeScript、无状态库,`stores/tabs.js` 为自写简易 store)
- 测试:JUnit 5 + Spring Boot Test + Testcontainers(MySQL 8 / PG 15 / SQL Server 2019)
- 交付:Spring Boot fat jar(内嵌前端);jpackage 生成 macOS dmg / Linux deb+rpm 安装包与 Windows 免安装 zip(均内嵌 JRE)

## 项目结构

```
Makefile                     常用命令快捷方式(make 查看全部:dev/build/run/package/clean;macOS 自动探测 JDK 25)
settings.gradle.kts          根多模块配置(include server、desktop;插件与依赖仓库)
build.gradle.kts             根:仅声明插件版本(kotlin/compose/spring-boot,apply false)
gradle/libs.versions.toml    版本目录:server/desktop 共享依赖版本(7 个 JDBC 驱动、POI、H2、testcontainers)
gradlew / gradle/wrapper/    Gradle 9.2.1 wrapper(Spring Boot 4.1 官方要求 8.14+ 或 9.x)
server/                      Java Web 版(Spring Boot):
  build.gradle.kts           后端构建(BOM 用 platform(SpringBootPlugin.BOM_COORDINATES);bootJar 产物 server/build/libs/dq-tool-<version>.jar;processResources 拷 web/dist 到 static/;bootRun workingDir=仓库根)
  src/main/java/com/example/dq/
    DqApplication.java       入口;启动时自动避让被占用的端口(10000 起向后探测 100 个;支持 --server.port= 参数与 SERVER_PORT 环境变量,server.port=0 时跳过避让)
    config/                  DqProperties(dq.* 配置绑定)、AiProperties(ai.* 默认配置绑定)、SpaWebConfig(SPA 路由回退 + 注册授权拦截器)、LicenseInterceptor(/api/** 未激活/过期抛 401)、BrowserOpener(安装版自动开浏览器,优先 Chrome/Edge --app 应用模式,用独立 --user-data-dir ~/.dq-tool/browser-profile 保证进程句柄有效、退出时能关闭窗口;open/closeWindow 供托盘复用)、TrayManager(系统托盘图标:打开窗口/退出,图标运行时绘制,installEarly 支持 main 阶段提前安装;右键菜单全平台统一用 Swing JPopupMenu + 系统外观 —— Windows 的 AWT 原生菜单 peer 渲染中文必现方块,设字体无效;托盘图标本身只有 AWT SystemTray API)、DesktopSplash(安装版启动画面,就绪后由 BrowserOpener 关闭)、DesktopSession(页面心跳看门狗,托盘不可用时的进程退出兜底)
    controller/              REST API:/api/datasources、/api/scans、/api/ai-config、/api/license(授权状态/激活,不被拦截)、/api/heartbeat(页面心跳,不被拦截),以及 /api/datasources/{dsId}/ 下的 databases、schemas、schema-stats、schemas/{schema}/{tables,column-count,latest-scan-jobs,running-scans,table-docs}、schemas/{schema}/tables/{table}/doc(POST 生成 / PUT 手动编辑);GlobalExceptionHandler 统一异常映射为 {message}(401/400/409/502/500),前端 axios 拦截器直接弹 message
    dialect/                 核心抽象 DbDialect + 7 个方言实现 + DialectFactory + AbstractDialect
    license/                 LicenseCodec 授权码编解码与 Ed25519 验签(纯函数;格式 DQ1.<base64url(客户名|yyyy-MM-dd)>.<base64url(签名)>)
    model/                   DTO/枚举(DbType、ScanStatus、NullRule、Range 等)
    repository/              手写 JdbcTemplate 仓储(无 JPA/MyBatis)
    scan/                    ScanExecutor(全局线程池,调度单元=分段)、ChunkRunner(分段执行)、InterruptRecovery
    service/                 DataSourceService(连接池/密码加解密)、MetadataService、ScanService(任务生命周期)、ExportService(POI 流式导出)、AiService(OpenAI 兼容 LLM 调用)、AiConfigService/TableDocService(AI 表说明)、LicenseService(授权验签/激活/状态缓存)
    util/CryptoUtil.java     AES-GCM 加解密
  src/main/resources/
    application.yml          全部可调配置(dq.data-dir 数据目录 / dq.scan.* / dq.security.secret / ai.* 默认配置 / logging.* 按天滚动文件日志)
    schema.sql               H2 建表(10 张表,含 scan_job_event 任务状态变更事件表、schema_stat 库列表统计缓存、ai_config/table_doc AI 表说明、license_info 授权信息单行表)+ 老库升级的 ALTER ... IF NOT EXISTS,启动自动执行
  src/test/java/com/example/dq/
    dialect/                 方言 SQL 生成、分段规划单元测试
    license/LicenseCodecTest.java  授权码签发/验签/篡改/过期判定单元测试(不需要 Docker)
    service/AiServiceTest.java AI 表说明 prompt 组装单元测试
    repository/ScanJobDeleteTest.java  H2 内存库验证任务删除的级联清理(分段/字段/表/任务四级,不需要 Docker)
    scan/ScanFlowTest.java   Testcontainers 端到端(MySQL/PG/SQLServer 三容器)
desktop/                     Kotlin + Compose Desktop 桌面重写版(同一 Gradle 构建的子模块,业务口径与 Web 版一致;详见 desktop/AGENTS.md)
web/                         前端 Vue 工程:
  src/views/                 八个页面:Dashboard(任务看板)、Datasources、Schemas、Tables、Scans、ScanDetail、TableColumns、Activate(授权激活,全屏独立页不进页签体系)
  src/components/            AiConfigDialog、DbTypeIcon、ExportButton、JobTimeline(对应 scan_job_event 的任务时间线)、LicenseFooter(首页底部授权信息 + 更换授权码弹窗)
  src/api/index.js           axios 封装(统一错误弹窗;401 非授权接口时整页跳 /activate),非 API 方法集合
  src/router/                beforeEach 授权守卫(原生 fetch 查 /api/license/status 并缓存,避免与 axios 循环依赖) src/stores/ src/utils/ src/assets/dbicons/(数据库 SVG 图标)
docker-test-env/             手动验证用的 SQL Server / Oracle docker-compose(非 CI 使用)
scripts/                     package-mac.sh / package-win.bat / package-linux.sh(jpackage 打包)、LicenseKeygen.java(授权码签发工具,纯 JDK 源码模式运行;Makefile 提供 make license-keypair / make license 快捷命令)
.github/workflows/release.yml  推 v* tag 或 workflow_dispatch 手动触发全平台安装包构建
data/                        运行期生成的 H2 数据文件(勿提交改动)
```

## 构建与运行

要求:JDK 25+、Node 24+(仅开发模式)。构建用仓库自带 Gradle wrapper,无需安装 Maven/Gradle。

```bash
# 开发模式:后端 10000 + 前端 5173(代理 /api 到 10000)
./gradlew :server:bootRun
cd web && npm install && npm run dev

# 交付:单 jar 内嵌前端 —— 必须先构建前端,再 bootJar
cd web && npm install && npm run build     # 产物 web/dist
cd .. && ./gradlew :server:bootJar          # processResources 自动把 web/dist 拷进 jar 的 static/
java -jar server/build/libs/dq-tool-0.1.3.jar   # 访问 http://localhost:10000
```

注意:`./gradlew :server:bootJar` 不会自动构建前端;`web/dist` 缺失或过期时 jar 内静态资源即为旧版/缺失。

日常操作也可以用根目录的 `Makefile`(`make` 查看全部):`make dev` / `make dev-web`(开发)、`make build` / `make run`(jar)、`make test`、`make package`(mac dmg)/ `make package-linux`(deb)、`make clean`;macOS 上会自动探测 JDK 25 覆盖 JAVA_HOME,`dev`/`run` 显式带 `-Djava.awt.headless=false`(否则 Spring Boot 默认 headless,窗口和托盘都不会启动),服务器方式调试用 `make dev-headless` / `make run-headless`。

## 测试

```bash
./gradlew :server:test
```

- 单元测试:方言 SQL 生成、规则谓词转义、分段键选择(不需要 Docker)
- H2 分段正确性:分段累加 == 全表单条 SQL
- H2 任务删除级联清理(ScanJobDeleteTest,不需要 Docker)
- Testcontainers 集成测试(需要 Docker):MySQL 8 / PG 15 / SQL Server 2019 真实容器上的并发分段全链路、空值规则、注释、导出
  - OrbStack 用户若报 "Could not find a valid Docker environment":
    `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew :server:test`
  - Testcontainers 版本固定 1.21.4(gradle/libs.versions.toml 有注释:旧版默认 API 1.32 被 Docker 29+ 拒绝;不随 Spring Boot 4.1 BOM 管理的 2.x 走)
- **达梦 / 人大金仓 / OceanBase / Oracle 无自动化覆盖**,相关方言改动只能接真实环境手动验证(`docker-test-env/` 有 SQL Server 和 Oracle 的 compose)
- **LLM 实际调用无自动化覆盖**(prompt 组装有单测),AI 表说明功能需配置真实接口后手动验证

## 代码约定

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- 分层:controller(薄)→ service(业务)→ repository(手写 JdbcTemplate)→ dialect(库差异抽象)。**所有数据库差异都必须收敛在 `dialect/` 包内**,service/scan 层不允许出现库特定的 SQL 或分支
- 新增数据库支持 = 实现 `DbDialect` 接口 + 在 `DialectFactory` 注册 + `DbType` 枚举 + `gradle/libs.versions.toml` 加驱动 + README 更新
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 修改库表结构时:`schema.sql` 用 `CREATE TABLE IF NOT EXISTS` + 文件末尾追加 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 兼容老库,不允许破坏性变更
- 前端无 lint/格式化配置,跟随现有代码风格(2 空格缩进、单文件组件)
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug
- 所有错误(程序异常、业务错误)都必须落日志:`GlobalExceptionHandler` 各分支 warn(带堆栈)/error,业务 catch 不允许静默吞掉;预期内的降级(驱动能力缺失、DM ALL_SEGMENTS 受限)用 debug/warn 说明降级后果;唯一例外是未激活期间的授权拦截(每请求触发,只记 debug 防刷屏)

## 安全考虑

- 数据源密码用 AES-GCM 加密存 H2(`password_enc` 列),密钥在 `application.yml` 的 `dq.security.secret`,默认值 `change-me-...`,改动密钥逻辑时注意向后兼容已存数据
- AI 配置的 API Key 同样 AES-GCM 加密存 H2(`ai_config.api_key_enc`),GET 接口只回传 hasKey 不回传明文;`application.yml` 的 `ai.*` 是默认配置(AiProperties 绑定),页面未设置的字段逐字段回落到默认值(默认 key 为明文,仅适合内网);默认配置不对外暴露 —— 不回显、报错不带细节,默认不可用时只提示用户自行配置;「生成表说明」会把表结构元数据(表名/字段/注释)发给配置的第三方 LLM 接口,不发送业务数据,属用户显式触发
- 授权码为离线 Ed25519 验签:私钥 `license-private.key` 只存分发方本地(.gitignore 已排除),公钥在 `application.yml` 的 `dq.license.public-key`;激活后授权码 AES-GCM 加密存 H2(`license_info.code_enc`),状态接口只回传客户标识/有效期不回传授权码;到期日当天仍有效;纯离线方案防不了逆向破解,仅作分发门槛
- 应用无认证,任何能访问端口的人都能操作所有数据源,不要暴露到公网
- 自定义空值规则(如 `status IN (0,-1)`)会原样拼进统计 SQL,属设计如此的"信任内网用户"行为;不要在方言层之外引入新的 SQL 拼接,标识符必须经 `DbDialect.quote()` 处理
- H2 数据文件含连接信息与扫描结果,`data/` 目录不应提交或外发

## 发布流程

- 推 `v*` tag(如 `git tag v1.2 && git push origin v1.2`)触发 `.github/workflows/release.yml`;也支持 workflow_dispatch 手动触发(产物以 artifact 下载,保留 30 天,名称带版本号)
- 并行构建:Windows 免安装 zip(x64 + ARM64,jpackage `--type app-image`,**已不用 exe/WiX**)、macOS dmg(Apple Silicon + Intel,Intel 用 macos-15-intel runner)、Linux deb + rpm,tag 触发时全部挂到 GitHub Release。**当前 CI 只启用 Windows x64,ARM64/macOS/Linux 任务在 release.yml 中整体注释停用,需要时取消注释恢复**
- jpackage 不支持交叉编译,各平台包在对应系统的 runner 上原生构建
- 本地打包用 `scripts/package-{mac,linux}.sh` / `scripts\package-win.bat`,可加 `--skip-build` 只重打包;Linux 脚本默认打 deb,加 `--type rpm` 打 rpm(需 rpmbuild)
- 安装包要求主版本号 ≥ 1,脚本把项目版本 `0.1.3` 映射为安装包版本 `1.3`
- 安装版数据目录固定为 `~/.dq-tool/data`(jar 方式为 `./data`),由打包脚本注入 `--java-options "-Ddq.data-dir=..."`(`spring.datasource.url` 与日志路径都引用该配置),修改打包脚本时保持这一区分
- 运行日志输出到数据目录 `logs/` 子目录,按天滚动(`dq-tool.yyyy-MM-dd.log`,保留 30 天,配置见 `application.yml` 的 `logging.*`);Windows 包无控制台窗口,日志文件是唯一排障入口
