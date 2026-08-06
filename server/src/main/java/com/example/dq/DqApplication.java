package com.example.dq;

import com.example.dq.config.DesktopSplash;
import com.example.dq.config.TrayManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.ServerSocket;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DqApplication {

    private static final int DEFAULT_PORT = 10000;
    private static final int MAX_PORT_OFFSET = 100;

    public static void main(String[] args) {
        int configuredPort = resolveConfiguredPort(args);
        // server.port=0 表示交给容器随机分配,无需避让
        if (configuredPort != 0) {
            int port = findAvailablePort(configuredPort);
            if (port != configuredPort) {
                System.out.printf("[dq-tool] 端口 %d 被占用,避让到 %d%n", configuredPort, port);
            }
            // 通过系统属性覆盖 application.yml 中的 server.port(命令行参数优先级更高,不受影响)
            System.setProperty("server.port", String.valueOf(port));
            earlyDesktopFeedback(port);
        }
        SpringApplication.run(DqApplication.class, args);
    }

    /**
     * 桌面安装版(打包脚本注入 -Djava.awt.headless=false)在 Spring 启动前先给出视觉反馈:
     * 第一时间安装系统托盘图标并弹出启动画面,服务就绪后由 BrowserOpener 关启动画面并打开窗口。
     * 显式判断 headless=false 而不是 !isHeadless():普通 java -jar 在桌面机器上运行时该属性未设置
     * (由 Spring 置为 headless),不能误装托盘/弹窗,保持服务器部署的原行为。
     */
    private static void earlyDesktopFeedback(int port) {
        if (!"false".equalsIgnoreCase(System.getProperty("java.awt.headless"))) {
            return;
        }
        TrayManager.installEarly("http://localhost:" + port);
        DesktopSplash.showEarly();
    }

    /**
     * 解析用户显式配置的端口,优先级: --server.port 启动参数 > SERVER_PORT 环境变量 > 默认 10000。
     */
    private static int resolveConfiguredPort(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--server.port=")) {
                return parsePort(arg.substring("--server.port=".length()), DEFAULT_PORT);
            }
        }
        String env = System.getenv("SERVER_PORT");
        if (env != null && !env.isBlank()) {
            return parsePort(env.trim(), DEFAULT_PORT);
        }
        return DEFAULT_PORT;
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
