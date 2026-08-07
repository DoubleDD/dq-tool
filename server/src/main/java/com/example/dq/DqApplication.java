package com.example.dq;

import com.example.dq.config.BrowserOpener;
import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DesktopSplash;
import com.example.dq.config.InstanceLock;
import com.example.dq.config.LegacyTlsSupport;
import com.example.dq.config.StartupLog;
import com.example.dq.config.TrayManager;
import com.example.dq.web.WebServer;

import java.io.IOException;
import java.net.ServerSocket;

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

            int configuredPort = resolveConfiguredPort(args, config.serverPort());
            int port = configuredPort;
            // 单实例保护:同数据目录已有实例在跑时,经 H2 AUTO_SERVER 连上旧实例内嵌的 H2 server,
            // 跨版本类不兼容直接启动失败(2026-08 实测);第二个实例改为带出已有实例窗口并退出
            if (InstanceLock.acquire(java.nio.file.Path.of(config.dataDir()))
                    == InstanceLock.Status.ALREADY_RUNNING) {
                StartupLog.log("检测到同数据目录已有 dq-tool 实例在运行(数据目录 " + config.dataDir() + ")");
                if ("false".equalsIgnoreCase(System.getProperty("java.awt.headless"))) {
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
                earlyDesktopFeedback(port);
            }

            // WebServer 构造时完成共享内核装配(H2 连接池 + Flyway 迁移 + 业务服务)
            StartupLog.log("装配 WebServer(H2 连接池 + Flyway 迁移 + 业务服务)...");
            WebServer server = new WebServer(config);
            StartupLog.log("WebServer 装配完成,启动 HTTP 监听 port=" + port + " ...");
            server.start(port);
            StartupLog.log("HTTP 服务已就绪,执行就绪动作(关启动画面 + 打开窗口)...");
            // 服务完全就绪:关启动画面 + 打开应用窗口、回填托盘引用(port=0 时以实际分配端口为准)
            server.onReady(server.port());
            StartupLog.log("启动流程全部完成,实际端口=" + server.port());
        } catch (Throwable t) {
            // 安装版无控制台,未捕获异常必须落文件;同时保留 stderr 输出(开发/服务器部署排障)
            StartupLog.log("启动失败,进程即将退出(startup.log=" + StartupLog.file() + ")", t);
            t.printStackTrace();
            DesktopSplash.close();
            System.exit(1);
        }
    }

    /**
     * 桌面安装版(打包脚本注入 -Djava.awt.headless=false)在服务启动前先给出视觉反馈:
     * 第一时间安装系统托盘图标并弹出启动画面,服务就绪后由 BrowserOpener 关启动画面并打开窗口。
     * 显式判断 headless=false 而不是 !isHeadless():普通 java -jar 在桌面机器上运行时该属性未设置,
     * 不能误装托盘/弹窗,保持服务器部署的原行为。
     */
    private static void earlyDesktopFeedback(int port) {
        if (!"false".equalsIgnoreCase(System.getProperty("java.awt.headless"))) {
            StartupLog.log("headless 模式,跳过托盘与启动画面");
            return;
        }
        StartupLog.log("安装系统托盘图标...");
        boolean trayInstalled = TrayManager.installEarly("http://localhost:" + port);
        StartupLog.log("托盘图标安装" + (trayInstalled ? "成功" : "失败/不可用") + ",显示启动画面...");
        DesktopSplash.showEarly();
        StartupLog.log("启动画面已显示");
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
