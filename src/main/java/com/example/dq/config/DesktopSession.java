package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 桌面安装版(--app 应用模式窗口)的生命周期看门狗。
 * 问题背景:安装包隐藏终端,用户关闭浏览器 --app 窗口后没有地方能结束后端进程,残留孤儿进程。
 * 方案:前端每 5 秒上报一次心跳(/api/heartbeat),本进程拉起的 --app 窗口存在时武装看门狗,
 * 超过 dq.desktop.shutdown-timeout-seconds 未收到心跳即判定窗口已关闭,优雅退出进程。
 * 只在 BrowserOpener 成功拉起 --app 窗口后才生效:java -jar 服务器部署、
 * 页面开在普通浏览器标签页(未由本进程拉起 app 窗口)等场景不受影响。
 * 已知边界:机器休眠超过超时时长会被误判为窗口关闭;进行中的扫描随进程退出中断,重开后可断点续扫。
 */
@Component
public class DesktopSession {

    private static final Logger log = LoggerFactory.getLogger(DesktopSession.class);

    private final DqProperties props;
    private final ApplicationContext ctx;
    /** 本进程是否成功拉起了 --app 应用模式窗口(只有这种情况才需要看门狗) */
    private volatile boolean appModeOpened;
    /** 最近一次页面心跳时间;0 表示还没收到过心跳,看门狗尚未武装 */
    private volatile long lastBeatMillis;

    public DesktopSession(DqProperties props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    public void markAppModeOpened() {
        this.appModeOpened = true;
    }

    public void beat() {
        this.lastBeatMillis = System.currentTimeMillis();
    }

    @Scheduled(fixedDelay = 5000)
    public void watchdog() {
        int timeoutSeconds = props.getDesktop().getShutdownTimeoutSeconds();
        if (!appModeOpened || timeoutSeconds <= 0 || lastBeatMillis == 0) {
            return;
        }
        long idleMillis = System.currentTimeMillis() - lastBeatMillis;
        if (idleMillis > timeoutSeconds * 1000L) {
            log.info("超过 {} 秒未收到页面心跳,判定应用窗口已关闭,退出进程", timeoutSeconds);
            System.exit(SpringApplication.exit(ctx, () -> 0));
        }
    }
}
