# 去 Spring 化改造方案(server 模块 → Javalin)

> 状态:已执行完毕(2026-08-06),执行记录见文末
> 日期:2026-08-06
> 范围:`server` 模块(Spring Boot 4.1 → 轻量方案);`desktop` 模块已是无 Spring 的 Compose Desktop,不在本方案范围。

## 1. 背景与动机

`server` 模块是一个本地单机桌面工具的服务端(浏览器访问 `localhost:10000` 使用),却扛着为大型服务端设计的 Spring Boot 全家桶:

- **自动配置**:启动时类路径扫描 + 条件装配,桌面应用用不到却每次付出启动时间
- **AOP / CGLIB 代理**:本项目 0 个 `@Autowired`、全部构造注入,Spring 容器只提供了"装配"一件事
- **配置体系**(application.yml + profile + 绑定):桌面单机一个配置文件足够
- **Web 容器抽象**(Servlet API + DispatcherServlet):6 个 Controller、22 个端点
- **体积与启动速度**:starter 传递依赖链 + 自动配置扫描

`desktop` 模块已经验证了"无 Spring"路线可行(依赖仅 HikariCP + H2 + Jackson + POI + Logback),本方案把 `server` 模块对齐到同一哲学。

## 2. 技术选型结论(决策记录,2026-08-06)

### 2.1 Web 层:Javalin

**结论**:Web 层从 Spring MVC 迁移到 **Javalin**(版本以 Maven Central 最新稳定版为准,当前 7.x 线,7.2.2)。

**决策理由**:

1. 端点规模 22 个,路由/反序列化/异常映射样板在裸方案下手写成本已超过框架成本
2. Javalin 是轻量框架的代表:单依赖、无自动配置、无注解扫描、启动毫秒级,与 Spring Boot 两个极端
3. 不需要 WebSocket,Javalin 的 HTTP 能力完全覆盖;底层 Jetty 与现状一致(Spring Boot 现用 starter-jetty),同步 handler 风格与现有代码零摩擦
4. **JSON 序列化可直接对齐现状**:项目业务代码已用 Jackson 3(`tools.jackson`,ScanService/ChunkRunner),而 Javalin 默认 JsonMapper 是 Jackson 2(`com.fasterxml.jackson`)。Javalin 7.2.0 起官方提供 `JavalinJackson3`(Jackson 3 的 JsonMapper 实现),选型 7.x 后 Web 层与业务层共用一个 ObjectMapper,不引入 Jackson 2。注意 Jackson 3 版本现在靠 Boot 4 BOM 托管,迁移后须在版本目录显式钉 `tools.jackson.core:jackson-databind`;并验证裸 Jackson 3 与 Boot 托管配置的默认值差异(未知字段、null 处理),由 Phase 2 的 curl 对照兜底

**对比过的替代方案(未选,记录以防重议)**:

| 方案 | 体积 | 拒绝理由 |
|---|---|---|
| 裸 JDK `HttpServer`(`jdk.httpserver`) | 0(JDK 自带) | 路由/JSON/状态码/异常/连接生命周期全手写,22 个端点意味着 22 份样板;仅适合 2~3 个端点。若走此路,须配虚拟线程 `Executor`,并在 jlink 打包时显式加 `jdk.httpserver` 模块 |
| Netty | ~5MB(HTTP codec 组合) | 不是 HTTP 服务器而是 NIO 网络框架,HTTP 解析/拼装全要自己写;体积反而最大;事件驱动风格与现有命令式代码不匹配;性能优势(高并发连接数)对单机工具无意义 |
| Jetty 直用(最小 4 jar) | ~2MB | 与 Javalin 同底层但无路由 DSL,样板介于裸 HttpServer 与 Javalin 之间;选 Javalin 等价于选 Jetty + 薄封装 |
| 保留 Spring Boot | — | 本轮要解决的问题本身 |

### 2.2 依赖注入:手写构造,不引入框架

**结论**:不引入 Guice / Koin / Dagger,`main` 里手动构造对象图。

**理由**:服务层 7 个、repository 层 6 个,全部具体类直接依赖、无接口多实现、无替换需求。手写构造约十行;任何 DI 框架(即使 Guice 这样 ~1.5MB 的轻量者)在当前依赖图下的结构收益为零,还要多维护一套绑定。**触发条件**(届时再议,预计不会发生):服务数 >15、出现接口多实现、或出现循环依赖。

### 2.3 数据访问:第一阶段保留 spring-jdbc,完全去 Spring 列为后续项

