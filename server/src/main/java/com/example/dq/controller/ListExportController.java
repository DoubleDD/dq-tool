package com.example.dq.controller;

import com.example.dq.model.ListExportRequest;
import com.example.dq.service.ListExportService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 通用列表导出:前端把当前表格所见的表头与行数据 POST 上来渲染 xlsx 并暂存,
 * 再用返回的 token 走 GET 一次性下载(与扫描 Excel 同一个 Content-Disposition 流式模式,
 * 桌面端 save_download_as 原生保存对话框零改动)。
 */
public class ListExportController {

    private final ListExportService listExportService;

    public ListExportController(ListExportService listExportService) {
        this.listExportService = listExportService;
    }

    /** 提交列表数据,返回下载 token */
    public void stage(Context ctx) {
        ListExportRequest req = Validators.validate(ctx.bodyAsClass(ListExportRequest.class));
        String token = listExportService.stage(req.getFilename(), req.getSheets());
        ctx.json(Map.of("token", token));
    }

    /** 一次性下载:取走即删,过期或重复下载返回 404 */
    public void download(Context ctx) throws IOException {
        ListExportService.StagedExport staged = listExportService.take(ctx.pathParam("token"));
        if (staged == null) {
            ctx.status(404).json(Map.of("message", "导出文件不存在或已过期,请重新导出"));
            return;
        }
        String filename = URLEncoder.encode(staged.getFilename() + ".xlsx", StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        response.getOutputStream().write(staged.getBytes());
    }
}
