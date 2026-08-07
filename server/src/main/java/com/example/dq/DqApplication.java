package com.example.dq;

import com.example.dq.config.ConfigLoader;
import com.example.dq.config.DesktopSplash;
import com.example.dq.config.TrayManager;
import com.example.dq.web.WebServer;

import java.io.IOException;
import java.net.ServerSocket;

public class DqApplication {

    private static final int MAX_PORT_OFFSET = 100;

    public static void main(String[] args) throws Exception {
        // 与原 Spring Boot 行为一致:默认 headless,只有显式 -Djava.awt.headless=false(打包脚本/make 注入)
        // 才启用窗口/托盘;必须在任何 AWT 类加载前设置
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        ConfigLoader.AppConfig config = ConfigLoader.load();
        // logback 首次打日志即初始化:dq.data-dir 必须先于任何日志输出设置(logback.xml 引用该变量)
        System.setProperty("dq.data-dir", config.dataDir());

        int configuredPort = resolveConfiguredPort(args, config.serverPort());
        int port = configuredPort;
        // server.port=0 表示交给容器随机分配,无需避让
        if (configuredPort != 0) {
            port = findAvailablePort(configuredPort);
            if (port != configuredPort) {
                System.out.printf("[dq-tool] 端口 %d 被占用,避让到 %d%n", configuredPort, port);
            }
            earlyDesktopFeedback(port);
        }

        // WebServer 构造时完成共享内核装配(H2 连接池 + Flyway 迁移 + 业务服务)
        WebServer server = new WebServer(config);
        server.start(port);
        // 服务完全就绪:关启动画面 + 打开应用窗口、回填托盘引用(port=0 时以实际分配端口为准)
        server.onReady(server.port());
    }

    /**
     * 桌面安装版(打包脚本注入 -Djava.awt.headless=false)在服务启动前先给出视觉反馈:
     * 第一时间安装系统托盘图标并弹出启动画面,服务就绪后由 BrowserOpener 关启动画面并打开窗口。
     * 显式判断 headless=false 而不是 !isHeadless():普通 java -jar 在桌面机器上运行时该属性未设置,
     * 不能误装托盘/弹窗,保持服务器部署的原行为。
     */
    private static void earlyDesktopFeedback(int port) {
        if (!"false".equalsIgnoreCase(System.getProperty("java.awt.headless"))) {
            return;
        }
        TrayManager.installEarly("http://localhost:" + port);
        DesktopSplash.showEarly();
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
