package com.example.dq.controller;

import com.example.dq.config.BrowserOpener;
import com.example.dq.config.JvmMemoryConfig;
import com.example.dq.model.BrowserSettingsRequest;
import com.example.dq.model.BrowserSettingsView;
import com.example.dq.model.DetectedBrowser;
import com.example.dq.model.ScanSettingsRequest;
import com.example.dq.service.SystemSettingsService;
import io.javalin.http.Context;

import java.util.List;

/** 系统全局设置(扫描参数、应用模式浏览器、JVM 最大内存等,页面「系统设置」可视化维护;AI 配置走 AiConfigController) */
public class SystemSettingsController {

    /** 最大内存设置视图:xmxMb=设置值(重启后生效),appliedMb=当前进程实际堆上限 */
    public record JvmMemoryView(int xmxMb, int appliedMb, int defaultMb, int minMb, int maxMb) {
    }

    /** 最大内存保存请求 */
    public record JvmMemoryRequest(Integer xmxMb) {
    }

    /** 页面心跳设置视图:intervalSeconds=有效值,其余为默认值/取值边界(供前端回显与单位换算) */
    public record HeartbeatView(int intervalSeconds, int defaultSeconds, int minSeconds, int maxSeconds) {
    }

    /** 页面心跳保存请求(间隔,秒) */
    public record HeartbeatRequest(Integer intervalSeconds) {
    }

    private final SystemSettingsService service;
    private final BrowserOpener browserOpener;
    private final java.nio.file.Path dataDir;

    public SystemSettingsController(SystemSettingsService service, BrowserOpener browserOpener,
            java.nio.file.Path dataDir) {
        this.service = service;
        this.browserOpener = browserOpener;
        this.dataDir = dataDir;
    }

    /** 扫描全局参数:合并配置文件默认值后的有效值 + 是否已自定义 */
    public void scanGet(Context ctx) {
        ctx.json(service.scanSettingsView());
    }

    /** 保存扫描全局参数(null 字段保留已存值) */
    public void scanSave(Context ctx) {
        service.saveScanSettings(ctx.bodyAsClass(ScanSettingsRequest.class));
        ctx.json(service.scanSettingsView());
    }

    /** 恢复默认:清空扫描参数列,回落到配置文件默认值 */
    public void scanReset(Context ctx) {
        service.resetScanSettings();
        ctx.json(service.scanSettingsView());
    }

    /** 浏览器设置:当前选择(null=自动) + 本机探测到的 Chromium 系浏览器清单 */
    public void browserGet(Context ctx) {
        ctx.json(browserView());
    }

    /**
     * 保存浏览器选择(null/"auto"/空串=恢复自动;其余必须是本机探测到的浏览器 id)。
     * 保存后立即写入 BrowserOpener(含冷启动镜像文件),对下次打开窗口生效。
     */
    public void browserSave(Context ctx) {
        String id = ctx.bodyAsClass(BrowserSettingsRequest.class).getBrowser();
        if (id != null && (id.isBlank() || id.equals("auto"))) {
            id = null;
        }
        if (id != null) {
            String wanted = id;
            boolean known = BrowserOpener.detectBrowsers().stream().anyMatch(b -> b.id().equals(wanted));
            if (!known) {
                throw new IllegalArgumentException("未在本机探测到浏览器: " + wanted);
            }
        }
        service.saveBrowserApp(id);
        browserOpener.setConfiguredBrowser(id);
        ctx.json(browserView());
    }

    private BrowserSettingsView browserView() {
        List<DetectedBrowser> browsers = BrowserOpener.detectBrowsers().stream()
                .map(b -> new DetectedBrowser(b.id(), b.name()))
                .toList();
        return new BrowserSettingsView(service.browserApp(), browsers);
    }

    /** JVM 最大内存:设置值(落 config.properties,重启后生效) + 当前进程实际堆上限 */
    public void jvmMemoryGet(Context ctx) {
        ctx.json(jvmMemoryView());
    }

    /** 保存最大内存(范围收敛由 JvmMemoryConfig 处理),下次启动生效 */
    public void jvmMemorySave(Context ctx) {
        Integer mb = ctx.bodyAsClass(JvmMemoryRequest.class).xmxMb();
        if (mb == null) {
            throw new IllegalArgumentException("最大内存不能为空");
        }
        try {
            JvmMemoryConfig.writeMb(dataDir, mb);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("保存最大内存设置失败: " + e.getMessage(), e);
        }
        ctx.json(jvmMemoryView());
    }

    private JvmMemoryView jvmMemoryView() {
        return new JvmMemoryView(
                JvmMemoryConfig.readMb(dataDir),
                (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024)),
                JvmMemoryConfig.DEFAULT_MB, JvmMemoryConfig.MIN_MB, JvmMemoryConfig.MAX_MB);
    }

    /** 页面心跳间隔:有效值 + 默认值/取值边界(前端按此间隔上报 /api/heartbeat,桌面看门狗按 3 个间隔判窗口关闭) */
    public void heartbeatGet(Context ctx) {
        ctx.json(heartbeatView());
    }

    /** 保存页面心跳间隔(秒),超界由内核钳制;保存后前端即时生效,看门狗超时随 3 个间隔自适应放宽 */
    public void heartbeatSave(Context ctx) {
        Integer seconds = ctx.bodyAsClass(HeartbeatRequest.class).intervalSeconds();
        if (seconds == null) {
            throw new IllegalArgumentException("心跳间隔不能为空");
        }
        service.saveHeartbeatInterval(seconds);
        ctx.json(heartbeatView());
    }

    private HeartbeatView heartbeatView() {
        return new HeartbeatView(
                service.heartbeatIntervalSeconds(),
                SystemSettingsService.DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
                SystemSettingsService.MIN_HEARTBEAT_INTERVAL_SECONDS,
                SystemSettingsService.MAX_HEARTBEAT_INTERVAL_SECONDS);
    }
}
