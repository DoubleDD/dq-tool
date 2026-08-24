package com.example.dq.controller;

import com.example.dq.service.AnnotationTransferService;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

    /** 导入导出文件(multipart 文件上传),返回导入摘要;格式不对由内核抛参数错误(400) */
    public void importAnnotations(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }
        try (var in = file.content()) {
            ctx.json(service.importJson(in));
        }
    }
}
