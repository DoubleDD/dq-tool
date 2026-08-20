package com.example.dq.web;

import com.example.dq.config.AppShutdown;
import com.example.dq.config.BrowserOpener;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DesktopSession;
import com.example.dq.config.DqProperties;
import com.example.dq.config.KernelConfigAdapter;
import com.example.dq.config.StartupLog;
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
 * 启动时序:构造(内核对象图 + 路由)→ start 绑定 → openBrowser 开窗 → finishInit 完成建表/迁移并置就绪。
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final Javalin app;
    private final ServiceEnv env;
    private final DesktopSession session;
    private final BrowserOpener browserOpener;
    private final TrayManager trayManager;
    /** 共享内核就绪标志:finishInit 完成建表/迁移/恢复后置 true,之前业务接口被闸门拦成 503 */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public WebServer(ConfigLoader.AppConfig config) throws Exception {
        DqProperties props = config.dq();

        // ---- 共享内核:H2 连接池 + 全部业务服务对象图(纯内存装配);建表/迁移/中断恢复等
        //      持久化重活延后到 HTTP 绑定与开窗之后(finishInit),期间业务接口由就绪闸门返回 503 ----
        StartupLog.log("  创建共享内核 ServiceEnv(H2 连接池 + 业务服务对象图)...");
        this.env = new ServiceEnv(KernelConfigAdapter.toKernelConfig(config));
        StartupLog.log("  ServiceEnv 就绪,创建 Javalin 与路由...");

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
            registerRoutes(cfg.routes, env.getLicenseService(),
                    new DataSourceController(env.getDataSourceService(), env.getDataSourceTransferService()),
                    new ScanController(env.getScanService(), env.getExportService()),
                    new MetadataController(env.getMetadataService(), env.getTableDocService()),
                    new ReportExportController(env.getWordReportExportService()),
                    new TagController(env.getTagService()),
                    new AiConfigController(env.getAiConfigService()), new LicenseController(env.getLicenseService()),
                    new LogController(logStreamAppender), sessionRef);
        });

        // ---- 桌面生命周期(原 Spring 事件/调度挂载点,改显式装配;退出动作统一走 AppShutdown) ----
        AppShutdown shutdown = new AppShutdown(app, env.getDataSource());
        this.session = new DesktopSession(props, shutdown);
        this.browserOpener = new BrowserOpener(session);
        this.trayManager = new TrayManager(browserOpener, session, shutdown);
        sessionRef.set(session);
        this.session.start();
        StartupLog.log("  WebServer 构造完成(路由 + 桌面生命周期组件)");
    }

    private void registerRoutes(RoutesConfig routes, LicenseService licenseService, DataSourceController ds,
                                ScanController scan, MetadataController meta, ReportExportController reportExport,
                                TagController tag, AiConfigController aiConfig,
                                LicenseController license, LogController logCtrl, AtomicReference<DesktopSession> sessionRef) {
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
        routes.get("/api/datasources", ds::list);
        routes.post("/api/datasources", ds::create);
        routes.put("/api/datasources/{id}", ds::update);
        routes.put("/api/datasources/{id}/schema-filter", ds::updateSchemaFilter);
        routes.delete("/api/datasources/{id}", ds::delete);
        routes.post("/api/datasources/test", ds::test);
        routes.post("/api/datasources/preview-databases", ds::previewDatabases);
        routes.get("/api/datasources/export", ds::export);
        routes.post("/api/datasources/import", ds::importDs);

        // ---- 扫描作业 ----
        routes.get("/api/scans/defaults", scan::defaults);
        routes.post("/api/scans", scan::create);
        routes.get("/api/scans", scan::list);
        routes.get("/api/scans/{jobId}", scan::get);
        routes.post("/api/scans/{jobId}/cancel", scan::cancel);
        routes.post("/api/scans/{jobId}/resume", scan::resume);
        routes.delete("/api/scans/{jobId}", scan::delete);
        routes.get("/api/scans/{jobId}/tables/{tableName}/columns", scan::columns);
        routes.get("/api/scans/{jobId}/export", scan::export);

        // ---- 元数据/浏览(/api/datasources/{dsId} 下) ----
        routes.get("/api/datasources/{dsId}/databases", meta::listDatabases);
        routes.get("/api/datasources/{dsId}/schemas", meta::listSchemas);
        routes.get("/api/datasources/{dsId}/schema-stats", meta::listSchemaStats);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables", meta::listTables);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/columns", meta::tableColumns);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/indexes", meta::tableIndexes);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/column-count", meta::countColumns);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/latest-scan-jobs", meta::latestScanJobs);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/running-scans", meta::runningScans);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-docs", meta::tableDocs);
        routes.post("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/doc", meta::generateTableDoc);
        routes.put("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/doc", meta::updateTableDoc);
        routes.put("/api/datasources/{dsId}/schemas/{schema}/description", meta::updateSchemaDescription);

        // ---- Word 报告异步导出任务 ----
        routes.post("/api/datasources/{dsId}/report/exports", reportExport::submit);
        routes.get("/api/report-exports", reportExport::list);
        routes.get("/api/report-exports/{id}/download", reportExport::download);
        routes.post("/api/report-exports/{id}/open", reportExport::open);
        routes.post("/api/report-exports/{id}/reveal", reportExport::reveal);

        // ---- 表标记与统计 ----
        routes.get("/api/tags", tag::list);
        routes.post("/api/tags", tag::create);
        routes.put("/api/tags/{id}", tag::update);
        routes.delete("/api/tags/{id}", tag::delete);
        routes.get("/api/tags/{id}/stats", tag::stats);
        routes.get("/api/datasources/{dsId}/schema-tag-stats", tag::schemaTagStats);
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-tags", tag::tableTags);
        routes.put("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/tags", tag::replaceTableTags);

        // ---- AI 配置 / 授权 / 心跳 ----
        routes.get("/api/ai-config", aiConfig::get);
        routes.put("/api/ai-config", aiConfig::save);
        routes.get("/api/license/status", license::status);
        routes.post("/api/license/activate", license::activate);
        // 授权码管理(仅配置了签发私钥的管理员实例;在 /api/license 前缀下,不被激活拦截)
        routes.get("/api/license/admin/codes", license::adminList);
        routes.post("/api/license/admin/codes", license::adminGenerate);
        routes.delete("/api/license/admin/codes/{id}", license::adminDelete);
        routes.get("/api/heartbeat", ctx -> {
            DesktopSession desktopSession = sessionRef.get();
            if (desktopSession != null) {
                desktopSession.beat();
            }
            ctx.status(204);
        });

        // 就绪探针:前端挂载应用前轮询本端点,共享内核就绪后返回 200(未就绪 503 + Retry-After)。
        // 与 /api/heartbeat(页面心跳,只表示服务在跑)区分:业务可用性由本端点表达
        routes.get("/api/health", ctx -> {
            if (ready.get()) {
                ctx.json(Map.of("status", "ok"));
            } else {
                ctx.status(503).header("Retry-After", "1").json(Map.of("status", "starting"));
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
        env.shutdown();
    }

    /** 实际监听端口(server.port=0 时为容器随机分配的结果) */
    public int port() {
        return app.port();
    }

    /**
     * HTTP 绑定后立即打开应用窗口(桌面安装版):页面外壳秒出,后端就绪由前端轮询
     * /api/health 等待——不再等共享内核初始化完成(原 onReady 拆分为 openBrowser + finishInit)。
     * 必须在 start() 之后调用;headless 服务器部署自动跳过(见 BrowserOpener)。
     */
    public void openBrowser() {
        browserOpener.openBrowser(app.port());
    }

    /**
     * 完成共享内核重活(H2 建表/迁移 + 中断任务恢复 + 报告任务恢复)并置服务就绪:
     * 就绪前业务接口由闸门返回 503,完成后 /api/health 转 200,前端随即加载数据。
     * 启动失败时由 main 调 closeBrowserWindow。托盘回填由 main 在就绪后调 markTrayReady,
     * 不并入本方法——避免测试 JVM(非 headless)里误装系统托盘图标。
     */
    public void finishInit() {
        env.initDatabase();
        ready.set(true);
        StartupLog.log("  共享内核初始化完成,服务就绪");
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
