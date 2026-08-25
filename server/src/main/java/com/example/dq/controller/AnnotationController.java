package com.example.dq.controller;

import com.example.dq.service.AnnotationTransferService;
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
import java.util.Map;

/** 标记与描述数据的导出/导入(Javalin handler,路由在 WebServer 注册) */
public class AnnotationController {

    private final AnnotationTransferService service;

    public AnnotationController(AnnotationTransferService service) {
        this.service = service;
    }

    /** 导出标记定义(含描述)、表-标记关联、表描述为 JSON 文件 */
    public void export(Context ctx) throws IOException {
        String filename = URLEncoder.encode("dq-annotations-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json",
                StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        service.export(response.getOutputStream());
    }

    /** 导入预检(multipart 文件上传):返回文件里标记数量与表级数据按数据源的分布,供前端做数据源映射 */
    public void previewImport(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }
        try (var in = file.content()) {
            ctx.json(service.preview(in));
        }
    }

    /** 导入导出文件(multipart 文件上传);表单字段 mapping 为 JSON(文件数据源名 → 本机数据源 id,0=跳过),返回导入摘要 */
    public void importAnnotations(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }
        Map<String, Long> mapping = parseMapping(ctx.formParam("mapping"));
        try (var in = file.content()) {
            ctx.json(service.importJson(in, mapping));
        }
    }

    /** 解析映射表单字段;为空视为无映射(回退按数据源名匹配),非法 JSON 走 400 */
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
