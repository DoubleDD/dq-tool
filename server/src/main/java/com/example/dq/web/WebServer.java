package com.example.dq.web;

import com.example.dq.config.AppShutdown;
import com.example.dq.config.BrowserOpener;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DesktopSession;
import com.example.dq.config.DqProperties;
import com.example.dq.config.KernelConfigAdapter;
import com.example.dq.config.StartupLog;
import com.example.dq.config.StartupStage;
import com.example.dq.config.TrayManager;
import com.example.dq.controller.AiConfigController;
import com.example.dq.controller.DataSourceController;
import com.example.dq.controller.LicenseController;
import com.example.dq.controller.MetadataController;
import com.example.dq.controller.ReportExportController;
import com.example.dq.controller.LogController;
import com.example.dq.controller.ScanController;
import com.example.dq.controller.TagController;
import com.example.dq.env.ServiceEnv;
import com.example.dq.license.LicenseFeature;
import com.example.dq.model.LicenseAdminRequiredException;
import com.example.dq.model.LicenseFeatureRequiredException;
import com.example.dq.model.LicenseRequiredException;
import com.example.dq.service.LicenseService;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson3;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.module.kotlin.KotlinModule;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 层装配与路由(去 Spring 后替代容器装配 + DispatcherServlet):
 * 构造对象图(repository → service → handler,全部构造注入),注册 31 个端点、
 * 授权前置校验(替代 LicenseInterceptor)、统一异常映射(响应体 {"message": ...},
 * 与改造前 GlobalExceptionHandler 一致)、静态资源与 SPA 回退(替代 SpaWebConfig)、
 * 就绪闸门与 /api/health 就绪探针(共享内核未就绪前业务接口统一 503,前端轮询到 200 再加载数据)。
 * Javalin 7 的路由只能在 Javalin.create 的配置回调里注册(cfg.routes),创建后不可追加。
 *
 * 启动时序(启动优化:先开窗、后建内核):
 * 构造(路由引用骨架 + 静态资源 + 探针,毫秒级,不建 ServiceEnv)→ start 绑定 → openBrowser 开窗
 * (页面外壳秒出,前端轮询 /api/health 看到实时启动阶段)→ finishInit 构建共享内核
 * (H2 池 + 服务对象图 + 建表/迁移/恢复)并置就绪。业务路由在 create 回调里照常注册,
 * 但 handler 引用 AtomicReference 控制器,内核构建完成后注入——就绪闸门保证注入前
 * 业务接口统一 503,不会触到空引用。
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final Javalin app;
    /** 共享内核(H2 池 + 服务对象图),openBrowser 开窗后才构建(启动优化) */
    private volatile ServiceEnv env;
    private final ConfigLoader.AppConfig config;
    private final DesktopSession session;
    private final BrowserOpener browserOpener;
    private final TrayManager trayManager;
    /** 共享内核就绪标志:finishInit 完成建表/迁移/恢复后置 true,之前业务接口被闸门拦成 503 */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    // 控制器/服务引用:路由在 create 回调里注册,内核(finishInit)构建完成后注入。
    // 就绪闸门在授权校验之前短路(未就绪一律 503),注入前不会触到空引用
    private final AtomicReference<LicenseService> licenseServiceRef = new AtomicReference<>();
    private final AtomicReference<DataSourceController> dataSourceCtrl = new AtomicReference<>();
    private final AtomicReference<ScanController> scanCtrl = new AtomicReference<>();
    private final AtomicReference<MetadataController> metaCtrl = new AtomicReference<>();
    private final AtomicReference<ReportExportController> reportCtrl = new AtomicReference<>();
    private final AtomicReference<TagController> tagCtrl = new AtomicReference<>();
    private final AtomicReference<AiConfigController> aiCtrl = new AtomicReference<>();
    private final AtomicReference<LicenseController> licenseCtrl = new AtomicReference<>();

    public WebServer(ConfigLoader.AppConfig config) throws Exception {
        this.config = config;
        DqProperties props = config.dq();

        // 全应用共享的 Jackson 3 mapper:序列化默认值与原 Spring Boot 托管配置对齐
        // (ISO 日期/不报空 bean/忽略未知字段;Jackson 3 默认 FAIL_ON_NULL_FOR_PRIMITIVES=true 与 Boot 相反,须显式关掉);
        // KotlinModule 用于内核的 Kotlin data class 模型
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new KotlinModule.Builder().build())
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        // ---- 实时日志流 Appender:以编程方式挂载到 root logger ----
        // 不在 logback.xml 声明(避免反射实例化导致无法获取引用);此处直接创建实例,
        // 供 LogController 的 SSE 端点订阅。挂载到 root logger 后,所有日志事件都会被捕获。
        LogStreamAppender logStreamAppender = new LogStreamAppender();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        logStreamAppender.setContext(loggerContext);
        logStreamAppender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .addAppender(logStreamAppender);

        // 心跳路由要在 create 回调里注册,而 DesktopSession 依赖 AppShutdown(需要 Javalin 实例),
        // 用引用后填打破循环(服务 start 之前回调不可能触发,不会读到 null)
        AtomicReference<DesktopSession> sessionRef = new AtomicReference<>();

        this.app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson3(objectMapper, false));
            // 指纹资源(文件名带内容 hash,内容变则文件名变):长缓存 immutable;须先注册,优先于根目录条目命中
            cfg.staticFiles.add(files -> {
                files.hostedPath = "/assets";
                files.directory = "/static/assets";
                files.location = Location.CLASSPATH;
                files.headers = Map.of("Cache-Control", "public, max-age=31536000, immutable");
            });
            // 入口 index.html 等:每次重校验。不缓存是硬要求——否则升级后浏览器仍用旧 index.html,
            // 引用已不存在的旧 hash 资源,模块脚本拿到 SPA 回退的 text/html 报 MIME 错误白屏
            cfg.staticFiles.add(files -> {
                files.hostedPath = "/";
                files.directory = "/static";
                files.location = Location.CLASSPATH;
                files.headers = Map.of("Cache-Control", "no-cache");
            });
            cfg.startup.showJavalinBanner = false;
            registerRoutes(cfg.routes, licenseServiceRef,
                    dataSourceCtrl, scanCtrl, metaCtrl, reportCtrl, tagCtrl, aiCtrl, licenseCtrl,
                    new LogController(logStreamAppender), sessionRef);
        });

        // ---- 桌面生命周期(原 Spring 事件/调度挂载点,改显式装配;退出动作统一走 AppShutdown) ----
        // 连接池由共享内核懒构建,退出时按需取(内核未构建完就退出时跳过关池)
        AppShutdown shutdown = new AppShutdown(app, () -> env == null ? null : env.getDataSource());
        this.session = new DesktopSession(props, shutdown);
        this.browserOpener = new BrowserOpener(session);
        this.trayManager = new TrayManager(browserOpener, session, shutdown);
        sessionRef.set(session);
        this.session.start();
        StartupLog.log("  WebServer 构造完成(路由引用骨架 + 桌面生命周期组件)");
    }

    private void registerRoutes(RoutesConfig routes, AtomicReference<LicenseService> licenseServiceRef,
                                AtomicReference<DataSourceController> dataSourceCtrl,
                                AtomicReference<ScanController> scanCtrl,
                                AtomicReference<MetadataController> metaCtrl,
                                AtomicReference<ReportExportController> reportCtrl,
                                AtomicReference<TagController> tagCtrl,
                                AtomicReference<AiConfigController> aiCtrl,
                                AtomicReference<LicenseController> licenseCtrl,
                                LogController logCtrl, AtomicReference<DesktopSession> sessionRef) {
        // 授权前置校验(替代 LicenseInterceptor):/api/** 除授权接口自身与页面心跳外,要求已激活且未过期;
        // beforeMatched 只在路由命中时触发,与原 Spring 拦截器一致(未匹配的 /api/** 仍走 404 而非 401)
        routes.beforeMatched("/api/*", ctx -> {
            String path = ctx.path();
            // 就绪闸门:共享内核(建表/迁移/中断恢复)未就绪前,除就绪探针与页面心跳外所有业务接口统一 503。
            // 必须在授权校验之前短路——licenseService 虽已构建,但依赖的库表可能尚未迁移完成
            if (path.equals("/api/health") || path.equals("/api/heartbeat")) {
                return;
            }
            if (!ready.get()) {
                throw new ServiceNotReadyException();
            }
            LicenseService licenseService = licenseServiceRef.get();
            // 授权码管理(license_admin 受控功能):/api/license 前缀下不被激活拦截,但需授权码显式包含该功能
            if (path.startsWith("/api/license/admin")) {
                licenseService.checkFeature(LicenseFeature.LICENSE_ADMIN, false);
                return;
            }
            if (path.startsWith("/api/license") || path.equals("/api/heartbeat")) {
                return;
            }
            licenseService.checkActive();
            // 受控功能校验(业务功能恒有,无需校验):运行日志页需授权码显式包含 logs 功能
            if (path.startsWith("/api/logs")) {
                licenseService.checkFeature(LicenseFeature.LOGS);
            }
        });
        // ---- 统一异常映射(替代 GlobalExceptionHandler,响应体保持 {"message": ...}) ----
        routes.exception(IllegalArgumentException.class, (e, ctx) -> {
            log.warn("请求参数错误: {}", e.getMessage(), e);
            ctx.status(400).json(Map.of("message", String.valueOf(e.getMessage())));
        });
        routes.exception(LicenseRequiredException.class, (e, ctx) -> {
            // 未激活/过期期间每个 API 请求都会触发,属授权状态而非程序错误,只记 debug 避免刷屏
            log.debug("授权校验拦截: {}", e.getMessage());
            ctx.status(401).json(Map.of("message", String.valueOf(e.getMessage())));
        });
        routes.exception(LicenseAdminRequiredException.class, (e, ctx) -> {
            log.warn("非管理员访问授权码管理: {}", e.getMessage());
            ctx.status(403).json(Map.of("message", String.valueOf(e.getMessage())));
        });
        routes.exception(LicenseFeatureRequiredException.class, (e, ctx) -> {
            log.warn("授权码未包含受控功能: {}", e.getMessage());
            ctx.status(403).json(Map.of("message", String.valueOf(e.getMessage())));
        });
        routes.exception(ServiceNotReadyException.class, (e, ctx) -> {
            // 启动早期绑定后、共享内核就绪前的统一响应:前端 /api/health 轮询到 200 前不挂载应用,
            // 页面停留在 index.html 的「正在连接服务」占位,不弹业务错误
            ctx.status(503).header("Retry-After", "1").json(Map.of("message", "服务启动中,请稍候…"));
        });
        routes.exception(IllegalStateException.class, (e, ctx) -> {
            log.warn("业务状态冲突: {}", e.getMessage(), e);
            ctx.status(409).json(Map.of("message", String.valueOf(e.getMessage())));
        });
        routes.exception(SQLException.class, (e, ctx) -> {
            log.warn("数据库访问失败: {}", e.getMessage(), e);
            ctx.status(502).json(Map.of("message", String.valueOf(e.getMessage())));
        });
        // 请求体 JSON 解析/映射失败(原 Spring 默认 400 语义)
        routes.exception(JacksonException.class, (e, ctx) -> {
            log.warn("请求体解析失败: {}", e.getMessage());
            ctx.status(400).json(Map.of("message", "请求体解析失败: " + e.getMessage()));
        });
        routes.exception(Exception.class, (e, ctx) -> {
            log.error("未处理异常", e);
            ctx.status(500).json(Map.of("message", String.valueOf(e.getMessage())));
        });

        // ---- 数据源 ----
        routes.get("/api/datasources", ctx -> dataSourceCtrl.get().list(ctx));
        routes.post("/api/datasources", ctx -> dataSourceCtrl.get().create(ctx));
        routes.put("/api/datasources/{id}", ctx -> dataSourceCtrl.get().update(ctx));
        routes.put("/api/datasources/{id}/schema-filter", ctx -> dataSourceCtrl.get().updateSchemaFilter(ctx));
        routes.delete("/api/datasources/{id}", ctx -> dataSourceCtrl.get().delete(ctx));
        routes.post("/api/datasources/test", ctx -> dataSourceCtrl.get().test(ctx));
        routes.post("/api/datasources/preview-databases", ctx -> dataSourceCtrl.get().previewDatabases(ctx));
        routes.get("/api/datasources/export", ctx -> dataSourceCtrl.get().export(ctx));
        routes.post("/api/datasources/import", ctx -> dataSourceCtrl.get().importDs(ctx));

        // ---- 扫描作业 ----
        routes.get("/api/scans/defaults", ctx -> scanCtrl.get().defaults(ctx));
        routes.post("/api/scans", ctx -> scanCtrl.get().create(ctx));
        routes.get("/api/scans", ctx -> scanCtrl.get().list(ctx));
        routes.get("/api/scans/{jobId}", ctx -> scanCtrl.get().get(ctx));
        routes.post("/api/scans/{jobId}/cancel", ctx -> scanCtrl.get().cancel(ctx));
        routes.post("/api/scans/{jobId}/resume", ctx -> scanCtrl.get().resume(ctx));
        routes.delete("/api/scans/{jobId}", ctx -> scanCtrl.get().delete(ctx));
        routes.get("/api/scans/{jobId}/tables/{tableName}/columns", ctx -> scanCtrl.get().columns(ctx));
        routes.get("/api/scans/{jobId}/export", ctx -> scanCtrl.get().export(ctx));

        // ---- 元数据/浏览(/api/datasources/{dsId} 下) ----
        routes.get("/api/datasources/{dsId}/databases", ctx -> metaCtrl.get().listDatabases(ctx));
        routes.get("/api/datasources/{dsId}/schemas", ctx -> metaCtrl.get().listSchemas(ctx));
        routes.get("/api/datasources/{dsId}/schema-stats", ctx -> metaCtrl.get().listSchemaStats(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables", ctx -> metaCtrl.get().listTables(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/columns", ctx -> metaCtrl.get().tableColumns(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/indexes", ctx -> metaCtrl.get().tableIndexes(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/column-count", ctx -> metaCtrl.get().countColumns(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/latest-scan-jobs", ctx -> metaCtrl.get().latestScanJobs(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/running-scans", ctx -> metaCtrl.get().runningScans(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-docs", ctx -> metaCtrl.get().tableDocs(ctx));
        routes.post("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/doc", ctx -> metaCtrl.get().generateTableDoc(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/doc", ctx -> metaCtrl.get().updateTableDoc(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/description", ctx -> metaCtrl.get().updateSchemaDescription(ctx));

        // ---- Word 报告异步导出任务 ----
        routes.post("/api/datasources/{dsId}/report/exports", ctx -> reportCtrl.get().submit(ctx));
        routes.get("/api/report-exports", ctx -> reportCtrl.get().list(ctx));
        routes.get("/api/report-exports/{id}/download", ctx -> reportCtrl.get().download(ctx));
        routes.post("/api/report-exports/{id}/open", ctx -> reportCtrl.get().open(ctx));
        routes.post("/api/report-exports/{id}/reveal", ctx -> reportCtrl.get().reveal(ctx));

        // ---- 表标记与统计 ----
        routes.get("/api/tags", ctx -> tagCtrl.get().list(ctx));
        routes.post("/api/tags", ctx -> tagCtrl.get().create(ctx));
        routes.put("/api/tags/{id}", ctx -> tagCtrl.get().update(ctx));
        routes.delete("/api/tags/{id}", ctx -> tagCtrl.get().delete(ctx));
        routes.get("/api/tags/{id}/stats", ctx -> tagCtrl.get().stats(ctx));
        routes.get("/api/datasources/{dsId}/schema-tag-stats", ctx -> tagCtrl.get().schemaTagStats(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-tags", ctx -> tagCtrl.get().tableTags(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/tags", ctx -> tagCtrl.get().replaceTableTags(ctx));

        // ---- AI 配置 / 授权 / 心跳 ----
        routes.get("/api/ai-config", ctx -> aiCtrl.get().get(ctx));
        routes.put("/api/ai-config", ctx -> aiCtrl.get().save(ctx));
        routes.get("/api/license/status", ctx -> licenseCtrl.get().status(ctx));
        routes.post("/api/license/activate", ctx -> licenseCtrl.get().activate(ctx));
        // 授权码管理(仅配置了签发私钥的管理员实例;在 /api/license 前缀下,不被激活拦截)
        routes.get("/api/license/admin/codes", ctx -> licenseCtrl.get().adminList(ctx));
        routes.post("/api/license/admin/codes", ctx -> licenseCtrl.get().adminGenerate(ctx));
        routes.delete("/api/license/admin/codes/{id}", ctx -> licenseCtrl.get().adminDelete(ctx));
        routes.get("/api/heartbeat", ctx -> {
            DesktopSession desktopSession = sessionRef.get();
            if (desktopSession != null) {
                desktopSession.beat();
            }
            ctx.status(204);
        });

        // 就绪探针:前端挂载应用前轮询本端点,共享内核就绪后返回 200(未就绪 503 + Retry-After + 启动阶段)。
        // 与 /api/heartbeat(页面心跳,只表示服务在跑)区分:业务可用性由本端点表达;
        // stage 供占位页展示"正在启动服务"的实时进度(见 StartupStage)
        routes.get("/api/health", ctx -> {
            if (ready.get()) {
                ctx.json(Map.of("status", "ok"));
            } else {
                ctx.status(503).header("Retry-After", "1")
                        .json(Map.of("status", "starting", "stage", StartupStage.get()));
            }
        });

        // ---- 实时日志流(SSE) ----
        routes.sse("/api/logs/stream", logCtrl::stream);

        // SPA 回退(替代 SpaWebConfig):静态资源未命中且非 /api/** 的 GET 一律回退 index.html 交给前端路由;
        // 但路径末段带扩展名(如 /assets/xxx.js)说明是静态文件缺失,必须真实 404——
        // 回退成 text/html 会让浏览器把 404 的 js 当 HTML 解析,报 MIME 错误白屏且掩盖真实问题;
        // /api/** 未匹配保持 JSON 404
        routes.error(404, ctx -> {
            String path = ctx.path();
            boolean api = path.startsWith("/api/") || path.equals("/api");
            boolean looksLikeFile = path.substring(path.lastIndexOf('/') + 1).contains(".");
            if (!api && !looksLikeFile && "GET".equals(ctx.method().name())) {
                InputStream index = WebServer.class.getResourceAsStream("/static/index.html");
                if (index != null) {
                    ctx.status(200).contentType("text/html;charset=utf-8")
                            .header("Cache-Control", "no-cache").result(index);
                    return;
                }
            }
            ctx.json(Map.of("message", "路径不存在: " + path));
        });
    }

    public void start(int port) {
        app.start(port);
    }

    /** 停止 Web 服务并关闭内核连接池(不走 System.exit,与 AppShutdown 的进程退出路径区分;测试与嵌入式使用) */
    public void stop() {
        app.stop();
        if (env != null) {
            env.shutdown();
        }
    }

    /** 实际监听端口(server.port=0 时为容器随机分配的结果) */
    public int port() {
        return app.port();
    }

    /**
     * HTTP 绑定后立即打开应用窗口(桌面安装版):页面外壳秒出,后端就绪由前端轮询
     * /api/health 等待(占位页同步展示实时启动阶段)。
     * 必须 start() 之后调用;headless 服务器部署自动跳过(见 BrowserOpener)。
     * 启动画面不在本方法关闭(原实现在这里关,造成 Chrome 冷启动期间的死区)——改由
     * DqApplication 在 finishInit 就绪后关闭,此时 Chrome 已显示占位页,无缝衔接。
     */
    public void openBrowser() {
        browserOpener.openBrowser(app.port());
    }

    /**
     * 完成共享内核重活(启动优化:开窗后才构建):
     * 1) 构建 ServiceEnv(H2 连接池 + 全部业务服务对象图)并注入路由引用的控制器;
     * 2) 持久化重活(H2 建表/迁移 + 中断任务恢复 + 报告任务恢复);
     * 3) 置服务就绪,/api/health 转 200,前端随即加载数据。
     * 就绪前业务接口由闸门返回 503。启动失败时由 main 调 closeBrowserWindow。
     * 托盘回填由 main 在就绪后调 markTrayReady,不并入本方法——避免测试 JVM(非 headless)
     * 里误装系统托盘图标。幂等:重复调用只执行一次内核构建。
     */
    public void finishInit() {
        if (env == null) {
            StartupLog.log("  创建共享内核 ServiceEnv(H2 连接池 + 业务服务对象图)...");
            ServiceEnv env = new ServiceEnv(KernelConfigAdapter.toKernelConfig(config));
            injectKernel(env);
            StartupLog.log("  ServiceEnv 就绪,初始化共享内核(建表/迁移/中断恢复)...");
            env.initDatabase();
        }
        ready.set(true);
        StartupLog.log("  共享内核初始化完成,服务就绪");
    }

    /**
     * 并行启动变体:内核已在后台线程与 Web 装配/开窗并行构建(见 DqApplication),
     * 此处只等待结果并注入引用;内核构建异常原样抛出,由 main 走统一启动失败路径。
     */
    public void finishInit(java.util.concurrent.CompletableFuture<ServiceEnv> kernelFuture) {
        if (env == null) {
            ServiceEnv env;
            try {
                env = kernelFuture.join();
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("共享内核初始化失败", cause);
            }
            injectKernel(env);
        }
        ready.set(true);
        StartupLog.log("  共享内核初始化完成,服务就绪");
    }

    /** 注入内核服务对象图到路由引用骨架,并把内核挂到本实例(stop/AppShutdown 用) */
    private void injectKernel(ServiceEnv env) {
        this.env = env;
        licenseServiceRef.set(env.getLicenseService());
        dataSourceCtrl.set(new DataSourceController(env.getDataSourceService(), env.getDataSourceTransferService()));
        scanCtrl.set(new ScanController(env.getScanService(), env.getExportService()));
        metaCtrl.set(new MetadataController(env.getMetadataService(), env.getTableDocService()));
        reportCtrl.set(new ReportExportController(env.getWordReportExportService()));
        tagCtrl.set(new TagController(env.getTagService()));
        aiCtrl.set(new AiConfigController(env.getAiConfigService()));
        licenseCtrl.set(new LicenseController(env.getLicenseService()));
    }

    /** 服务就绪后回填托盘菜单引用(原 onReady 的托盘部分),桌面安装版由 main 在 finishInit 后调用 */
    public void markTrayReady() {
        trayManager.onReady(app.port());
    }

    /** 启动失败时关闭本进程拉起的 --app 窗口,避免残留孤儿浏览器窗口 */
    public void closeBrowserWindow() {
        browserOpener.closeWindow();
    }
}
