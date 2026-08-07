package com.example.dq.controller;

import com.example.dq.model.ScanRequest;
import com.example.dq.service.ExportService;
import com.example.dq.service.ScanService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 扫描作业(Javalin handler,路由在 WebServer 注册) */
public class ScanController {

    private final ScanService scanService;
    private final ExportService exportService;

    public ScanController(ScanService scanService, ExportService exportService) {
        this.scanService = scanService;
        this.exportService = exportService;
    }

    public void create(Context ctx) throws Exception {
        ScanRequest req = Validators.validate(ctx.bodyAsClass(ScanRequest.class));
        ctx.json(Map.of("jobId", scanService.createScan(req)));
    }

    public void list(Context ctx) {
        Long datasourceId = ctx.queryParamAsClass("datasourceId", Long.class).getOrNull();
        ctx.json(scanService.listJobs(datasourceId, ctx.queryParam("dbName"), ctx.queryParam("schemaName")));
    }

    public void get(Context ctx) {
        ctx.json(scanService.getJob(jobId(ctx)));
    }

    public void cancel(Context ctx) {
        scanService.cancel(jobId(ctx));
    }

    public void resume(Context ctx) {
        scanService.resume(jobId(ctx));
    }

    public void delete(Context ctx) {
        scanService.delete(jobId(ctx));
    }

    public void columns(Context ctx) {
        ctx.json(scanService.getColumns(jobId(ctx), ctx.pathParam("tableName")));
    }

    public void export(Context ctx) throws IOException {
        long jobId = jobId(ctx);
        String filename = URLEncoder.encode("dq-scan-" + jobId + ".xlsx", StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        // tableCols/cols:逗号分隔的列 key(表列表/字段明细 sheet),缺省导出全部列;空值表示只留固定首列
        exportService.export(jobId, splitKeys(ctx.queryParam("tableCols")), splitKeys(ctx.queryParam("cols")),
                response.getOutputStream());
    }

    private static long jobId(Context ctx) {
        return ctx.pathParamAsClass("jobId", Long.class).get();
    }

    private static List<String> splitKeys(String param) {
        return param == null ? null : Arrays.asList(param.split(","));
    }
}