**现状**:6 个 repository 全部使用 `JdbcTemplate`。

**结论**:第一阶段保留 `spring-jdbc`(单独引用 `spring-jdbc` + `spring-core` + `spring-beans` + `spring-tx`,几个 jar、无自动配置、无扫描),repository 代码零改动。JdbcTemplate 的价值(资源管理、异常转换、参数绑定)与 Spring 容器无关,可以脱衣使用。

**后续项(不在本次范围)**:完全去掉 spring-jdbc,改为手写 JDBC——`desktop` 模块的 `Jdbc.kt` 已给出同构先例,若两个模块要共享数据访问风格再评估。

### 2.4 保留的独立库

| 库 | 结论 | 说明 |
|---|---|---|
| Jackson 3(`tools.jackson`) | 保留,版本进版本目录 | JSON,本就不依赖 Spring;现靠 Boot BOM 托管,迁移后显式钉 `tools.jackson.core:jackson-databind`;Web 层经 `JavalinJackson3` 共用同一 ObjectMapper |
| Logback | 保留单引 | 滚动策略从 application.yml 迁到 logback.xml |
| HikariCP | 替换 spring-boot-starter-jdbc 自带池 | 现为 spring.datasource 托管,改手动创建 |
| H2 / 七个 JDBC 驱动 / POI | 不变 | 均为独立依赖 |
| hibernate-validator | 保留(替代 starter-validation) | Jakarta 校验独立于 Spring,写一个 validate 工具即可;注意消息插值需要 EL 实现(如 `org.glassfish.expressly`),缺失时会回落 ParameterMessageInterpolator——须验证回落行为与现 400 消息格式(`字段 + defaultMessage`)一致 |

**版本托管缺口**:去 Boot BOM 后,logback、slf4j、snakeyaml、Jackson 3、spring-jdbc/core/beans/tx、hibernate-validator、HikariCP 的版本全部不再有人托管,须进 `gradle/libs.versions.toml` 显式钉版本;其中 slf4j / logback 目前由 starter 传递引入、从未显式声明,迁移时首次显式声明。

## 3. 现状盘点(改造范围)

### 3.1 构建与入口

- `server/build.gradle.kts`:Spring Boot 插件 + BOM + starter-web/jetty/jdbc/validation;`bootJar` 产物名 `dq-tool`;`processResources` 拷贝 `web/dist → static`;`bootRun` 固定工作目录
- 根 `build.gradle.kts`:`org.springframework.boot` 插件版本声明
- `DqApplication.main`:端口解析(`--server.port=` > `SERVER_PORT` > 10000)→ 端口占用避让(向后探测 100 个;`server.port=0` 时跳过避让,交给容器随机分配)→ 桌面早反馈(TrayManager/DesktopSplash,headless=false 时)
- **schema.sql 自动执行是 Boot 功能**:`spring.sql.init.mode=always` 负责启动时跑 H2 建表/升级脚本,去 Boot 后无人接管,必须手动替代(见 Phase 1)

### 3.2 Web 层(6 个 Controller,22 个端点,全部构造注入,0 个 @Autowired)

| Controller | 端点 |
|---|---|
| LicenseController | GET/POST `/api/license/status`、`/api/license/activate` |
| ScanController | 扫描作业 CRUD + Excel 导出 |
| MetadataController | 元数据/浏览 |
| DataSourceController | 数据源管理 |
| AiConfigController | AI 配置 |
| HeartbeatController | `/api/heartbeat`(页面心跳,GET 返回 204) |

横切件:

- `LicenseInterceptor`:`/api/**` 除 `/api/license/**`、`/api/heartbeat` 外,未激活抛 `LicenseRequiredException`(→401)
- `SpaWebConfig`:静态资源 `classpath:/static/` + SPA history 回退到 index.html(`/api/**` 不回退)
- `GlobalExceptionHandler`:IllegalArgumentException→400、MethodArgumentNotValid→400、LicenseRequired→401、IllegalState→409、SQLException→502、Exception→500,统一 `{"message": ...}`(前端依赖此结构)
- **导出端点非普通 JSON 迁移**:`ScanController` 的 Excel 导出直接用 `jakarta.servlet.http.HttpServletResponse` 流式写(POI 流式导出);Javalin 7 底层仍是 jakarta servlet(Jetty 12),迁移时用 `ctx.res()` / `ctx.outputStream()` 等价实现

### 3.3 配置体系

