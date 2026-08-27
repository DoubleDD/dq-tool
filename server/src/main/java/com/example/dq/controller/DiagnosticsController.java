package com.example.dq.controller;

import com.example.dq.license.LicenseFeature;
import com.example.dq.model.DiagnosticsReport;
import com.example.dq.model.LogErrorItem;
import com.example.dq.model.LicenseFeatureRequiredException;
import com.example.dq.service.DiagnosticsService;
import com.example.dq.service.LicenseService;
import com.example.dq.web.LogEntry;
import com.example.dq.web.LogStreamAppender;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统诊断(排错中心)端点:概览聚合 + 数据源连通性实测。
 * /api/diagnostics 前缀在授权前置校验中放行(未激活恰是最需要诊断的场景);
 * 日志摘录仍按 logs 受控功能门控——已激活但授权码未包含 logs 功能时降级为 null。
 */
public class DiagnosticsController {

    private final DiagnosticsService diagnosticsService;
    private final LicenseService licenseService;
    private final LogStreamAppender appender;

    public DiagnosticsController(DiagnosticsService diagnosticsService, LicenseService licenseService,
                                 LogStreamAppender appender) {
        this.diagnosticsService = diagnosticsService;
        this.licenseService = licenseService;
        this.appender = appender;
    }

    /** GET /api/diagnostics:诊断概览(快路径,全本地聚合) */
    public void overview(Context ctx) {
        ctx.json(diagnosticsService.overview(recentLogErrors()));
    }

    /** POST /api/diagnostics/check-datasources:全部已存数据源连通性实测(慢路径,用户手动触发) */
    public void checkDatasources(Context ctx) {
        ctx.json(diagnosticsService.checkDatasources());
    }

    /**
     * 内存环形缓冲中最近 50 条 WARN/ERROR 日志;授权码未包含 logs 功能时返回 null(前端展示升级提示)。
     * checkFeature(requireActive=false):未激活实例直接放行(未激活时功能全锁,诊断页看日志无碍)。
     */
    private List<LogErrorItem> recentLogErrors() {
        try {
            licenseService.checkFeature(LicenseFeature.LOGS, false);
        } catch (LicenseFeatureRequiredException e) {
            return null;
        }
        List<LogEntry> entries = appender.getRecentEntries();
        List<LogErrorItem> items = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && items.size() < 50; i--) {
            LogEntry e = entries.get(i);
            if ("ERROR".equals(e.level()) || "WARN".equals(e.level())) {
                items.add(new LogErrorItem(e.timestamp(), e.level(), e.logger(), e.message(), e.stackTrace()));
            }
        }
        return items;
    }
}
