package com.example.dq.config;

import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 进程退出封装(替代 SpringApplication.exit):停 Web 服务 → 关连接池 → System.exit(0)。
 * 托盘「退出」(TrayManager)与心跳看门狗(DesktopSession)共用;
 * 调用方负责在新线程里调 exit()(关闭过程会阻塞当前线程直到 Jetty/Hikari 停完)。
 */
public class AppShutdown {

    private static final Logger log = LoggerFactory.getLogger(AppShutdown.class);

    private final Javalin app;
    /** 连接池提供者:共享内核懒构建(WebServer 开窗后再建),构造时可能尚未就绪,退出时按需取 */
    private final Supplier<HikariDataSource> dataSource;

    public AppShutdown(Javalin app, Supplier<HikariDataSource> dataSource) {
        this.app = app;
        this.dataSource = dataSource;
    }

    public void exit() {
        try {
            app.stop();
        } catch (Exception e) {
            log.warn("停止 Web 服务失败: {}", e.getMessage());
        }
        HikariDataSource ds = dataSource.get();
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("关闭数据源连接池失败: {}", e.getMessage());
            }
        }
        System.exit(0);
    }
}
