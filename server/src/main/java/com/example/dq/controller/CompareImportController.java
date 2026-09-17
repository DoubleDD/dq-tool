package com.example.dq.controller;

import com.example.dq.service.CompareImportService;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

/** 比对任务批量导入(Javalin handler,路由在 WebServer 注册):一 sheet 一任务,原件留档可下载 */
public class CompareImportController {

    private final CompareImportService service;

    public CompareImportController(CompareImportService service) {
        this.service = service;
    }

    /** 上传 Excel 提交导入批次(multipart 字段 file,仅 .xlsx):原件落盘 + 解析 + 数据源匹配;
     * 未配置大模型 409(入口即拦,文件不落盘),批次置 DS_REVIEW 等用户确认 */
    public void submit(Context ctx) throws Exception {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要上传的 Excel 文件");
        }
        String filename = file.filename() == null ? "" : file.filename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 文件");
        }
        try (var in = file.content()) {
            ctx.json(Map.of("batchId", service.submit(filename, in).getId()));
        }
    }

    /** 批次详情:数据源映射报告(dsReport)+ fileName/fileSize + 建出的 jobIds */
    public void detail(Context ctx) {
        ctx.json(service.get(id(ctx)));
    }

    /** 下载上传的原始 Excel(文件名原样,UTF-8 编码;任务列表点来源文件名走这里) */
    public void downloadFile(Context ctx) throws Exception {
        CompareImportService.CompareImportFile file = service.downloadFile(id(ctx));
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8));
        response.setContentLengthLong(Files.size(file.getPath()));
        Files.copy(file.getPath(), response.getOutputStream());
    }

    /** 用户确认数据源映射(仅 DS_REVIEW,否则 409):转后台实测建档 + 逐 sheet 建 PENDING 任务,
     * 建出的任务 id 清单轮询批次详情(detail 的 jobIds)获取;
     * 可选 body {"mapping": {"<ds_report 行 key>": <改绑数据源 id 或 null=待新建>}},
     * 不带 body/空映射 = 全部按匹配结果照旧(向后兼容);body 不是合法 JSON → 400 */
    public void confirm(Context ctx) {
        Map<String, Long> mapping = Map.of();
        String body = ctx.body();
        if (body != null && !body.isBlank()) {
            ConfirmRequest req;
            try {
                req = ctx.bodyAsClass(ConfirmRequest.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("确认请求体不是合法 JSON(期望 {\"mapping\": {...}} 或空)");
            }
            if (req != null && req.mapping != null) {
                mapping = req.mapping;
            }
        }
        service.confirm(id(ctx), mapping);
        ctx.json(Map.of("ok", true));
    }

    /** confirm 请求体(可选):mapping 键为 ds_report 行 key,值为改绑的数据源 id(null = 该行待新建) */
    public static class ConfirmRequest {
        public Map<String, Long> mapping;
    }

    /** 下载导入模版 xlsx(表头 + 基准/对比两行示例) */
    public void template(Context ctx) throws Exception {
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode("比对导入模版.xlsx", StandardCharsets.UTF_8));
        service.writeTemplate(response.getOutputStream());
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
