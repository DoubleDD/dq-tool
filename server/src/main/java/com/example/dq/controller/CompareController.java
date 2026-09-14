package com.example.dq.controller;

import com.example.dq.model.CreateCompareJobRequest;
import com.example.dq.model.MappingSuggestRequest;
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

    /** 列级对比·字段映射预生成:大模型逐目标产出「基准字段 → 目标列」建议,人工审核后随任务提交 */
    public void suggestMapping(Context ctx) {
        MappingSuggestRequest req = Validators.validate(ctx.bodyAsClass(MappingSuggestRequest.class));
        ctx.json(service.suggestMappings(req));
    }

    /** 任务列表(新的在前);query archived=true 时含已归档 */
    public void list(Context ctx) {
        boolean includeArchived = "true".equalsIgnoreCase(ctx.queryParam("archived"));
        ctx.json(service.list(includeArchived));
    }

    /** RUNNING 任务瘦出行(后台任务中心 1s 轮询口径;compare 授权校验同前缀) */
    public void listActive(Context ctx) {
        ctx.json(service.listActive());
    }

    /** 任务详情:任务字段 + 目标指标列表 */
    public void detail(Context ctx) {
        ctx.json(service.detail(id(ctx)));
    }

    /** 差异明细分页:query targetId/diffType/kw/page(size 缺省 20) 组合过滤 */
    public void diffs(Context ctx) {
        // targetId 可缺省:必须用 getOrNull()。Javalin 的 getOrDefault(T) 是 Kotlin 方法、形参非空,
        // Java 侧传 null 会先被 Intrinsics 非空检查拦下(即使请求带了 targetId 也一律 500)
        Long targetId = ctx.queryParamAsClass("targetId", Long.class).getOrNull();
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

    /** 比对报告导出 xlsx:首 sheet「总览」一行一系统,其后每个差异行一个 sheet 展开字段级明细 */
    public void export(Context ctx) throws Exception {
        long id = id(ctx);
        String filename = URLEncoder.encode("比对总览-" + id + ".xlsx", StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        service.exportDiff(id, response.getOutputStream());
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
