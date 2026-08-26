package com.example.dq.controller;

import com.example.dq.config.BrowserOpener;
import com.example.dq.model.BrowserSettingsRequest;
import com.example.dq.model.BrowserSettingsView;
import com.example.dq.model.DetectedBrowser;
import com.example.dq.model.ScanSettingsRequest;
import com.example.dq.service.SystemSettingsService;
import io.javalin.http.Context;

import java.util.List;

/** 系统全局设置(扫描参数、应用模式浏览器等,页面「系统设置」可视化维护;AI 配置走 AiConfigController) */
public class SystemSettingsController {

    private final SystemSettingsService service;
    private final BrowserOpener browserOpener;

    public SystemSettingsController(SystemSettingsService service, BrowserOpener browserOpener) {
        this.service = service;
        this.browserOpener = browserOpener;
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
}
