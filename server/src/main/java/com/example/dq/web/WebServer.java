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
import com.example.dq.controller.AiUsageController;
import com.example.dq.controller.AnnotationController;
import com.example.dq.controller.ChangelogController;
import com.example.dq.controller.CompareController;
import com.example.dq.controller.CompareImportController;
import com.example.dq.controller.DataSourceController;
import com.example.dq.controller.DiagnosticsController;
import com.example.dq.controller.LicenseController;
import com.example.dq.controller.ListExportController;
import com.example.dq.controller.LanController;
import com.example.dq.controller.MetadataController;
import com.example.dq.controller.MetadataSyncController;
import com.example.dq.controller.PreviewController;
import com.example.dq.controller.RelationController;
import com.example.dq.controller.ReportExportController;
import com.example.dq.controller.SampleExportController;
import com.example.dq.controller.SqlConsoleController;
import com.example.dq.controller.ErrorCenterController;
import com.example.dq.controller.ExportCenterController;
import com.example.dq.controller.LogController;
import com.example.dq.controller.ManualCollectController;
import com.example.dq.controller.ObjectCatalogController;
import com.example.dq.controller.ScanController;
import com.example.dq.controller.ScanTransferController;
import com.example.dq.controller.SystemSettingsController;
import com.example.dq.controller.SystemController;
import com.example.dq.controller.TagController;
import com.example.dq.env.ServiceEnv;
import com.example.dq.license.LicenseMenu;
import com.example.dq.model.LicenseAdminRequiredException;
import com.example.dq.model.LicenseMenuRequiredException;
import com.example.dq.model.LicenseRequiredException;
import com.example.dq.service.LicenseService;
import com.example.dq.service.SystemSettingsService;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Web 层装配与路由(去 Spring 后替代容器装配 + DispatcherServlet):
 * 构造对象图(repository → service → handler,全部构造注入),注册 68 个端点、
 * 授权前置校验(替代 LicenseInterceptor)、统一异常映射(响应体 {"message": ...},
 * 与改造前 GlobalExceptionHandler 一致)、静态资源与 SPA 回退(替代 SpaWebConfig)、
 * 就绪闸门与 /api/health 就绪探针(共享内核未就绪前业务接口统一 503,前端轮询到 200 再加载数据)。
 * Javalin 7 的路由只能在 Javalin.create 的配置回调里注册(cfg.routes),创建后不可追加。
 * <p>
 * 启动时序(启动优化:先开窗、后建内核):
 * 构造(路由引用骨架 + 静态资源 + 探针,毫秒级,不建 ServiceEnv)→ start 绑定 → openBrowser 开窗
 * (页面外壳秒出,前端轮询 /api/health 看到实时启动阶段)→ finishInit 构建共享内核
 * (H2 池 + 服务对象图 + 建表/迁移/恢复)并置就绪。业务路由在 create 回调里照常注册,
 * 但 handler 引用 AtomicReference 控制器,内核构建完成后注入——就绪闸门保证注入前
 * 业务接口统一 503,不会触到空引用。
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    /**
     * 访问管控未命中时的页面提示(纯 API 形态下浏览器直接打开页面路由的场景)
     */
    private static final String BLOCKED_PAGE = """
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
            <title>访问被拒绝</title></head>
            <body style="font-family:system-ui,sans-serif;padding:48px;color:#303133">
            <h2>浏览器直接访问已被禁止</h2>
            <p>请使用桌面应用打开,或通过
            <code>http://&lt;host&gt;:&lt;port&gt;/?token=&lt;访问令牌&gt;</code> 访问。</p>
            </body></html>""";

    private final Javalin app;
    /**
     * 共享内核(H2 池 + 服务对象图),openBrowser 开窗后才构建(启动优化)
     */
    private volatile ServiceEnv env;
    private final ConfigLoader.AppConfig config;
    private final DesktopSession session;
    private final BrowserOpener browserOpener;
    private final TrayManager trayManager;
    /**
     * 共享内核就绪标志:finishInit 完成建表/迁移/恢复后置 true,之前业务接口被闸门拦成 503
     */
    private final AtomicBoolean ready = new AtomicBoolean(false);
    /**
     * 静态资源(请求路径 → 内容):构造时一次性预读进内存,请求期零 I/O(来源见 preloadStatic)
     */
    private final Map<String, byte[]> staticCache;
    /**
     * 静态资源清单(/api/assets-manifest 出口),启动诊断复核失败资源时比对用
     */
    private final List<String> staticManifest;

    // 控制器/服务引用:路由在 create 回调里注册,内核(finishInit)构建完成后注入。
    // 就绪闸门在授权校验之前短路(未就绪一律 503),注入前不会触到空引用
    private final AtomicReference<LicenseService> licenseServiceRef = new AtomicReference<>();
    private final AtomicReference<DataSourceController> dataSourceCtrl = new AtomicReference<>();
    private final AtomicReference<ScanController> scanCtrl = new AtomicReference<>();
    private final AtomicReference<ScanTransferController> scanTransferCtrl = new AtomicReference<>();
    private final AtomicReference<MetadataController> metaCtrl = new AtomicReference<>();
    private final AtomicReference<MetadataSyncController> metaSyncCtrl = new AtomicReference<>();
    private final AtomicReference<ReportExportController> reportCtrl = new AtomicReference<>();
    private final AtomicReference<SampleExportController> sampleExportCtrl = new AtomicReference<>();
    private final AtomicReference<CompareController> compareCtrl = new AtomicReference<>();
    private final AtomicReference<CompareImportController> compareImportCtrl = new AtomicReference<>();
    private final AtomicReference<TagController> tagCtrl = new AtomicReference<>();
    private final AtomicReference<ManualCollectController> manualCollectCtrl = new AtomicReference<>();
    private final AtomicReference<ObjectCatalogController> objectCatalogCtrl = new AtomicReference<>();
    private final AtomicReference<AiConfigController> aiCtrl = new AtomicReference<>();
    private final AtomicReference<AiUsageController> aiUsageCtrl = new AtomicReference<>();
    private final AtomicReference<SystemSettingsController> settingsCtrl = new AtomicReference<>();
    private final AtomicReference<LicenseController> licenseCtrl = new AtomicReference<>();
    private final AtomicReference<PreviewController> previewCtrl = new AtomicReference<>();
    private final AtomicReference<SqlConsoleController> sqlConsoleCtrl = new AtomicReference<>();
    private final AtomicReference<AnnotationController> annotationCtrl = new AtomicReference<>();
    private final AtomicReference<ListExportController> listExportCtrl = new AtomicReference<>();
    private final AtomicReference<DiagnosticsController> diagnosticsCtrl = new AtomicReference<>();
    private final AtomicReference<ChangelogController> changelogCtrl = new AtomicReference<>();
    private final AtomicReference<LanController> lanCtrl = new AtomicReference<>();
    private final AtomicReference<RelationController> relationCtrl = new AtomicReference<>();
    private final AtomicReference<ErrorCenterController> errorCtrl = new AtomicReference<>();
    private final AtomicReference<ExportCenterController> exportCenterCtrl = new AtomicReference<>();
    private final AtomicReference<SystemController> systemCtrl = new AtomicReference<>();
    /**
     * 实时日志 Appender 引用:LogController(SSE)与 DiagnosticsController(错误日志摘录)共用同一实例
     */
    private LogStreamAppender logStreamAppender;
    /**
     * 错误采集 Appender:把 ERROR / 带异常的 WARN 日志事件汇入错误中心(内核就绪后经 setSink 注入出口)
     */
    private final ErrorCaptureAppender errorCaptureAppender = new ErrorCaptureAppender();

    public WebServer(ConfigLoader.AppConfig config) throws Exception {
        this.config = config;
        DqProperties props = config.dq();

        // 静态资源来源:配置 dq.web.static-dir 且目录存在 → 磁盘发静态(jpackage/安装版);
        // 否则退回 classpath 内嵌静态(dev/测试)。必须在 Javalin.create 前算好(路由 lambda 捕获这两个字段)
        this.staticCache = preloadStatic(props.getWeb().getStaticDir());
        this.staticManifest = staticCache.keySet().stream().sorted().toList();

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
        this.logStreamAppender = logStreamAppender;
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        logStreamAppender.setContext(loggerContext);
        logStreamAppender.start();
        // 错误采集 Appender:与实时日志流并列挂在 root,ERROR / 带异常的 WARN 汇入错误中心。
        // 内核未就绪前事件进自身有界缓冲,finishInit 注入 sink 后回灌(启动期异常不丢)
        errorCaptureAppender.setContext(loggerContext);
        errorCaptureAppender.start();
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logStreamAppender);
        rootLogger.addAppender(errorCaptureAppender);

        // 心跳路由要在 create 回调里注册,而 DesktopSession 依赖 AppShutdown(需要 Javalin 实例),
        // 用引用后填打破循环(服务 start 之前回调不可能触发,不会读到 null)
        AtomicReference<DesktopSession> sessionRef = new AtomicReference<>();

        this.app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson3(objectMapper, false));
            // CORS(方案 B:宽松 CORS + 每次启动随机 token 门禁):交付 jar 是纯 API,Tauri webview 从
            // tauri://localhost(自定义协议)/ http://tauri.localhost 跨域访问 127.0.0.1:<动态端口>。
            // 用 Javalin 自带 CorsPlugin 一把梭:anyHost() 的 "*" 分支排在 Origin: null 判断之前,故 null 也回 *;
            // allowCredentials 必须为 false(* 与 credentials 互斥,我们不用跨域 Cookie)。插件还负责把无匹配路由的
            // OPTIONS 预检改写为 200,并回显 Access-Control-Request-Headers(即 x-dq-token 自动放行)。
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.path = "/api/*";
                rule.anyHost();
                rule.exposeHeader("Content-Disposition");
                rule.maxAge = 3600;
            }));
            if (staticCache.isEmpty()) {
                StartupLog.log("  静态资源为空:未配置可用的 dq.web.static-dir,classpath 也无 /static"
                        + "(纯 API 形态下正常,前端由 Tauri frontendDist / jpackage static-dir 提供)");
            }
            cfg.startup.showJavalinBanner = false;
            registerRoutes(cfg.routes, licenseServiceRef,
                    dataSourceCtrl, scanCtrl, scanTransferCtrl, metaCtrl, metaSyncCtrl, reportCtrl, sampleExportCtrl, compareCtrl,
                    compareImportCtrl, tagCtrl,
                    manualCollectCtrl, objectCatalogCtrl, aiCtrl, aiUsageCtrl,
                    settingsCtrl, licenseCtrl, previewCtrl, sqlConsoleCtrl, annotationCtrl, listExportCtrl, diagnosticsCtrl,
                    changelogCtrl, lanCtrl, relationCtrl,
                    new LogController(logStreamAppender), errorCtrl, exportCenterCtrl, systemCtrl, sessionRef);
        });

        // ---- 桌面生命周期(原 Spring 事件/调度挂载点,改显式装配;退出动作统一走 AppShutdown) ----
        // 连接池由共享内核懒构建,退出时按需取(内核未构建完就退出时跳过关池)
        AppShutdown shutdown = new AppShutdown(app, () -> env == null ? null : env.getDataSource());
        // 心跳间隔供应器:内核就绪后实时读系统设置(页面可改),未就绪/读取失败回落默认,绝不让看门狗因设置读取异常中断
        this.session = new DesktopSession(props, shutdown, () -> {
            try {
                return env != null ? env.getSystemSettingsService().heartbeatIntervalSeconds()
                        : SystemSettingsService.DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
            } catch (Exception e) {
                return SystemSettingsService.DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
            }
        });
        this.browserOpener = new BrowserOpener(session, Path.of(config.dataDir(), "browser-app.txt"));
        this.trayManager = new TrayManager(browserOpener, session, shutdown);
        sessionRef.set(session);
        this.session.start();
        StartupLog.log("  WebServer 构造完成(路由引用骨架 + 桌面生命周期组件)");
    }

    private void registerRoutes(RoutesConfig routes, AtomicReference<LicenseService> licenseServiceRef,
                                AtomicReference<DataSourceController> dataSourceCtrl,
                                AtomicReference<ScanController> scanCtrl,
                                AtomicReference<ScanTransferController> scanTransferCtrl,
                                AtomicReference<MetadataController> metaCtrl,
                                AtomicReference<MetadataSyncController> metaSyncCtrl,
                                AtomicReference<ReportExportController> reportCtrl,
                                AtomicReference<SampleExportController> sampleExportCtrl,
                                AtomicReference<CompareController> compareCtrl,
                                AtomicReference<CompareImportController> compareImportCtrl,
                                AtomicReference<TagController> tagCtrl,
                                AtomicReference<ManualCollectController> manualCollectCtrl,
                                AtomicReference<ObjectCatalogController> objectCatalogCtrl,
                                AtomicReference<AiConfigController> aiCtrl,
                                AtomicReference<AiUsageController> aiUsageCtrl,
                                AtomicReference<SystemSettingsController> settingsCtrl,
                                AtomicReference<LicenseController> licenseCtrl,
                                AtomicReference<PreviewController> previewCtrl,
                                AtomicReference<SqlConsoleController> sqlConsoleCtrl,
                                AtomicReference<AnnotationController> annotationCtrl,
                                AtomicReference<ListExportController> listExportCtrl,
                                AtomicReference<DiagnosticsController> diagnosticsCtrl,
                                AtomicReference<ChangelogController> changelogCtrl,
                                AtomicReference<LanController> lanCtrl,
                                AtomicReference<RelationController> relationCtrl,
                                LogController logCtrl, AtomicReference<ErrorCenterController> errorCtrl,
                                AtomicReference<ExportCenterController> exportCenterCtrl,
                                AtomicReference<SystemController> systemCtrl,
                                AtomicReference<DesktopSession> sessionRef) {
        // 浏览器访问管控(默认关闭):配置了 dq.access-token 才生效,未配置时行为与改动前完全一致。
        // 用全局 before 而非 beforeMatched:既拦 /api/**(含未匹配路由),也拦 jpackage/static-dir 形态下的页面路由;
        // 位于最前,早于就绪闸门与授权闸门。OPTIONS 预检、固定豁免清单、带扩展名的静态资源在 AccessGuard 内放行。
        List<String> accessTokens = config.dq().getAccessTokens();
        if (!accessTokens.isEmpty()) {
            // CORS 排查(三平台 webview 实发 Origin):每进程按不同来源各记一次(不含 token、不回传接口)。
            // 若某平台 webview 完全不发 Origin,这里就不会出现它的来源 —— 即 CorsPlugin 直接 return、
            // 预检失败、API 全挂,需按实施计划 §7 回落 Rust loopback 反代。
            Set<String> seenOrigins = java.util.concurrent.ConcurrentHashMap.newKeySet();
            routes.before(ctx -> {
                String origin = ctx.header("Origin");
                if (origin != null && seenOrigins.add(origin)) {
                    log.info("  /api 请求来源 Origin={}(CORS 排查,每种来源仅记一次)", origin);
                }
                // 授权码「免接口鉴权」标记生效时(演示用,已激活未过期且授权码带标记)整实例跳过令牌校验;
                // isAuthBypassed 内部对异常按 false 处理(库未就绪等),宁严勿松
                LicenseService licenseService = licenseServiceRef.get();
                if (licenseService != null && licenseService.isAuthBypassed()) {
                    return;
                }
                AccessGuard.enforce(ctx, accessTokens);
            });
            routes.exception(AccessGuard.AccessBlockedException.class, (e, ctx) -> {
                if (e.isApi() || !"GET".equals(ctx.method().name())) {
                    ctx.status(403).json(Map.of("message",
                            "访问被拒绝:请通过桌面应用打开,或使用带访问令牌的地址访问"));
                    return;
                }
                ctx.status(403).contentType("text/html;charset=utf-8").result(BLOCKED_PAGE);
            });
            StartupLog.log("  浏览器访问管控已启用(请求须携带访问令牌;令牌值不落日志)");
        }
        // 授权前置校验(替代 LicenseInterceptor):/api/** 除授权接口自身与页面心跳外,要求已激活且未过期;
        // beforeMatched 只在路由命中时触发,与原 Spring 拦截器一致(未匹配的 /api/** 仍走 404 而非 401)
        routes.beforeMatched("/api/*", ctx -> {
            String path = ctx.path();
            // 就绪闸门:共享内核(建表/迁移/中断恢复)未就绪前,除就绪探针与页面心跳外所有业务接口统一 503。
            // 必须在授权校验之前短路——licenseService 虽已构建,但依赖的库表可能尚未迁移完成
            if (path.equals("/api/health") || path.equals("/api/heartbeat") || path.equals("/api/assets-manifest")) {
                return;
            }
            if (!ready.get()) {
                throw new ServiceNotReadyException();
            }
            LicenseService licenseService = licenseServiceRef.get();
            // 授权码管理(license-admin 菜单):/api/license 前缀下不被激活拦截,但需授权码显式开放该菜单
            if (path.startsWith("/api/license/admin")) {
                licenseService.checkMenu(LicenseMenu.LICENSE_ADMIN, false);
                return;
            }
            // 数据比对(compare 菜单):需已激活且授权码开放 compare 菜单,未授权 403;
            // 批量导入与导入模版同属受控功能,与 /api/compare-jobs 同门禁
            if (path.startsWith("/api/compare-jobs") || path.startsWith("/api/compare-imports")
                    || path.startsWith("/api/compare-import-template")) {
                licenseService.checkMenu(LicenseMenu.COMPARE);
                return;
            }
            // 前端错误上报:纯采集入口,与授权无关必须永远放行 —— 否则未开放「错误中心」菜单的实例
            // 前端错误会 403 而静默丢失,采集能力不能受菜单可见性影响
            if (path.startsWith("/api/errors/report")) {
                return;
            }
            // 错误中心(error-center 菜单):查看/筛选/导出需已激活且授权码开放该菜单,未授权 403
            if (path.startsWith("/api/errors")) {
                licenseService.checkMenu(LicenseMenu.ERROR_CENTER);
                return;
            }
            // 局域网共享出口(/api/lan/share/*)放行激活检查:供同网段其他实例 HTTP 拉取数据,peer 侧无本机授权上下文
            if (path.startsWith("/api/license") || path.startsWith("/api/diagnostics") || path.startsWith("/api/changelog")
                    || path.startsWith("/api/lan/share/") || path.equals("/api/heartbeat")) {
                return;
            }
            licenseService.checkActive();
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
        routes.exception(LicenseMenuRequiredException.class, (e, ctx) -> {
            log.warn("授权码未开放该菜单: {}", e.getMessage());
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
        // 数据源管理页卡片拖拽改分组(轻量单列更新,不走全量 PUT 的连库探测)
        routes.put("/api/datasources/{id}/group", ctx -> dataSourceCtrl.get().updateGroup(ctx));
        routes.delete("/api/datasources/{id}", ctx -> dataSourceCtrl.get().delete(ctx));
        routes.post("/api/datasources/test", ctx -> dataSourceCtrl.get().test(ctx));
        routes.post("/api/datasources/preview-databases", ctx -> dataSourceCtrl.get().previewDatabases(ctx));
        routes.get("/api/db-types/{dbType}/system-schemas", ctx -> dataSourceCtrl.get().systemSchemas(ctx));
        routes.get("/api/datasources/export", ctx -> dataSourceCtrl.get().export(ctx));
        // 元数据缓存导出(结构缓存 + 标注数据,供离线导入使用);静态段优先于 {dsId} 路径参数
        routes.get("/api/datasources/metadata-export", ctx -> dataSourceCtrl.get().exportMetadata(ctx));
        // 元数据导入预检:解析文件内数据源并按连接身份自动匹配本机数据源,供前端做映射
        routes.post("/api/datasources/metadata-transfer/preview", ctx -> dataSourceCtrl.get().previewMetadataImport(ctx));
        routes.post("/api/datasources/import", ctx -> dataSourceCtrl.get().importDs(ctx));

        // ---- 扫描作业 ----
        routes.get("/api/scans/defaults", ctx -> scanCtrl.get().defaults(ctx));
        // 扫描记录导出/导入(跨机器迁移):静态段须先于 {jobId} 注册,避免被路径参数路由截获
        routes.get("/api/scans/transfer/export", ctx -> scanTransferCtrl.get().export(ctx));
        routes.post("/api/scans/transfer/preview", ctx -> scanTransferCtrl.get().preview(ctx));
        routes.post("/api/scans/transfer/import", ctx -> scanTransferCtrl.get().importJson(ctx));
        routes.post("/api/scans", ctx -> scanCtrl.get().create(ctx));
        routes.get("/api/scans", ctx -> scanCtrl.get().list(ctx));
        routes.get("/api/scans/{jobId}", ctx -> scanCtrl.get().get(ctx));
        routes.post("/api/scans/{jobId}/cancel", ctx -> scanCtrl.get().cancel(ctx));
        routes.post("/api/scans/{jobId}/finish", ctx -> scanCtrl.get().finish(ctx));
        routes.post("/api/scans/{jobId}/resume", ctx -> scanCtrl.get().resume(ctx));
        routes.delete("/api/scans/{jobId}", ctx -> scanCtrl.get().delete(ctx));
        routes.get("/api/scans/{jobId}/tables/{tableName}/columns", ctx -> scanCtrl.get().columns(ctx));
        routes.get("/api/scans/{jobId}/export", ctx -> scanCtrl.get().export(ctx));
        routes.get("/api/scans/{jobId}/export-word", ctx -> scanCtrl.get().exportWord(ctx));

        // ---- 元数据/浏览(/api/datasources/{dsId} 下) ----
        routes.get("/api/datasources/{dsId}/databases", ctx -> metaCtrl.get().listDatabases(ctx));
        routes.get("/api/datasources/{dsId}/schemas", ctx -> metaCtrl.get().listSchemas(ctx));
        routes.get("/api/datasources/{dsId}/schema-stats", ctx -> metaCtrl.get().listSchemaStats(ctx));
        routes.get("/api/datasources/{dsId}/export-dbstruct-word", ctx -> metaCtrl.get().exportDbStructWord(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables", ctx -> metaCtrl.get().listTables(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/columns", ctx -> metaCtrl.get().tableColumns(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/indexes", ctx -> metaCtrl.get().tableIndexes(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/ddl", ctx -> metaCtrl.get().tableDdl(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/preview", ctx -> previewCtrl.get().preview(ctx));
        // 预览全量导出:符合条件的前 20 万行流式 xlsx(静态尾段 export,不与 /preview 冲突)
        routes.get("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/preview/export", ctx -> previewCtrl.get().export(ctx));
        // SQL 控制台:对数据源执行任意 SQL,返回结果集或受影响行数
        routes.post("/api/datasources/{dsId}/sql/execute", ctx -> sqlConsoleCtrl.get().execute(ctx));
        // SQL 控制台「本地 H2 库」:应用自身配置库只读查询(写语句 400),schema/表/字段供智能提示
        routes.post("/api/sql-console/local-h2/execute", ctx -> sqlConsoleCtrl.get().executeLocalH2(ctx));
        routes.get("/api/sql-console/local-h2/schemas", ctx -> sqlConsoleCtrl.get().localH2Schemas(ctx));
        routes.get("/api/sql-console/local-h2/tables", ctx -> sqlConsoleCtrl.get().localH2Tables(ctx));
        routes.get("/api/sql-console/local-h2/columns", ctx -> sqlConsoleCtrl.get().localH2Columns(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/column-count", ctx -> metaCtrl.get().countColumns(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/columns", ctx -> metaCtrl.get().schemaColumns(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/latest-scan-jobs", ctx -> metaCtrl.get().latestScanJobs(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/running-scans", ctx -> metaCtrl.get().runningScans(ctx));
        // 最新扫描结果 Excel 导出:每表最近一次表级 DONE 快照,跨任务,不依赖指定任务记录
        routes.get("/api/datasources/{dsId}/schemas/{schema}/export-latest", ctx -> scanCtrl.get().exportLatest(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-docs", ctx -> metaCtrl.get().tableDocs(ctx));
        // 表所属系统:GET 取整库 map,PUT 批量设置/清除(静态段 table-systems 与 {table} 不冲突,同 table-docs 先例)
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-systems", ctx -> metaCtrl.get().tableSystems(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/table-systems", ctx -> metaCtrl.get().batchSetTableSystems(ctx));
        routes.post("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/doc", ctx -> metaCtrl.get().generateTableDoc(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/doc", ctx -> metaCtrl.get().updateTableDoc(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/description", ctx -> metaCtrl.get().updateSchemaDescription(ctx));

        // ---- Word 报告异步导出任务 ----
        routes.post("/api/datasources/{dsId}/report/exports", ctx -> reportCtrl.get().submit(ctx));
        routes.get("/api/report-exports", ctx -> reportCtrl.get().list(ctx));
        routes.get("/api/report-exports/{id}/download", ctx -> reportCtrl.get().download(ctx));
        routes.post("/api/report-exports/{id}/open", ctx -> reportCtrl.get().open(ctx));
        routes.post("/api/report-exports/{id}/reveal", ctx -> reportCtrl.get().reveal(ctx));

        // ---- 系统级:调系统默认程序打开数据目录内产物(Tauri 导出直存后前端「打开文件」;
        // save-download 为浏览器 /--app 形态与 Tauri 对齐的「后端直存数据目录」:自调目标导出接口写盘) ----
        routes.post("/api/system/open", ctx -> systemCtrl.get().open(ctx));
        routes.post("/api/system/reveal", ctx -> systemCtrl.get().reveal(ctx));
        routes.post("/api/system/save-download", ctx -> systemCtrl.get().saveDownload(ctx));

        // ---- 导出中心(V66 登记,V67 push + 点击即登记状态机):全部导出统一登记,含相对数据目录路径 ----
        routes.get("/api/export-center", ctx -> exportCenterCtrl.get().list(ctx));
        routes.post("/api/export-center/landed", ctx -> exportCenterCtrl.get().landed(ctx));
        routes.post("/api/export-center/fail", ctx -> exportCenterCtrl.get().fail(ctx));
        routes.delete("/api/export-center/{kind}/{id}", ctx -> exportCenterCtrl.get().delete(ctx));

        // ---- 表格批量导入数据源 + 抽样导出任务 ----
        routes.post("/api/sample-exports", ctx -> sampleExportCtrl.get().submit(ctx));
        routes.get("/api/sample-exports", ctx -> sampleExportCtrl.get().list(ctx));
        // 注意避开 /api/sample-exports/{id} 同前缀静态段:实测 Javalin 7 会把它路由给 {id} 导致类型转换 400
        routes.get("/api/sample-export-template", ctx -> sampleExportCtrl.get().template(ctx));
        routes.get("/api/sample-exports/{id}", ctx -> sampleExportCtrl.get().detail(ctx));
        routes.get("/api/sample-exports/{id}/download", ctx -> sampleExportCtrl.get().download(ctx));
        routes.post("/api/sample-exports/{id}/open-dir", ctx -> sampleExportCtrl.get().openDir(ctx));
        routes.post("/api/sample-exports/{id}/open", ctx -> sampleExportCtrl.get().openFile(ctx));
        routes.post("/api/sample-exports/{id}/pause", ctx -> sampleExportCtrl.get().pause(ctx));
        routes.post("/api/sample-exports/{id}/resume", ctx -> sampleExportCtrl.get().resume(ctx));
        routes.post("/api/sample-exports/{id}/export", ctx -> sampleExportCtrl.get().export(ctx));
        routes.post("/api/sample-exports/{id}/reimport", ctx -> sampleExportCtrl.get().reimport(ctx));
        // 批量删除刻意避开 /api/sample-exports/ 前缀注册(同前缀静态段会被 {id} 吃掉报 400,见上方 template 注释)
        routes.post("/api/sample-exports-delete", ctx -> sampleExportCtrl.get().delete(ctx));

        // ---- 数据比对任务 ----
        routes.post("/api/compare-jobs", ctx -> compareCtrl.get().submit(ctx));
        routes.post("/api/compare-jobs/mapping-suggest", ctx -> compareCtrl.get().suggestMapping(ctx));
        routes.post("/api/compare-jobs/batch-delete", ctx -> compareCtrl.get().deleteBatch(ctx));
        routes.get("/api/compare-jobs/active", ctx -> compareCtrl.get().listActive(ctx));
        routes.get("/api/compare-jobs", ctx -> compareCtrl.get().list(ctx));
        routes.get("/api/compare-jobs/{id}", ctx -> compareCtrl.get().detail(ctx));
        routes.delete("/api/compare-jobs/{id}", ctx -> compareCtrl.get().delete(ctx));
        routes.get("/api/compare-jobs/{id}/diffs", ctx -> compareCtrl.get().diffs(ctx));
        routes.get("/api/compare-jobs/{id}/report", ctx -> compareCtrl.get().report(ctx));
        // 比对报告导出:服务端直存数据目录/compare(任务 ID 前缀命名,同名覆盖),返回 {path,name,size,checksum}
        routes.post("/api/compare-jobs/{id}/export", ctx -> compareCtrl.get().export(ctx));
        // 报告导出件「打开文件 / 打开文件夹」(导出统一直存数据目录/exports 后的入口)
        routes.post("/api/compare-jobs/{id}/open-export", ctx -> compareCtrl.get().openExport(ctx));
        routes.post("/api/compare-jobs/{id}/reveal-export", ctx -> compareCtrl.get().revealExport(ctx));
        routes.post("/api/compare-jobs/{id}/rerun", ctx -> compareCtrl.get().rerun(ctx));
        routes.post("/api/compare-jobs/{id}/archive", ctx -> compareCtrl.get().archive(ctx));
        // 「字段审核」确认映射并开始比对(仅 PENDING)/ 向导编辑提交(PENDING 保存待处理,终态保存并重跑)
        routes.post("/api/compare-jobs/{id}/confirm-mapping", ctx -> compareCtrl.get().confirmMapping(ctx));
        // 「待处理」直接开始比对(编辑向导「保存并比对」在 PUT 之后调用;仅 PENDING 且非 DS_ERROR)
        routes.post("/api/compare-jobs/{id}/start", ctx -> compareCtrl.get().start(ctx));
        routes.put("/api/compare-jobs/{id}", ctx -> compareCtrl.get().update(ctx));
        // 单目标自定义显示名(V72,备用接口;向导清单编辑随任务 PUT 提交,不走这里)
        routes.put("/api/compare-jobs/{id}/targets/{targetId}/display-name", ctx -> compareCtrl.get().updateTargetDisplayName(ctx));

        // ---- 比对任务批量导入(一 sheet 一任务,原件留档;门禁同 compare) ----
        routes.post("/api/compare-imports", ctx -> compareImportCtrl.get().submit(ctx));
        // 注意避开 /api/compare-imports/{id} 同前缀静态段:实测 Javalin 7 会把它路由给 {id} 导致类型转换 400
        // (同 /api/sample-export-template 先例)
        routes.get("/api/compare-import-template", ctx -> compareImportCtrl.get().template(ctx));
        routes.get("/api/compare-imports/{id}", ctx -> compareImportCtrl.get().detail(ctx));
        routes.get("/api/compare-imports/{id}/file", ctx -> compareImportCtrl.get().downloadFile(ctx));
        routes.post("/api/compare-imports/{id}/confirm", ctx -> compareImportCtrl.get().confirm(ctx));

        // ---- 元数据批量同步(数据源页「刷新」)----
        routes.post("/api/metadata-sync", ctx -> metaSyncCtrl.get().submit(ctx));
        // 静态段 latest 须先于 {id} 注册,避免被路径参数路由截获(同 /api/scans/transfer 先例)
        routes.get("/api/metadata-sync/latest", ctx -> metaSyncCtrl.get().latest(ctx));
        routes.get("/api/metadata-sync/{id}", ctx -> metaSyncCtrl.get().detail(ctx));
        routes.post("/api/metadata-sync/{id}/cancel", ctx -> metaSyncCtrl.get().cancel(ctx));

        // ---- 表标记与统计 ----
        routes.get("/api/tags", ctx -> tagCtrl.get().list(ctx));
        routes.post("/api/tags", ctx -> tagCtrl.get().create(ctx));
        routes.put("/api/tags/{id}", ctx -> tagCtrl.get().update(ctx));
        routes.delete("/api/tags/{id}", ctx -> tagCtrl.get().delete(ctx));
        routes.get("/api/tags/{id}/stats", ctx -> tagCtrl.get().stats(ctx));
        routes.get("/api/datasources/{dsId}/schema-tag-stats", ctx -> tagCtrl.get().schemaTagStats(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/table-tags", ctx -> tagCtrl.get().tableTags(ctx));
        // 批量打标(只增不删),与 GET 同路径不同方法;单表整体替换走 tables/{table}/tags
        routes.put("/api/datasources/{dsId}/schemas/{schema}/table-tags", ctx -> tagCtrl.get().batchAddTableTags(ctx));
        routes.put("/api/datasources/{dsId}/schemas/{schema}/tables/{table}/tags", ctx -> tagCtrl.get().replaceTableTags(ctx));

        // ---- 人工采集(收藏重点关注的表) ----
        routes.get("/api/manual-collects", ctx -> manualCollectCtrl.get().list(ctx));
        routes.post("/api/manual-collects", ctx -> manualCollectCtrl.get().addBatch(ctx));
        routes.post("/api/manual-collects/batch-delete", ctx -> manualCollectCtrl.get().deleteBatch(ctx));
        routes.delete("/api/manual-collects/{id}", ctx -> manualCollectCtrl.get().delete(ctx));
        routes.get("/api/datasources/{dsId}/schemas/{schema}/manual-collects", ctx -> manualCollectCtrl.get().tableCollectMap(ctx));

        // ---- 对象管理(数据目录):目录树按数据源隔离,目录挂载表,挂载表登记关系表 ----
        routes.get("/api/datasources/{dsId}/object-catalog", ctx -> objectCatalogCtrl.get().tree(ctx));
        routes.post("/api/object-dirs", ctx -> objectCatalogCtrl.get().createDir(ctx));
        routes.post("/api/object-dirs/sort", ctx -> objectCatalogCtrl.get().sortDirs(ctx));
        routes.put("/api/object-dirs/{id}", ctx -> objectCatalogCtrl.get().renameDir(ctx));
        routes.delete("/api/object-dirs/{id}", ctx -> objectCatalogCtrl.get().deleteDir(ctx));
        routes.post("/api/object-dirs/{id}/move", ctx -> objectCatalogCtrl.get().moveDir(ctx));
        routes.post("/api/object-dirs/{id}/tables", ctx -> objectCatalogCtrl.get().mountTable(ctx));
        routes.delete("/api/object-tables/{id}", ctx -> objectCatalogCtrl.get().unmount(ctx));
        routes.post("/api/object-tables/{id}/move", ctx -> objectCatalogCtrl.get().moveTable(ctx));
        routes.post("/api/object-tables/{id}/relations", ctx -> objectCatalogCtrl.get().addRelation(ctx));
        routes.delete("/api/object-table-relations/{id}", ctx -> objectCatalogCtrl.get().removeRelation(ctx));
        routes.post("/api/object-table-relations/{id}/move", ctx -> objectCatalogCtrl.get().moveRelation(ctx));

        // ---- 标记与描述数据导出/导入(跨机器迁移) ----
        routes.get("/api/annotations/export", ctx -> annotationCtrl.get().export(ctx));
        routes.post("/api/annotations/import/preview", ctx -> annotationCtrl.get().previewImport(ctx));
        routes.post("/api/annotations/import", ctx -> annotationCtrl.get().importAnnotations(ctx));

        // ---- 通用列表导出(前端提交所见表格数据,渲染 xlsx 一次性下载) ----
        routes.post("/api/list-exports", ctx -> listExportCtrl.get().stage(ctx));
        routes.get("/api/list-exports/{token}", ctx -> listExportCtrl.get().download(ctx));

        // ---- ER 关系推导与表间关系 ----
        routes.post("/api/relation-infer", ctx -> relationCtrl.get().submitInfer(ctx));
        routes.get("/api/relation-infer-jobs/active", ctx -> relationCtrl.get().listActiveJobs(ctx));
        routes.get("/api/relation-infer-jobs", ctx -> relationCtrl.get().listJobs(ctx));
        routes.get("/api/relation-infer-jobs/{id}", ctx -> relationCtrl.get().getJob(ctx));
        routes.get("/api/relations", ctx -> relationCtrl.get().list(ctx));
        routes.post("/api/relations", ctx -> relationCtrl.get().addManual(ctx));
        routes.post("/api/relations/{id}/confirm", ctx -> relationCtrl.get().confirm(ctx));
        routes.post("/api/relations/{id}/reject", ctx -> relationCtrl.get().reject(ctx));
        routes.delete("/api/relations/{id}", ctx -> relationCtrl.get().delete(ctx));
        // 批量操作(关系批量处理对话框):返回实际影响数 updated
        routes.post("/api/relations/batch-confirm", ctx -> relationCtrl.get().batchConfirm(ctx));
        routes.post("/api/relations/batch-reject", ctx -> relationCtrl.get().batchReject(ctx));
        routes.post("/api/relations/batch-delete", ctx -> relationCtrl.get().batchDelete(ctx));
        // 关系审核数据(导出 ER 关系 3 个 sheet:最终/原始/关系变化),table 可选
        routes.get("/api/relation-audit", ctx -> relationCtrl.get().audit(ctx));
        routes.get("/api/relation-graph", ctx -> relationCtrl.get().graph(ctx));

        // ---- AI 配置 / 系统设置 / 授权 / 心跳 ----
        routes.get("/api/ai-config", ctx -> aiCtrl.get().get(ctx));
        routes.put("/api/ai-config", ctx -> aiCtrl.get().save(ctx));
        routes.post("/api/ai-config/test", ctx -> aiCtrl.get().test(ctx));
        // AI 调用 Token/费用统计
        routes.get("/api/ai-usage/stats", ctx -> aiUsageCtrl.get().stats(ctx));
        routes.get("/api/ai-usage/scan-series", ctx -> aiUsageCtrl.get().scanSeries(ctx));
        routes.get("/api/ai-usage/logs", ctx -> aiUsageCtrl.get().logs(ctx));
        routes.get("/api/system-settings/scan", ctx -> settingsCtrl.get().scanGet(ctx));
        routes.put("/api/system-settings/scan", ctx -> settingsCtrl.get().scanSave(ctx));
        routes.delete("/api/system-settings/scan", ctx -> settingsCtrl.get().scanReset(ctx));
        routes.get("/api/system-settings/browser", ctx -> settingsCtrl.get().browserGet(ctx));
        routes.put("/api/system-settings/browser", ctx -> settingsCtrl.get().browserSave(ctx));
        routes.get("/api/system-settings/jvm-memory", ctx -> settingsCtrl.get().jvmMemoryGet(ctx));
        routes.put("/api/system-settings/jvm-memory", ctx -> settingsCtrl.get().jvmMemorySave(ctx));
        routes.get("/api/system-settings/heartbeat", ctx -> settingsCtrl.get().heartbeatGet(ctx));
        routes.put("/api/system-settings/heartbeat", ctx -> settingsCtrl.get().heartbeatSave(ctx));
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

        // 静态资源清单:启动页诊断复核失败资源时比对——失败 URL 在清单内但仍拿不到 → 疑似被杀软/
        // 代理拦截;不在清单内 → 入口与后端版本错配(旧入口引用新包已删除的旧 hash 资源)。
        // 与 /api/health 同批放行就绪闸门与激活检查(启动卡住/未激活恰是最需要它的场景)
        routes.get("/api/assets-manifest", ctx -> ctx.json(Map.of("assets", staticManifest)));

        // ---- 静态资源(内存缓存,请求期零 jar I/O,原因见 preloadStatic)----
        routes.get("/", ctx -> serveStatic(ctx, "/index.html"));
        routes.get("/assets/{name}", ctx -> serveStatic(ctx, ctx.path()));

        // ---- 实时日志流(SSE) ----
        routes.sse("/api/logs/stream", logCtrl::stream);

        // ---- 系统诊断(排错中心):概览聚合 + 数据源连通实测;放行激活检查(未激活恰是最需要诊断的场景) ----
        routes.get("/api/diagnostics", ctx -> diagnosticsCtrl.get().overview(ctx));
        routes.post("/api/diagnostics/check-datasources", ctx -> diagnosticsCtrl.get().checkDatasources(ctx));

        // ---- 错误中心:前端上报 + 统一查询/标记/清理/导出;放行激活检查(与 /api/diagnostics 同待遇) ----
        // 静态段(stats/export/clear/purge/batch-status/report)必须先注册,避免被 /{id} 路径参数截获
        routes.post("/api/errors/report", ctx -> errorCtrl.get().report(ctx));
        routes.get("/api/errors/stats", ctx -> errorCtrl.get().stats(ctx));
        routes.get("/api/errors/export", ctx -> errorCtrl.get().export(ctx));
        routes.post("/api/errors/clear", ctx -> errorCtrl.get().clear(ctx));
        routes.post("/api/errors/purge", ctx -> errorCtrl.get().purge(ctx));
        routes.post("/api/errors/batch-status", ctx -> errorCtrl.get().batchStatus(ctx));
        routes.get("/api/errors", ctx -> errorCtrl.get().list(ctx));
        routes.get("/api/errors/{id}", ctx -> errorCtrl.get().detail(ctx));
        routes.put("/api/errors/{id}/status", ctx -> errorCtrl.get().updateStatus(ctx));
        routes.delete("/api/errors/{id}", ctx -> errorCtrl.get().delete(ctx));

        // ---- 更新日志:CHANGELOG.md 解析结果;放行激活检查(未激活也能看版本更新说明) ----
        routes.get("/api/changelog", ctx -> changelogCtrl.get().overview(ctx));

        // ---- 局域网共享:状态/在线实例/设置/预览/拉取为本机页面用;share/* 为实例间拉取出口(放行激活检查) ----
        // share 静态段(preview)须先于同前缀注册;pull/preview 的 {instanceId} 与 share 不同前缀,无截获问题
        routes.get("/api/lan/status", ctx -> lanCtrl.get().status(ctx));
        routes.get("/api/lan/peers", ctx -> lanCtrl.get().peers(ctx));
        routes.post("/api/lan/peers/manual", ctx -> lanCtrl.get().addManualPeer(ctx));
        routes.delete("/api/lan/peers/manual", ctx -> lanCtrl.get().removeManualPeer(ctx));
        routes.put("/api/lan/settings", ctx -> lanCtrl.get().saveSettings(ctx));
        routes.get("/api/lan/preview/{instanceId}", ctx -> lanCtrl.get().previewPeer(ctx));
        routes.post("/api/lan/pull/{instanceId}", ctx -> lanCtrl.get().pull(ctx));
        routes.get("/api/lan/share/info", ctx -> lanCtrl.get().shareInfo(ctx));
        routes.get("/api/lan/share/annotations/preview", ctx -> lanCtrl.get().shareAnnotationsPreview(ctx));
        routes.get("/api/lan/share/annotations", ctx -> lanCtrl.get().shareAnnotations(ctx));
        routes.get("/api/lan/share/scans/preview", ctx -> lanCtrl.get().shareScansPreview(ctx));
        routes.get("/api/lan/share/scans", ctx -> lanCtrl.get().shareScans(ctx));
        routes.get("/api/lan/share/datasources", ctx -> lanCtrl.get().shareDatasources(ctx));

        // SPA 回退(替代 SpaWebConfig):静态资源未命中且非 /api/** 的 GET 一律回退 index.html 交给前端路由;
        // 但路径末段带扩展名(如 /assets/xxx.js)说明是静态文件缺失,必须真实 404——
        // 回退成 text/html 会让浏览器把 404 的 js 当 HTML 解析,报 MIME 错误白屏且掩盖真实问题;
        // /api/** 未匹配保持 JSON 404
        routes.error(404, ctx -> {
            String path = ctx.path();
            boolean api = path.startsWith("/api/") || path.equals("/api");
            boolean looksLikeFile = path.substring(path.lastIndexOf('/') + 1).contains(".");
            if (!api && !looksLikeFile && "GET".equals(ctx.method().name())) {
                byte[] index = staticCache.get("/index.html");
                if (index != null) {
                    ctx.status(200).contentType("text/html;charset=utf-8")
                            .header("Cache-Control", "no-store").result(index);
                    return;
                }
            }
            ctx.json(Map.of("message", "路径不存在: " + path));
        });
    }

    /**
     * 静态资源一次性预读进内存(web/dist 全量仅几 MB):请求期不再碰 jar 文件。
     * 不走 Javalin CLASSPATH 静态实现的原因:其 ClasspathResource 每个请求都 openConnection
     * 且 setUseCaches(false)——浏览器每请求一个 js/css 都要重新打开 75MB fat jar 并重析中央目录,
     * 异常被静默吞掉按 404 处理;Windows 杀软对新装未签名 jar 的每次打开都做实时扫描,
     * 首次安装/重启后并发资产请求易被卡住或拒绝,表现为「入口 200(走 SPA 回退)、assets 404」
     * (2026-09 多用户实测反推,反编译 ClasspathResource 字节码证实)。
     * 枚举:配置了 dq.web.static-dir 且目录存在时 Files.walk 该磁盘目录(jpackage/安装版);
     * 否则打包运行扫 fat jar 的 static/ 条目、dev/测试遍历 classpath 资源根;失败按空放行
     * (dev 不构建前端时本就没有静态资源,仅影响启动诊断判定精度)。
     * 加固(2026-09):杀软可能连启动期这一趟读取也拦截——失败项最多再重试 2 轮(间隔 300ms),
     * 仍失败的资源整个会话期 404,故启动日志固定输出「静态资源 N/M」汇总,缺失时逐个列出,
     * 便于现场对照 /api/assets-manifest 排查。
     */
    private static Map<String, byte[]> preloadStatic(String staticDir) {
        Path dir = staticDir == null || staticDir.isBlank() ? null : Path.of(staticDir);
        boolean fromDisk = dir != null && Files.isDirectory(dir);
        if (dir != null && !fromDisk) {
            log.warn("配置的静态目录不存在,回落 classpath 静态: {}", staticDir);
        }
        String source = fromDisk ? "dir=" + staticDir : "classpath";
        List<String> paths = fromDisk ? enumerateStaticDirPaths(dir) : enumerateStaticPaths();
        Map<String, byte[]> cache = new java.util.LinkedHashMap<>();
        List<String> pending = new ArrayList<>(paths);
        for (int round = 0; round < 3 && !pending.isEmpty(); round++) {
            if (round > 0) {
                log.warn("静态资源预读第 {} 轮仍有 {} 个失败,300ms 后重试", round, pending.size());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            List<String> failed = new ArrayList<>();
            for (String path : pending) {
                // 磁盘:直接从配置目录读;classpath:走类加载器(getResourceAsStream,
                // useCaches=true 共享已打开的 JarFile),启动期单趟读取
                try (InputStream in = fromDisk
                        ? Files.newInputStream(dir.resolve(path.substring(1)))
                        : WebServer.class.getResourceAsStream("/static" + path)) {
                    if (in != null) {
                        cache.put(path, in.readAllBytes());
                    } else {
                        failed.add(path);
                    }
                } catch (Exception e) {
                    log.warn("静态资源预读失败 {}: {}", path, e.toString());
                    failed.add(path);
                }
            }
            pending = failed;
        }
        if (pending.isEmpty()) {
            StartupLog.log("  静态资源 " + cache.size() + "/" + paths.size() + " 加载完成(内存缓存, source=" + source + ")");
        } else {
            StartupLog.log("  静态资源 " + cache.size() + "/" + paths.size() + " 加载,缺失 "
                    + pending.size() + " 个(会话期将 404, source=" + source + "): " + pending);
            log.error("静态资源预读最终失败 {}/{} 个,这些资源会话期将 404: {}", pending.size(), paths.size(), pending);
        }
        return cache;
    }

    /**
     * 枚举磁盘静态目录下的资源路径(与 classpath 分支同口径:/index.html、/assets/xxx.js),供预读与清单出口共用
     */
    private static List<String> enumerateStaticDirPaths(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> "/" + root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("磁盘静态目录枚举失败(按空清单放行): {}", e.toString());
            return List.of();
        }
    }

    /**
     * 枚举本构建内嵌静态资源路径(返回 /index.html、/assets/xxx.js 等),供预读与清单出口共用
     */
    private static List<String> enumerateStaticPaths() {
        try {
            URL location = WebServer.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            if (path.toString().endsWith(".jar")) {
                List<String> assets = new ArrayList<>();
                try (JarFile jar = new JarFile(path.toFile())) {
                    java.util.Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith("static/") && !name.endsWith("/")) {
                            assets.add("/" + name.substring("static/".length()));
                        }
                    }
                }
                java.util.Collections.sort(assets);
                return assets;
            }
            // dev / 测试:classes 目录,遍历 classpath 资源根下的 static
            URL staticRoot = WebServer.class.getResource("/static");
            if (staticRoot != null && "file".equals(staticRoot.getProtocol())) {
                Path root = Path.of(staticRoot.toURI());
                try (Stream<Path> walk = Files.walk(root)) {
                    return walk.filter(Files::isRegularFile)
                            .map(p -> "/" + root.relativize(p).toString().replace('\\', '/'))
                            .sorted()
                            .toList();
                }
            }
        } catch (Exception e) {
            log.warn("静态资源清单构建失败(按空清单放行,仅影响启动诊断判定): {}", e.toString());
        }
        return List.of();
    }

    /**
     * 从内存缓存服务静态资源:指纹资源长缓存 immutable,入口等其余 no-store
     */
    private void serveStatic(io.javalin.http.Context ctx, String path) {
        byte[] content = staticCache.get(path);
        if (content == null) {
            throw new io.javalin.http.NotFoundResponse();
        }
        if (path.startsWith("/assets/")) {
            // 指纹资源(文件名带内容 hash,内容变则文件名变):长缓存 immutable
            ctx.header("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            // 入口 index.html 等:禁止任何缓存复用(no-cache 在会话恢复/重校验失败时仍可能给旧副本)
            ctx.header("Cache-Control", "no-store");
        }
        ctx.contentType(staticContentType(path)).result(content);
    }

    private static String staticContentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            // ---------- 文本 ----------
            case "html", "htm" -> "text/html;charset=utf-8";
            case "css" -> "text/css;charset=utf-8";
            case "js", "mjs" -> "text/javascript;charset=utf-8";
            case "cjs" -> "application/node";
            case "txt", "text", "log" -> "text/plain;charset=utf-8";
            case "csv" -> "text/csv;charset=utf-8";
            case "xml" -> "text/xml;charset=utf-8";
            case "md", "markdown" -> "text/markdown;charset=utf-8";
            case "vtt" -> "text/vtt;charset=utf-8";

            // ---------- 数据 / 配置 ----------
            case "json" -> "application/json;charset=utf-8";
            case "map" -> "application/json;charset=utf-8";
            case "jsonld" -> "application/ld+json;charset=utf-8";
            case "webmanifest" -> "application/manifest+json;charset=utf-8";
            case "yaml", "yml" -> "application/yaml;charset=utf-8";
            case "pdf" -> "application/pdf";
            case "wasm" -> "application/wasm";
            case "rtf" -> "application/rtf";
            case "zip" -> "application/zip";
            case "gz" -> "application/gzip";
            case "tar" -> "application/x-tar";
            case "7z" -> "application/x-7z-compressed";
            case "rar" -> "application/vnd.rar";
            case "bin", "exe", "dll" -> "application/octet-stream";

            // ---------- 图片 ----------
            case "png" -> "image/png";
            case "jpg", "jpeg", "jpe" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "apng" -> "image/apng";

            // ---------- 音视频 ----------
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "m4a" -> "audio/mp4";
            case "weba" -> "audio/webm";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "ogv" -> "video/ogg";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "m3u8" -> "application/vnd.apple.mpegurl";
            case "ts" -> "video/mp2t";

            // ---------- 字体 ----------
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "eot" -> "application/vnd.ms-fontobject";
            case "sfnt" -> "font/sfnt";

            default -> "application/octet-stream";
        };
    }

    public void start(int port) {
        app.start(port);
    }

    /**
     * 停止 Web 服务并关闭内核连接池(不走 System.exit,与 AppShutdown 的进程退出路径区分;测试与嵌入式使用)
     */
    public void stop() {
        app.stop();
        if (env != null) {
            env.shutdown();
        }
    }

    /**
     * 实际监听端口(server.port=0 时为容器随机分配的结果)
     */
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
        syncBrowserSetting();
        startLanShare();
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
        syncBrowserSetting();
        startLanShare();
    }

    /**
     * 内核就绪后启动局域网共享(UDP 实例发现):需要实际监听的 HTTP 端口随心跳广播,
     * 开关(dq.lan.enabled / 页面设置)在 LanShareService 内判定;失败不阻断启动。
     */
    private void startLanShare() {
        try {
            if (env != null) {
                env.getLanShareService().start(app.port());
            }
        } catch (Exception e) {
            log.warn("局域网发现启动失败(不影响主服务): {}", e.getMessage());
        }
    }

    /**
     * 以 DB 为准回填浏览器选择:启动早期开窗时 H2 尚未就绪,BrowserOpener 只能用镜像文件里的值;
     * 内核就绪后同步一次(DB 为唯一事实来源),并顺带刷新镜像文件。失败不阻断启动。
     */
    private void syncBrowserSetting() {
        try {
            if (env != null) {
                browserOpener.setConfiguredBrowser(env.getSystemSettingsService().browserApp());
            }
        } catch (Exception e) {
            log.warn("回填浏览器选择失败,沿用启动早期镜像值: {}", e.getMessage());
        }
    }

    /**
     * 注入内核服务对象图到路由引用骨架,并把内核挂到本实例(stop/AppShutdown 用)
     */
    private void injectKernel(ServiceEnv env) {
        this.env = env;
        licenseServiceRef.set(env.getLicenseService());
        dataSourceCtrl.set(new DataSourceController(env.getDataSourceService(), env.getDataSourceTransferService(),
                env.getMetadataTransferService(), env.getExportCenterService()));
        scanCtrl.set(new ScanController(env.getScanService(), env.getExportService(), env.getScanWordExportService(),
                env.getExportCenterService()));
        scanTransferCtrl.set(new ScanTransferController(env.getScanTransferService(), env.getExportCenterService()));
        metaCtrl.set(new MetadataController(env.getMetadataService(), env.getTableDocService(),
                env.getTableSystemService(), env.getDbStructExportService(), env.getDataSourceService(),
                env.getExportCenterService()));
        metaSyncCtrl.set(new MetadataSyncController(env.getMetaSyncService()));
        reportCtrl.set(new ReportExportController(env.getWordReportExportService()));
        sampleExportCtrl.set(new SampleExportController(env.getSampleExportService(), env.getExportCenterService()));
        compareCtrl.set(new CompareController(env.getCompareService(), env.getExportCenterService()));
        compareImportCtrl.set(new CompareImportController(env.getCompareImportService(), env.getExportCenterService()));
        tagCtrl.set(new TagController(env.getTagService()));
        manualCollectCtrl.set(new ManualCollectController(env.getManualCollectService()));
        objectCatalogCtrl.set(new ObjectCatalogController(env.getObjectCatalogService()));
        aiCtrl.set(new AiConfigController(env.getAiConfigService()));
        aiUsageCtrl.set(new AiUsageController(env.getAiUsageService()));
        settingsCtrl.set(new SystemSettingsController(env.getSystemSettingsService(), browserOpener,
                env.getConfig().getDataDir()));
        licenseCtrl.set(new LicenseController(env.getLicenseService()));
        previewCtrl.set(new PreviewController(env.getPreviewService(), env.getExportCenterService()));
        sqlConsoleCtrl.set(new SqlConsoleController(env.getSqlConsoleService(), env.getLocalH2ConsoleService()));
        annotationCtrl.set(new AnnotationController(env.getAnnotationTransferService(), env.getExportCenterService()));
        listExportCtrl.set(new ListExportController(env.getListExportService(), env.getExportCenterService()));
        diagnosticsCtrl.set(new DiagnosticsController(env.getDiagnosticsService(), logStreamAppender));
        changelogCtrl.set(new ChangelogController(env.getChangelogService()));
        lanCtrl.set(new LanController(env.getLanShareService()));
        relationCtrl.set(new RelationController(env.getRelationInferService(), env.getTableRelationService()));
        errorCtrl.set(new ErrorCenterController(env.getErrorCenterService(), env.getExportCenterService()));
        exportCenterCtrl.set(new ExportCenterController(env.getExportCenterService()));
        systemCtrl.set(new SystemController(env.getConfig(), this::port, config.dq().getAccessTokens()));
        // 错误中心出口注入:采集 Appender 回灌启动期缓冲,静态出口供未捕获异常处理器等使用
        errorCaptureAppender.setSink(env.getErrorCenterService()::record);
        ErrorCenterHolder.bind(env.getErrorCenterService());
    }

    /**
     * 服务就绪后回填托盘菜单引用(原 onReady 的托盘部分),桌面安装版由 main 在 finishInit 后调用
     */
    public void markTrayReady() {
        trayManager.onReady(app.port());
    }

    /**
     * 启动失败时关闭本进程拉起的 --app 窗口,避免残留孤儿浏览器窗口
     */
    public void closeBrowserWindow() {
        browserOpener.closeWindow();
    }
}