- `application.yml`:server.port、spring.datasource(H2 文件库 `jdbc:h2:file:${dq.data-dir}/dqconfig;AUTO_SERVER=TRUE`)、sql.init(schema.sql)、logging(按天滚动、30 天、100MB/2GB)、`dq.*`(scan 阈值、security.secret、desktop 看门狗、license 公钥)、`ai.*`
- `DqProperties`(@ConfigurationProperties prefix="dq",Scan/Security/License/Desktop 四组)、`AiProperties`
- `dq.data-dir` 由安装版打包脚本以 `-Ddq.data-dir=${user.home}/.dq-tool/data` 注入(注意是**横杠**命名,见 scripts/package-*.sh;Spring relaxed binding 下 `-Ddq.data.dir` 也能生效,但 logback 没有 relaxed binding,logback.xml 中的变量名必须与脚本注入的 `dq.data-dir` 完全一致)

### 3.4 生命周期与桌面集成(Spring 事件/调度挂载点)

- `BrowserOpener`:监听 `ApplicationReadyEvent` → 关启动画面 + 打开浏览器(应用模式);从 environment 读 `server.port`(main 已把避让后的最终端口 setProperty 进去)
- `DesktopSession`:`@Scheduled(fixedDelay=5000)` 心跳看门狗;退出用 `SpringApplication.exit(ctx, ...)` 且必须换线程(否则等 taskScheduler 自己僵持)
- `TrayManager`:监听 `ApplicationReadyEvent`;退出逻辑同 SpringApplication.exit
- `DesktopSplash`:纯静态,无 Spring 依赖,不动

### 3.5 测试

- `ScanFlowTest`:唯一使用 `@SpringBootTest(properties=...)` 的测试(Testcontainers 数据库 + Spring 上下文装配;实际只装配 4 个 service + H2 mem 库,手动装配成本低)
- 其余 5 个测试(LicenseCodec/ScanJobDelete/DialectSqlGen/ChunkPlanning/AiService)为纯单元测试,不动;`ScanJobDeleteTest` 已用 spring-jdbc 的 `ScriptUtils.executeSqlScript` 执行 schema.sql,是 Phase 1 手动初始化的先例

## 4. 改造步骤(分阶段,每阶段有验收)

### Phase 0:建立基线

- 记录改造前:`java -jar` 启动耗时、`dq-tool-*.jar` 体积、全量测试结果
- 验收:基线数据入档,测试全绿

### Phase 1:构建与配置(去 Boot 插件、配置自加载)

1. `server/build.gradle.kts`:移除 Spring Boot 插件与 BOM;依赖换成:Javalin 7.x、Jackson 3(显式钉)、snakeyaml(读 yaml)、HikariCP、spring-jdbc(+core/beans/tx)、hibernate-validator(+EL 实现或验证回落)、slf4j + logback(首次显式声明)、现有独立库;版本全部进 `gradle/libs.versions.toml`。**fat jar 用 Shadow 插件(`com.gradleup.shadow`)产出**——普通 `jar` 任务不含依赖,`java -jar` 与 jpackage `--main-jar` 都无法运行;产物路径保持 `server/build/libs/dq-tool-<version>.jar` 不变,但 `Makefile`、`scripts/package-*.sh` 中调用的 `:server:bootJar` 任务名要同步改为 shadow 产物任务;`processResources` 拷贝 `web/dist → static` 保留;`bootRun` → 由 `application` 插件或 `javaexec` 承接
2. 根 `build.gradle.kts`:删除 Spring Boot 插件声明
3. 新增 `ConfigLoader`:snakeyaml 读 `application.yml` → `DqProperties`/`AiProperties`(去掉 @ConfigurationProperties 注解,保留嵌套结构);`dq.data-dir` 系统属性覆盖逻辑保留;`server.port` 优先级(`--server.port=` > `SERVER_PORT` > yml)保留。**main 必须在首次打日志之前完成 data-dir 解析并 `System.setProperty`**——logback 首次使用即初始化,晚于该时点的属性设置对日志路径无效
4. 日志滚动策略从 application.yml 的 `logging:` 段迁到 `logback.xml`:路径用 `${dq.data-dir:-./data}`(变量名与打包脚本注入的 `-Ddq.data-dir` 完全一致,`:-` 兜底开发模式)
5. 新增启动初始化:Hikari 建池后用 `ScriptUtils.executeSqlScript`(spring-jdbc 自带)执行 `classpath:schema.sql`,替代 `spring.sql.init.mode=always`;`ScanJobDeleteTest` 已有同样写法可作参照
- 验收:`gradlew build` 通过;jar 启动后 H2 建表、日志落盘路径/滚动行为与改造前一致;跑一遍 `scripts/package-*.sh` 验证 shadow fat jar 与 jpackage 兼容

