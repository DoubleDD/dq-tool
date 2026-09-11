package com.example.dq.controller;

import com.example.dq.model.MetaSyncDetail;
import com.example.dq.service.MetaSyncService;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/** 元数据批量同步任务(数据源页「刷新」按钮;Javalin handler,路由在 WebServer 注册) */
public class MetadataSyncController {

    private final MetaSyncService service;

    public MetadataSyncController(MetaSyncService service) {
        this.service = service;
    }

    /** 启动同步(body {"datasourceIds":[...]});空列表 400,已有运行中任务 409;后台执行,返回 jobId */
    public void submit(Context ctx) {
        SyncRequest req = ctx.bodyAsClass(SyncRequest.class);
        List<Long> ids = req == null || req.datasourceIds == null ? List.of() : req.datasourceIds;
        ctx.json(Map.of("jobId", service.submit(ids)));
    }

    /** 最近一次任务(含明细),页面打开时恢复轮询;从未同步过返回 204 */
    public void latest(Context ctx) {
        MetaSyncDetail detail = service.latest();
        if (detail == null) {
            ctx.status(204);
        } else {
            ctx.json(detail);
        }
    }

    /** 任务详情(进度轮询) */
    public void detail(Context ctx) {
        ctx.json(service.detail(id(ctx)));
    }

    /** 取消任务:落库标志,工作线程在检查点响应 */
    public void cancel(Context ctx) {
        service.cancel(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 启动同步请求体 */
    public static class SyncRequest {
        public List<Long> datasourceIds;
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
