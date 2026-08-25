package com.example.dq.controller;

import com.example.dq.model.ScanSettingsRequest;
import com.example.dq.service.SystemSettingsService;
import io.javalin.http.Context;

/** 系统全局设置(扫描参数等,页面「系统设置」可视化维护;AI 配置走 AiConfigController) */
public class SystemSettingsController {

    private final SystemSettingsService service;

    public SystemSettingsController(SystemSettingsService service) {
        this.service = service;
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

    /** 恢复默认:删除自定义行,回落到配置文件默认值 */
    public void scanReset(Context ctx) {
        service.resetScanSettings();
        ctx.json(service.scanSettingsView());
    }
}