### Phase 2:Web 层迁移(Controller → Javalin handler)

1. 新增 `WebServer` 装配类:main 中构造对象图(7 service + 6 repository + Hikari + JdbcTemplate)→ `Javalin.create().start(port)`
2. 配置 JSON:Javalin 使用 `JavalinJackson3` 作为 JsonMapper,与业务层(ScanService/ChunkRunner)共用同一个 Jackson 3 ObjectMapper;不引入 Jackson 2 传递依赖
3. 6 个 Controller 逐个迁移为 handler 方法:`ctx.json(...)` 出参、`ctx.bodyAsClass(...)` 入参;`@Valid` 校验 → 手写 `validate()` 工具(复用 hibernate-validator),异常语义与现 400 响应一致。**Excel 导出端点单独处理**:现写 `HttpServletResponse` 流式输出,迁移用 `ctx.res()` / `ctx.outputStream()`,响应头(Content-Type / Content-Disposition 文件名)与现状一致
4. `GlobalExceptionHandler` → 6 个 `app.exception(...)` 映射,响应体保持 `{"message": ...}`
5. `LicenseInterceptor` → `app.before(...)` 授权校验,排除 `/api/license/**` 与 `/api/heartbeat`(实现时验证 Javalin 通配符语义,必要时在 before 内做 path 判断,语义与现状一致)
6. `SpaWebConfig` → 静态资源 `classpath:/static` + SPA 回退 handler:存在则返回文件,不存在且非 `/api/**` 则回退 `index.html`,`/api/**` 未匹配返回 JSON 404
- 验收:每个端点用 curl 对照改造前行为(含 401/404/400/409/500 各状态、SPA 回退、静态资源、导出文件的响应头与内容)

### Phase 3:生命周期与桌面集成

1. `DesktopSession.watchdog` `@Scheduled` → `ScheduledExecutorService.scheduleWithFixedDelay(5s)`;退出逻辑改为**停 Javalin → 关 Hikari 池 → `System.exit(0)`**(保留"换线程退出"的注释与做法)
2. `BrowserOpener.openBrowser`(ApplicationReadyEvent)→ main 中 `Javalin.start()` 之后显式调用;正常路径由 main 把避让后的最终端口直接传给 BrowserOpener/TrayManager,不再绕道 environment;**`server.port=0` 时须从 Javalin 启动后的实际端口回读**(配置对象里的 0 不是真实端口)
3. `TrayManager` 事件挂载 → main 显式调用;`SpringApplication.exit` → 与 DesktopSession 相同的退出封装
4. `DqApplication.main` 的端口解析/避让/早反馈逻辑原样保留,挪进新 main
- 验收:桌面安装版(jpackage 脚本)托盘、启动画面、应用模式窗口、关窗看门狗退出,全流程手工走查

### Phase 4:测试改造与全量回归

1. `ScanFlowTest` 去 `@SpringBootTest`:手动装配(H2 mem / Testcontainers 数据源 → new JdbcTemplate → repository → service),保持断言不变
2. `spring-boot-starter-test` → junit-jupiter + assertj(与 desktop 模块一致)
3. 全量回归:Testcontainers 覆盖的 MySQL/PostgreSQL/SQL Server 端到端 + 其余单测
4. 收尾同步文档:根 `AGENTS.md`(技术栈、构建命令、配置体系、发布流程多处描述随本次改造变化)、README、`Makefile` 注释
- 验收:全部测试通过,与 Phase 0 基线无行为差异

## 5. 风险与应对

| 风险 | 应对 |
|---|---|
| Javalin 路由/通配符语义细节(before 排除、`/api/**` 匹配)与 Spring 拦截器不完全等价 | Phase 2 逐个端点 curl 对照;语义差异以"行为一致"为准,不追求实现一致 |
| 静态资源/SPA 回退行为差异 | Phase 2 单独走查前端路由(直接访问 /datasources 等深层路由) |
| 打包脚本/Makefile 引用 `bootJar` 任务与产物;shadow fat jar 与 jpackage 的兼容性 | 产物路径保持 `server/build/libs/dq-tool-<version>.jar`;任务名同步改;Phase 1 验收时跑一遍 scripts/package-*.sh |
| `@Valid` 校验消息格式变化(含 hibernate-validator 独立使用时 EL 缺失的回落行为) | validate 工具保持现 message 拼接格式,前端依赖 message 字段;Phase 1 验证 EL 实现/回落行为 |
| 日志滚动策略迁移遗漏;logback 初始化早于 ConfigLoader 导致路径属性失效 | main 首行解析 data-dir 并 setProperty;Phase 1 验收项含滚动行为对比(跨天/超限) |
| 裸 Jackson 3 与 Boot 托管配置的序列化默认值差异(未知字段、null 处理) | Phase 2 curl 对照各端点响应体;`JavalinJackson3` 与业务层共用 ObjectMapper,差异面收敛到一处 |
| 看门狗/托盘退出路径回归 | Phase 3 全流程手工走查,含"托盘不可用→看门狗退出"兜底路径 |

