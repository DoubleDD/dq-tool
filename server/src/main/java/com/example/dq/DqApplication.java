package com.example.dq;

import com.example.dq.config.BrowserOpener;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DesktopSplash;
import com.example.dq.config.InstanceLock;
import com.example.dq.config.KernelConfigAdapter;
import com.example.dq.config.LegacyTlsSupport;
import com.example.dq.config.StartupLog;
import com.example.dq.config.StartupStage;
import com.example.dq.config.TrayManager;
import com.example.dq.env.ServiceEnv;
import com.example.dq.web.WebServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;

public class DqApplication {

    private static final int MAX_PORT_OFFSET = 100;

    public static void main(String[] args) throws Exception {
        // 启动早期日志必须最先初始化:Windows 安装版无控制台,双击后的每一步都要落文件(数据目录同级 logs/startup.log)
        StartupLog.init();
        // 非 main 线程的未捕获异常(AWT 事件线程、看门狗线程等)默认只打 stderr,安装版看不到,统一落 startup.log
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                StartupLog.log("线程 " + thread.getName() + " 未捕获异常", e));
        // 与原 Spring Boot 行为一致:默认 headless,只有显式 -Djava.awt.headless=false(打包脚本/make 注入)
        // 才启用窗口/托盘;必须在任何 AWT 类加载前设置
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        // 兼容仅支持 TLS 1.0/1.1 的老版本 SQL Server;必须在任何 TLS 使用之前调用(详见 LegacyTlsSupport)
        LegacyTlsSupport.enable();
        // 桌面安装版(headless=false)先给出视觉反馈:启动画面第一时间弹出(安装包走 JVM 原生 -splash,
        // 点击图标即显示;开发模式回退 Swing 启动画面),托盘改后台安装,不再阻塞主流程。
        // 显式判断 headless=false 而不是 !isHeadless():普通 java -jar 在桌面机器上运行时该属性未设置,
        // 不能误装托盘/弹窗,保持服务器部署的原行为。
        boolean desktop = "false".equalsIgnoreCase(System.getProperty("java.awt.headless"));
        if (desktop) {
            StartupLog.log("显示启动画面...");
            DesktopSplash.showEarly();
            StartupLog.log("启动画面已显示");
        }
        StartupLog.mark("早期初始化" + (desktop ? "(含启动画面)" : ""));
        // try 外声明:启动失败(如共享内核初始化异常)时 catch 里要关掉已拉起的应用窗口
        WebServer server = null;
        try {
            StartupLog.log("加载配置(application.yml)...");
            ConfigLoader.AppConfig config = ConfigLoader.load();
            // logback 首次打日志即初始化:dq.data-dir / dq.log-dir 必须先于任何日志输出设置(logback.xml 引用)
            System.setProperty("dq.data-dir", config.dataDir());
            // 日志目录固定为数据目录同级的 logs/(规则单点 StartupLog.logDirFor),-Ddq.log-dir 可显式覆盖
            if (System.getProperty("dq.log-dir") == null) {
                System.setProperty("dq.log-dir", StartupLog.logDirFor(config.dataDir()).toString());
            }
            StartupLog.adoptDataDir(config.dataDir());
            StartupLog.log("配置加载完成: dataDir=" + config.dataDir() + ", serverPort=" + config.serverPort()
                    + ", headless=" + System.getProperty("java.awt.headless"));
            StartupLog.mark("加载配置");

            int configuredPort = resolveConfiguredPort(args, config.serverPort());
            int port = configuredPort;
            // 单实例保护:同数据目录已有实例在跑时,经 H2 AUTO_SERVER 连上旧实例内嵌的 H2 server,
            // 跨版本类不兼容直接启动失败(2026-08 实测);第二个实例改为带出已有实例窗口并退出
            if (InstanceLock.acquire(java.nio.file.Path.of(config.dataDir()))
                    == InstanceLock.Status.ALREADY_RUNNING) {
                StartupLog.log("检测到同数据目录已有 dq-tool 实例在运行(数据目录 " + config.dataDir() + ")");
                if (desktop) {
                    int runningPort = configuredPort == 0 ? -1 : InstanceLock.findRunningInstancePort(configuredPort);
                    String url = "http://localhost:" + (runningPort > 0 ? runningPort : configuredPort);
                    StartupLog.log("打开已有实例窗口 " + url + " ,本进程退出");
                    BrowserOpener.reopenExisting(url);
                    System.exit(0);
                } else {
                    StartupLog.log("headless 模式不打开窗口,本进程退出(如需多实例请使用不同数据目录与端口)");
                    System.err.println("[dq-tool] 同数据目录已有实例在运行,本进程退出: " + config.dataDir());
                    System.exit(1);
                }
            }
            // server.port=0 表示交给容器随机分配,无需避让
            if (configuredPort != 0) {
                port = findAvailablePort(configuredPort);
                if (port != configuredPort) {
                    StartupLog.log("端口 " + configuredPort + " 被占用,避让到 " + port);
                }
            }

            // 先在主线程完成 SLF4J/logback 初始化:内核后台线程(Hikari 打日志)与 WebServer 装配
            // 会并发触发初始化,先到的线程可能拿到初始化进行中的 SubstituteLoggerFactory,
            // WebServer 里强转 LoggerContext 挂 LogStreamAppender 会 ClassCastException(实测)
            org.slf4j.LoggerFactory.getLogger(DqApplication.class);
            StartupLog.mark("单实例锁/端口探测/日志系统初始化");
            // 启动优化:共享内核重活(H2 池 + 业务服务对象图 + 建表/迁移/恢复)与 Web 装配、
            // 端口绑定、开窗完全并行——内核不依赖 Javalin(路由走引用骨架,就绪闸门挡住未注入请求),
            // 此处后台线程先跑,finishInit 只等结果;内核异常经 future 在 finishInit 原样抛出,
            // 走下方统一启动失败路径。必须晚于 dq.data-dir 系统属性设置与单实例锁获取
            // (内核日志走 logback、H2 单进程打开)。
            StartupLog.log("后台启动共享内核构建(H2 池 + 建表/迁移/中断恢复,与 Web 装配并行)...");
            CompletableFuture<ServiceEnv> kernelFuture = new CompletableFuture<>();
            long kernelStartNanos = System.nanoTime();
            Thread kernelThread = new Thread(() -> {
                try {
                    ServiceEnv env = new ServiceEnv(KernelConfigAdapter.toKernelConfig(config));
                    env.initDatabase();
                    // 内核与 main 线程并行,耗时不进 main 的阶段打点,单独成行(耗时统计汇总的补充)
                    StartupLog.log("共享内核构建完成,并行耗时 "
                            + (System.nanoTime() - kernelStartNanos) / 1_000_000 + "ms");
                    kernelFuture.complete(env);
                } catch (Throwable t) {
                    kernelFuture.completeExceptionally(t);
                }
            }, "kernel-init");
            kernelThread.setDaemon(true);
            kernelThread.start();

            // WebServer 只装配轻量外壳(静态资源 + 就绪探针 + 路由引用骨架),秒级完成
            StartupLog.log("装配 WebServer(轻量外壳:静态资源 + 就绪探针 + 路由引用骨架)...");
            server = new WebServer(config);
            StartupLog.mark("装配 WebServer");
            StartupLog.log("WebServer 装配完成,启动 HTTP 监听 port=" + port + " ...");
            StartupStage.set(StartupStage.HTTP);
            server.start(port);
            StartupLog.mark("HTTP 监听");
            // 先绑定再开窗:首页(静态外壳)秒出,前端轮询 /api/health 等待后端就绪(占位页同步展示
            // 实时启动阶段),不再等共享内核初始化完成(避免服务初始化期间的白屏)
            StartupLog.log("HTTP 已监听,立即打开应用窗口(页面外壳秒出,后端就绪由前端轮询等待)...");
            server.openBrowser();
            // 首次 AWT 初始化此刻在主线程完成(原生 splash 全程罩着),保证后续 AWT 调用
            // (托盘图标等)不落到非主线程做首次初始化(macOS 要求 AWT 在主线程初始化);
            // 托盘继续后台安装,不阻塞主流程(installEarlyAsync 进行中时 markTrayReady 不等待)
            if (desktop) {
                DesktopSplash.ensureAwtInitialized();
                StartupLog.log("后台安装系统托盘图标...");
                TrayManager.installEarlyAsync("http://localhost:" + port);
            }
            StartupLog.mark("打开应用窗口");
            // 共享内核与 Web 装配/开窗并行构建(上面 kernel-init 线程),此处只等结果;
            // 完成后 /api/health 转 200。内核异常经 future 原样抛出,走统一启动失败路径
            StartupLog.log("等待共享内核初始化(与 Web 装配并行,通常已完成)...");
            StartupStage.set(StartupStage.KERNEL);
            server.finishInit(kernelFuture);
            StartupLog.mark("等待共享内核");
            // 服务就绪后关闭启动画面:此时 Chrome 已显示占位页,不留"关窗→浏览器冷启动"的死区
            StartupStage.set(StartupStage.READY);
            DesktopSplash.close();
            // 回填托盘菜单引用(原 onReady 的托盘部分;headless 下 installEarly 自动跳过)
            server.markTrayReady();
            StartupLog.log("启动流程全部完成,实际端口=" + server.port());
            StartupLog.mark("就绪收尾");
            StartupLog.logTiming();
        } catch (Throwable t) {
            // 安装版无控制台,未捕获异常必须落文件;同时保留 stderr 输出(开发/服务器部署排障)
            StartupLog.log("启动失败,进程即将退出(startup.log=" + StartupLog.file() + ")", t);
            // 失败同样输出耗时统计:排障时能直接看到卡在哪个阶段之后
            StartupLog.logTiming();
            t.printStackTrace();
            // 启动早期已开窗时,关掉本进程拉起的 --app 窗口,避免进程退出后残留孤儿窗口
            if (server != null) {
                try {
                    server.closeBrowserWindow();
                } catch (Exception ignore) {
                    // 关窗失败不影响退出
                }
            }
            DesktopSplash.close();
            System.exit(1);
        }
    }

    /**
     * 解析用户显式配置的端口,优先级: --server.port 启动参数 > SERVER_PORT 环境变量 > application.yml。
     */
    private static int resolveConfiguredPort(String[] args, int defaultPort) {
        for (String arg : args) {
            if (arg.startsWith("--server.port=")) {
                return parsePort(arg.substring("--server.port=".length()), defaultPort);
            }
        }
        String env = System.getenv("SERVER_PORT");
        if (env != null && !env.isBlank()) {
            return parsePort(env.trim(), defaultPort);
        }
        return defaultPort;
    }

    private static int parsePort(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 从期望端口开始向后探测,返回第一个可用端口。
     */
    private static int findAvailablePort(int preferred) {
        for (int port = preferred; port <= preferred + MAX_PORT_OFFSET; port++) {
            if (isPortFree(port)) {
                return port;
            }
        }
        throw new IllegalStateException(
                "端口 " + preferred + " ~ " + (preferred + MAX_PORT_OFFSET) + " 均被占用,请通过 --server.port= 指定其他端口");
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
