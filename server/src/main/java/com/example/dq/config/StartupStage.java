package com.example.dq.config;

/**
 * 启动阶段进度(启动优化):前端占位页在轮询 /api/health 时读取阶段号,
 * 在浏览器窗口里展示"正在启动服务"的实时进度(第几步),让等待有动效反馈。
 * 阶段号只用于展示,就绪与否仍以 /api/health 200 为准。
 * 启动早期(HTTP 未监听)前端连不上,阶段 1 只会出现在日志里;浏览器实际能看到 2→3。
 */
public final class StartupStage {

    /** 加载配置/装配阶段(启动早期,前端尚不可达) */
    public static final int CONFIG = 1;
    /** 服务已监听、窗口已打开(占位页可见) */
    public static final int HTTP = 2;
    /** 共享内核初始化中(建表/迁移/中断恢复) */
    public static final int KERNEL = 3;
    /** 已就绪(与 /api/health 200 同刻) */
    public static final int READY = 4;

    private static volatile int current = CONFIG;

    private StartupStage() {
    }

    public static void set(int stage) {
        current = stage;
    }

    public static int get() {
        return current;
    }
}
