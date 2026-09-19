package com.example.dq.controller;

import com.example.dq.model.ExportFailRequest;
import com.example.dq.model.ExportKind;
import com.example.dq.model.ExportLandedRequest;
import com.example.dq.service.ExportCenterService;
import io.javalin.http.Context;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 导出中心(V66):全部导出入口统一登记可查(Javalin handler,路由在 WebServer 注册) */
public class ExportCenterController {

    private final ExportCenterService service;

    public ExportCenterController(ExportCenterService service) {
        this.service = service;
    }

    /** GET /api/export-center?kind=&keyword=&start=&end=&page=&size= → {total, items} */
    public void list(Context ctx) {
        ExportKind kind = null;
        String kindParam = ctx.queryParam("kind");
        if (kindParam != null && !kindParam.isBlank()) {
            kind = ExportKind.parse(kindParam);
            if (kind == null) {
                throw new IllegalArgumentException("未知导出类型: " + kindParam);
            }
        }
        ctx.json(service.list(kind, ctx.queryParam("keyword"), parseDate(ctx.queryParam("start")),
                parseDate(ctx.queryParam("end")),
                ctx.queryParamAsClass("page", Integer.class).getOrDefault(1),
                ctx.queryParamAsClass("size", Integer.class).getOrDefault(20)));
    }

    /** DELETE /api/export-center/{kind}/{id}:删除登记记录(仅删记录,不动磁盘文件) */
    public void delete(Context ctx) {
        service.delete(ctx.pathParam("kind"), ctx.pathParamAsClass("id", Long.class).get());
        ctx.json(java.util.Map.of("ok", true));
    }

    /** POST /api/export-center/landed {fileName}:统一直存成功后回填 rel_path + 实测大小(按文件名关联最新 RUNNING) */
    public void landed(Context ctx) {
        String fileName = ctx.bodyAsClass(ExportLandedRequest.class).getFileName();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("缺少 fileName");
        }
        service.landed(fileName);
        ctx.json(java.util.Map.of("ok", true));
    }

    /** POST /api/export-center/fail {path, error}:导出失败把点击时登记的「生成中」翻 FAILED */
    public void fail(Context ctx) {
        ExportFailRequest req = ctx.bodyAsClass(ExportFailRequest.class);
        if (req.getPath() == null || req.getPath().isBlank()) {
            throw new IllegalArgumentException("缺少 path");
        }
        service.failByPath(req.getPath(), req.getError() == null ? "导出失败" : req.getError());
        ctx.json(java.util.Map.of("ok", true));
    }

    /** yyyy-MM-dd → 当天起始;end 由 service 侧 +1 天做含当日区间 */
    private static LocalDateTime parseDate(String raw) {
        return raw == null || raw.isBlank() ? null : LocalDate.parse(raw.trim()).atStartOfDay();
    }
}
