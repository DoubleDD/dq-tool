package com.example.dq.controller;

import com.example.dq.service.SampleExportService;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 表格批量导入数据源 + 抽样导出任务(Javalin handler,路由在 WebServer 注册) */
public class SampleExportController {

    private final SampleExportService service;

    public SampleExportController(SampleExportService service) {
        this.service = service;
    }

    /** 上传 Excel 提交任务(multipart 字段 file,仅 .xlsx);后台执行,前端轮询任务列表看进度 */
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
            ctx.json(Map.of("taskId", service.submit(filename, in)));
        }
    }

    /** 任务列表(新的在前) */
    public void list(Context ctx) {
        ctx.json(service.list());
    }

    /** 任务详情:数据源导入明细 + 逐表导出明细 */
    public void detail(Context ctx) {
        ctx.json(service.detail(id(ctx)));
    }

    /** 下载打包 zip */
    public void download(Context ctx) throws Exception {
        long id = id(ctx);
        Path file = service.downloadZip(id);
        String filename = URLEncoder.encode(service.downloadName(id), StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
    }

    /** 下载导入模版 xlsx(表头 + 示例行) */
    public void template(Context ctx) throws Exception {
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode("抽样导入模版.xlsx", StandardCharsets.UTF_8));
        service.writeTemplate(response.getOutputStream());
    }

    /** 调系统文件管理器打开任务产物目录 */
    public void openDir(Context ctx) {
        service.openDir(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 暂停运行中的任务(工作线程在下一检查点挂起) */
    public void pause(Context ctx) {
        service.pause(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 恢复暂停的任务 */
    public void resume(Context ctx) {
        service.resume(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 检测完成后由用户决策:继续导出(第二步) */
    public void export(Context ctx) {
        service.export(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 重新导入 Excel(multipart 字段 file):全量替换明细并重跑数据源检测,修复数据源 */
    public void reimport(Context ctx) throws Exception {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new IllegalArgumentException("请选择要上传的 Excel 文件");
        }
        String filename = file.filename() == null ? "" : file.filename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 文件");
        }
        long id = id(ctx);
        try (var in = file.content()) {
            service.reimport(id, filename, in);
        }
        ctx.json(Map.of("ok", true));
    }

    /** 批量删除任务(body {"ids":[...]}):完成/失败直接删,暂停中=取消并删,运行/排队跳过 */
    public void delete(Context ctx) {
        DeleteRequest req = ctx.bodyAsClass(DeleteRequest.class);
        ctx.json(service.delete(req.ids == null ? List.of() : req.ids));
    }

    /** 批量删除请求体 */
    public static class DeleteRequest {
        public List<Long> ids;
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
