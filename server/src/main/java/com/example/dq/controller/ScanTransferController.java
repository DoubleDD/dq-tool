package com.example.dq.controller;

import com.example.dq.service.ScanTransferService;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 扫描记录导出/导入(Javalin handler,路由在 WebServer 注册) */
public class ScanTransferController {

    private final ScanTransferService service;

    public ScanTransferController(ScanTransferService service) {
        this.service = service;
    }

    /** 导出扫描记录为 JSON 文件;queryParam ids 逗号分隔,缺省/空 = 导出全部任务 */
    public void export(Context ctx) throws IOException {
        String filename = URLEncoder.encode("dq-scans-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json",
                StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        service.export(parseIds(ctx.queryParam("ids")), response.getOutputStream());
    }

    /** 导入预检(multipart 文件上传):返回文件内各数据源的 job 数与本机数据源清单,供前端做数据源映射 */
    public void preview(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }
        try (var in = file.content()) {
            ctx.json(service.preview(in));
        }
    }

    /** 导入导出文件(multipart 文件上传);表单字段 mapping 为 JSON(文件数据源名 → 本机数据源 id,0=跳过),返回导入摘要 */
    public void importJson(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }
        Map<String, Long> mapping = parseMapping(ctx.formParam("mapping"));
        try (var in = file.content()) {
            ctx.json(service.importJson(in, mapping));
        }
    }

    /** 逗号分隔的任务 id 列表;空白项忽略,非法值走 400 */
    private static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(ids.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("任务 id 列表格式不正确", e);
        }
    }

    /** 解析映射表单字段;为空视为全部跳过,非法 JSON 走 400 */
    private static Map<String, Long> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper().readValue(mappingJson,
                    new TypeReference<Map<String, Long>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("数据源映射格式不正确", e);
        }
    }
}
