package com.example.dq.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面心跳看门狗(DesktopSession)单测:时钟用 AtomicLong 注入,
 * 覆盖心跳超时退出、进程挂起(墙钟大增/单调钟几乎不走)容忍、挂起后窗口真关仍退出等路径。
 * exit 由子类打标代替真实 System.exit。
 */
class DesktopSessionTest {

    /** 退出动作打标版:构造参数传 null,exit 重写为置标志位 */
    private static class FlagShutdown extends AppShutdown {
        private final AtomicBoolean exited = new AtomicBoolean(false);

        FlagShutdown() {
            super(null, () -> null);
        }

        @Override
        public void exit() {
            exited.set(true);
        }
    }

    private final AtomicLong wall = new AtomicLong(1_000_000L);
    private final AtomicLong nano = new AtomicLong(0L);

    private DesktopSession newSession(FlagShutdown shutdown) {
        DqProperties props = new DqProperties();
        return new DesktopSession(props, shutdown, wall::get, nano::get);
    }

    private void advanceMillis(long millis) {
        wall.addAndGet(millis);
        nano.addAndGet(millis * 1_000_000L);
    }

    private void waitForExit(FlagShutdown shutdown) throws InterruptedException {
        for (int i = 0; i < 100 && !shutdown.exited.get(); i++) {
            Thread.sleep(10);
        }
    }

    @Test
    void 心跳超时且无挂起时触发退出() throws Exception {
        FlagShutdown shutdown = new FlagShutdown();
        DesktopSession session = newSession(shutdown);
        session.markAppModeOpened();
        session.beat();

        advanceMillis(60_000);
        session.watchdog();

        waitForExit(shutdown);
        assertTrue(shutdown.exited.get(), "超过 45s 未收到心跳应触发退出");
    }

    @Test
    void 进程挂起后唤醒不退出并重置心跳基线() {
        FlagShutdown shutdown = new FlagShutdown();
        DesktopSession session = newSession(shutdown);
        session.markAppModeOpened();
        session.beat();

        // 模拟系统休眠 5 分钟:墙钟前进 300s,单调钟只走 5s(挂起时暂停)
        wall.addAndGet(300_000);
        nano.addAndGet(5_000_000_000L);
        session.watchdog();
        assertFalse(shutdown.exited.get(), "挂起刚唤醒不应判死退出");

        // 前端恢复后立即补心跳,后续检查不再退出
        session.beat();
        advanceMillis(5_000);
        session.watchdog();
        assertFalse(shutdown.exited.get(), "唤醒后心跳恢复不应退出");
    }

    @Test
    void 挂起期间窗口真关唤醒后仍会超时退出() throws Exception {
        FlagShutdown shutdown = new FlagShutdown();
        DesktopSession session = newSession(shutdown);
        session.markAppModeOpened();
        session.beat();

        // 休眠后唤醒,挂起检测重置心跳基线
        wall.addAndGet(300_000);
        nano.addAndGet(5_000_000_000L);
        session.watchdog();
        assertFalse(shutdown.exited.get(), "挂起刚唤醒不应判死退出");

        // 唤醒后窗口仍是关闭状态:再无心跳,超时后应退出
        advanceMillis(60_000);
        session.watchdog();

        waitForExit(shutdown);
        assertTrue(shutdown.exited.get(), "唤醒后持续无心跳仍应超时退出");
    }

    @Test
    void 未收到过心跳或托盘生效时不武装看门狗() {
        FlagShutdown shutdown = new FlagShutdown();
        DesktopSession session = newSession(shutdown);
        session.markAppModeOpened();

        // 从未收到心跳(lastBeatMillis == 0):不判定
        advanceMillis(120_000);
        session.watchdog();
        assertFalse(shutdown.exited.get(), "未收到过心跳时看门狗不应武装");

        // 托盘生效后看门狗停用:心跳陈旧也不退出
        session.beat();
        advanceMillis(120_000);
        session.markTrayActive();
        session.watchdog();
        assertFalse(shutdown.exited.get(), "托盘生效后看门狗停用,不应退出");
    }
}
