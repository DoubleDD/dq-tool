package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 桌面安装版(--app 应用模式窗口)的生命周期看门狗。
 * 问题背景:安装包隐藏终端,用户关闭浏览器 --app 窗口后没有地方能结束后端进程,残留孤儿进程。
 * 方案:前端每 5 秒上报一次心跳(/api/heartbeat),本进程拉起的 --app 窗口存在时武装看门狗,
 * 超过 dq.desktop.shutdown-timeout-seconds 未收到心跳即判定窗口已关闭,优雅退出进程。
 * 系统托盘(TrayManager)可用时以后台守护进程方式运行,托盘提供「打开窗口/退出」入口,
 * 看门狗停用;本类仅作为托盘不可用环境的兜底。
 * java -jar 服务器部署、页面开在普通浏览器标签页(未由本进程拉起 app 窗口)等场景不受影响。
 * 已知边界:机器休眠超过超时时长会被误判为窗口关闭;进行中的扫描随进程退出中断,重开后可断点续扫。
 */
public class DesktopSession {

    private static final Logger log = LoggerFactory.getLogger(DesktopSession.class);

    private final DqProperties props;
    private final AppShutdown shutdown;
    /** 本进程是否成功拉起了 --app 应用模式窗口(只有这种情况才需要看门狗) */
    private volatile boolean appModeOpened;
    /** 托盘图标(TrayManager)生效时后端以守护进程方式常驻,看门狗停用 */
    private volatile boolean trayActive;
    /** 最近一次页面心跳时间;0 表示还没收到过心跳,看门狗尚未武装 */
    private volatile long lastBeatMillis;

    public DesktopSession(DqProperties props, AppShutdown shutdown) {
        this.props = props;
        this.shutdown = shutdown;
    }

    /** 启动看门狗定时检查(等价原 @Scheduled(fixedDelay = 5000)) */
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "desktop-watchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::watchdog, 0, 5, TimeUnit.SECONDS);
    }

    public void markAppModeOpened() {
        this.appModeOpened = true;
    }

    public void markTrayActive() {
        this.trayActive = true;
    }

    public void beat() {
        this.lastBeatMillis = System.currentTimeMillis();
    }

    void watchdog() {
        int timeoutSeconds = props.getDesktop().getShutdownTimeoutSeconds();
        if (trayActive || !appModeOpened || timeoutSeconds <= 0 || lastBeatMillis == 0) {
            return;
        }
        long idleMillis = System.currentTimeMillis() - lastBeatMillis;
        if (idleMillis > timeoutSeconds * 1000L) {
            log.info("超过 {} 秒未收到页面心跳,判定应用窗口已关闭,退出进程", timeoutSeconds);
            // 必须换线程退出:当前方法跑在看门狗调度线程上,原地执行关闭(停 Javalin、关连接池)
            // 会长时间阻塞调度线程;延续原"换线程退出"的做法
            Thread exitThread = new Thread(shutdown::exit, "desktop-exit");
            exitThread.setDaemon(false);
            exitThread.start();
        }
    }
}
