package com.example.dq.controller;

import com.example.dq.model.ReportExportRequest;
import com.example.dq.service.WordReportExportService;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Word 报告异步导出任务(Javalin handler,路由在 WebServer 注册) */
public class ReportExportController {

    private final WordReportExportService service;

    public ReportExportController(WordReportExportService service) {
        this.service = service;
    }

    /** 提交导出任务(后台执行,前端轮询任务列表看进度);body {schemas:[...]} 为空表示全部库 */
    public void submit(Context ctx) {
        long dsId = ctx.pathParamAsClass("dsId", Long.class).get();
        ReportExportRequest req = ctx.bodyAsClass(ReportExportRequest.class);
        ctx.json(Map.of("taskId", service.submit(dsId, ctx.queryParam("db"),
                req == null ? null : req.getSchemas())));
    }

    /** 任务列表(新的在前);?datasourceId= 可按数据源过滤 */
    public void list(Context ctx) {
        Long dsId = ctx.queryParamAsClass("datasourceId", Long.class).getOrNull();
        ctx.json(service.list(dsId));
    }

    /** 另存为:下载报告文件 */
    public void download(Context ctx) throws Exception {
        long id = id(ctx);
        Path file = service.downloadFile(id);
        String filename = URLEncoder.encode(service.downloadName(id), StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
    }

    /** 调系统软件打开文档(MS Office → WPS → 默认关联) */
    public void open(Context ctx) {
        service.openDocument(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 打开文件所在目录并选中文件 */
    public void reveal(Context ctx) {
        service.reveal(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