## 6. 验收指标

- 全量测试(Testcontainers 端到端 + 单测)全绿,与 Phase 0 基线一致
- 启动耗时与 jar 体积:以 Phase 0 基线为准,记录提升幅度(预期启动时间降一个量级、体积显著下降)
- 桌面集成(托盘/启动画面/应用模式/看门狗)行为不变
- 全部 22 个端点状态码与响应体与改造前一致

## 7. 遗留问题(不在本次范围)

- 数据访问层是否最终完全去 Spring(spring-jdbc → 手写 JDBC,与 desktop 模块 `Jdbc.kt` 对齐)
- desktop/server 两个模块共享业务代码的可行性评估(当前为两份独立实现)

## 8. 执行记录(2026-08-06)

**基线 vs 改造后**:

| 指标 | 基线(Spring Boot 4.1) | 改造后(Javalin 7.2.2) |
|---|---|---|
| fat jar 体积 | 62M(65,141,288 B) | 58M(59,791,502 B;驱动/POI/Jetty 占大头,Spring 只占零头) |
| 启动耗时(到 `/api/license/status` 可响应) | 2.52 s | 1.54 s(-39%) |
| 全量测试 | 14 用例全绿(含 Testcontainers 三库) | 14 用例全绿,一致 |

**已验证**:API 级端到端(真实 MySQL:数据源→扫描→字段统计→导出,响应头逐字节一致)、授权激活/401/404/400/409 各状态码与 `{"message"}` 格式、SPA 深层路由回退、静态资源、心跳 204、logback 落盘路径与格式、桌面安装版行为(显式 headless=false 时装托盘+开 --app 窗口,普通 java -jar 不触发;托盘生效时关窗进程常驻)。

**执行中额外发现并处理的问题**(方案之外):

1. **Javalin 7 路由 API 大改**:路由/异常映射/错误页只能在 `Javalin.create` 配置回调里注册(`cfg.routes`),`app.get(...)` 系列已移除;心跳路由与 DesktopSession/AppShutdown 的构造循环用 `AtomicReference` 后填打破。`Validator.allowNullable()` 移除,改用 `getOrNull()`。
2. **headless 语义**:Spring Boot 原来会把 `java.awt.headless` 置 true,去 Spring 后桌面机器上普通 `java -jar` 会误判为桌面模式弹窗/装托盘;`DqApplication.main` 首行显式默认 headless=true,行为与基线对齐。
3. **Jackson 3 默认值反转**:Jackson 3 默认 `FAIL_ON_NULL_FOR_PRIMITIVES=true`(Boot 托管为 false),缺省 `forceFull` 的扫描请求会 400;共享 mapper 已显式对齐 Boot 全部相关默认值。另补 JacksonException→400 映射(原 Spring 对畸形 JSON 默认 400)。
4. **`@Transactional` 处理**:`ScanService.delete` 改注入 `TransactionTemplate` 编程式事务(spring-tx 脱容器,JdbcTemplate 经 DataSourceUtils 自动参与)。
5. **打包脚本 `${user.home}`**:原为 Spring 占位符解析,ConfigLoader 现手动展开;jpackage `--main-class` 从 Boot JarLauncher 改为 `com.example.dq.DqApplication`。

**未验证项(需真实环境手工确认)**:托盘菜单「退出」点击路径(GUI 操作,代码与基线一致仅换退出封装);看门狗触发退出(仅托盘不可用环境生效,逻辑与基线一致);jpackage 全平台打包(任务名/主类已改,未实际跑 scripts/package-*.sh);达梦/金仓/OceanBase/Oracle 方言(本来就无自动化覆盖);LLM 实际调用(HTTP 客户端从 RestClient 换成 JDK HttpClient,prompt 组装单测通过)。
