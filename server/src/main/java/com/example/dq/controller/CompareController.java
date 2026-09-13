package com.example.dq.controller;

import com.example.dq.model.CreateCompareJobRequest;
import com.example.dq.service.CompareService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 数据比对任务(Javalin handler,路由在 WebServer 注册) */
public class CompareController {

    private final CompareService service;

    public CompareController(CompareService service) {
        this.service = service;
    }

    /** 提交比对任务:同步校验后落库,后台执行,前端轮询任务列表看进度 */
    public void submit(Context ctx) {
        CreateCompareJobRequest req = Validators.validate(ctx.bodyAsClass(CreateCompareJobRequest.class));
        ctx.json(Map.of("jobId", service.submit(req)));
    }

    /** 任务列表(新的在前);query archived=true 时含已归档 */
    public void list(Context ctx) {
        boolean includeArchived = "true".equalsIgnoreCase(ctx.queryParam("archived"));
        ctx.json(service.list(includeArchived));
    }

    /** 任务详情:任务字段 + 目标指标列表 */
    public void detail(Context ctx) {
        ctx.json(service.detail(id(ctx)));
    }

    /** 差异明细分页:query targetId/diffType/kw/page(size 缺省 20) 组合过滤 */
    public void diffs(Context ctx) {
        Long targetId = ctx.queryParamAsClass("targetId", Long.class).getOrDefault(null);
        Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
        Integer size = ctx.queryParamAsClass("size", Integer.class).getOrDefault(20);
        ctx.json(service.diffs(id(ctx), targetId, ctx.queryParam("diffType"), ctx.queryParam("kw"), page, size));
    }

    /** 质量报告:目标指标 + 问题字段排行 + 汇总 */
    public void report(Context ctx) {
        ctx.json(service.report(id(ctx)));
    }

    /** 重跑(仅 DONE/FAILED/CANCELED):清空既有结果重新执行 */
    public void rerun(Context ctx) {
        service.rerun(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 归档/取消归档:query archived=true|false */
    public void archive(Context ctx) {
        boolean archived = !"false".equalsIgnoreCase(ctx.queryParam("archived"));
        service.archive(id(ctx), archived);
        ctx.json(Map.of("ok", true));
    }

    /** 删除任务(tx 级联删三表);RUNNING 中 409 */
    public void delete(Context ctx) {
        service.delete(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 差异明细导出 xlsx:每目标一 sheet,只含非 SAME 行,DIFF 行逐字段展开 */
    public void export(Context ctx) throws Exception {
        long id = id(ctx);
        String filename = URLEncoder.encode("差异明细-" + id + ".xlsx", StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        service.exportDiff(id, response.getOutputStream());
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
