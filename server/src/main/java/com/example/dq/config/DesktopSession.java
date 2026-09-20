package com.example.dq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 桌面安装版(--app 应用模式窗口)的生命周期看门狗。
 * 问题背景:安装包隐藏终端,用户关闭浏览器 --app 窗口后没有地方能结束后端进程,残留孤儿进程。
 * 方案:前端按心跳间隔(系统设置页可调,默认 5 秒)上报心跳(/api/heartbeat),本进程拉起的 --app 窗口存在时武装看门狗,
 * 超过 max(dq.desktop.shutdown-timeout-seconds, 3 个心跳间隔) 未收到心跳即判定窗口已关闭,优雅退出进程。
 * 系统托盘(TrayManager)可用时以后台守护进程方式运行,托盘提供「打开窗口/退出」入口,
 * 看门狗停用;本类仅作为托盘不可用环境的兜底。
 * java -jar 服务器部署、页面开在普通浏览器标签页(未由本进程拉起 app 窗口)等场景不受影响。
 * 挂起容忍:判定心跳超时用墙钟(currentTimeMillis),机器休眠/进程被系统挂起期间墙钟照走而
 * 页面心跳冻结,直接按超时判定会把"刚唤醒"误判成"窗口已关闭"。看门狗每轮同时对比墙钟与
 * 单调钟(nanoTime,挂起时暂停)的增量,两者差值超过阈值即判定进程曾被挂起:本轮跳过退出判定,
 * 并把心跳基线重置到唤醒时刻——窗口还开着时前端恢复后立即补心跳,窗口真关了也会在超时后正常退出。
 * 已知边界:进行中的扫描随进程退出中断,重开后可断点续扫。
 */
public class DesktopSession {

    private static final Logger log = LoggerFactory.getLogger(DesktopSession.class);

    /** 挂起判定阈值(毫秒):相邻两轮检查中墙钟增量比单调钟增量多出该值以上,视为进程曾被挂起。
     *  正常调度抖动/GC 停顿下两个时钟等幅前进,差值接近 0;阈值远小于默认超时 45s,留足余量 */
    private static final long SUSPEND_DETECT_THRESHOLD_MS = 10_000;

    private final DqProperties props;
    private final AppShutdown shutdown;
    private final LongSupplier wallClock;
    private final LongSupplier nanoClock;
    /** 页面心跳间隔(秒)供应器:每轮看门狗实时取(系统设置页可改,内核未就绪时由调用方回落默认) */
    private final IntSupplier heartbeatIntervalSeconds;
    /** 本进程是否成功拉起了 --app 应用模式窗口(只有这种情况才需要看门狗) */
    private volatile boolean appModeOpened;
    /** 托盘图标(TrayManager)生效时后端以守护进程方式常驻,看门狗停用 */
    private volatile boolean trayActive;
    /** 最近一次页面心跳时间;0 表示还没收到过心跳,看门狗尚未武装 */
    private volatile long lastBeatMillis;
    /** 上一轮看门狗检查时的墙钟/单调钟读数,用于挂起检测(构造时取初值,首轮差值即真实间隔) */
    private volatile long lastRunWallMillis;
    private volatile long lastRunNanos;

    public DesktopSession(DqProperties props, AppShutdown shutdown) {
        this(props, shutdown, () -> com.example.dq.service.SystemSettingsService.DEFAULT_HEARTBEAT_INTERVAL_SECONDS);
    }

    /** 心跳间隔可注入:系统设置页改后看门狗下一轮即按新间隔判定 */
    public DesktopSession(DqProperties props, AppShutdown shutdown, IntSupplier heartbeatIntervalSeconds) {
        this(props, shutdown, heartbeatIntervalSeconds, System::currentTimeMillis, System::nanoTime);
    }

    /** 时钟可注入,供测试模拟挂起场景 */
    DesktopSession(DqProperties props, AppShutdown shutdown, IntSupplier heartbeatIntervalSeconds,
            LongSupplier wallClock, LongSupplier nanoClock) {
        this.props = props;
        this.shutdown = shutdown;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.wallClock = wallClock;
        this.nanoClock = nanoClock;
        this.lastRunWallMillis = wallClock.getAsLong();
        this.lastRunNanos = nanoClock.getAsLong();
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
        this.lastBeatMillis = wallClock.getAsLong();
    }

    void watchdog() {
        int configuredTimeout = props.getDesktop().getShutdownTimeoutSeconds();
        if (trayActive || !appModeOpened || configuredTimeout <= 0 || lastBeatMillis == 0) {
            return;
        }
        // 页面心跳间隔可调(系统设置页):窗口开着时心跳间隔可能长于配置的超时,
        // 有效超时取 max(配置超时, 3 个心跳间隔),保证窗口开着不会被误杀
        long timeoutSeconds = Math.max(configuredTimeout, 3L * heartbeatIntervalSeconds.getAsInt());
        long now = wallClock.getAsLong();
        long nanos = nanoClock.getAsLong();
        long wallDelta = now - lastRunWallMillis;
        long monoDeltaMillis = (nanos - lastRunNanos) / 1_000_000L;
        lastRunWallMillis = now;
        lastRunNanos = nanos;
        if (wallDelta - monoDeltaMillis > SUSPEND_DETECT_THRESHOLD_MS) {
            // 进程曾被挂起(系统休眠/冻结):挂起期间心跳同样冻结,idle 不可信,
            // 跳过本轮判定并把心跳基线重置到唤醒时刻,等前端恢复心跳
            log.info("检测到进程曾挂起(墙钟前进 {}ms、单调钟前进 {}ms),看门狗跳过本轮判定并重置心跳基线",
                    wallDelta, monoDeltaMillis);
            lastBeatMillis = now;
            return;
        }
        long idleMillis = now - lastBeatMillis;
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
