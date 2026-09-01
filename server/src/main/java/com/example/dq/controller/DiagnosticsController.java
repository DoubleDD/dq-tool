package com.example.dq.controller;

import com.example.dq.model.DiagnosticsReport;
import com.example.dq.model.LogErrorItem;
import com.example.dq.service.DiagnosticsService;
import com.example.dq.web.LogEntry;
import com.example.dq.web.LogStreamAppender;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统诊断(排错中心)端点:概览聚合 + 数据源连通性实测。
 * /api/diagnostics 前缀在授权前置校验中放行(未激活恰是最需要诊断的场景);
 * 日志摘录来自内存日志缓冲,运行日志已转为普通功能,不再按授权码门控。
 */
public class DiagnosticsController {

    private final DiagnosticsService diagnosticsService;
    private final LogStreamAppender appender;

    public DiagnosticsController(DiagnosticsService diagnosticsService, LogStreamAppender appender) {
        this.diagnosticsService = diagnosticsService;
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

    /** 内存环形缓冲中最近 50 条 WARN/ERROR 日志 */
    private List<LogErrorItem> recentLogErrors() {
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
