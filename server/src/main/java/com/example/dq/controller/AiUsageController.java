package com.example.dq.controller;

import com.example.dq.service.AiUsageService;
import io.javalin.http.Context;

/** AI 调用 Token/费用统计:汇总/每日序列/场景分布 + 最近调用明细 */
public class AiUsageController {

    private final AiUsageService service;

    public AiUsageController(AiUsageService service) {
        this.service = service;
    }

    /** 统计:最近 days 天(默认 30,范围 1-365),含汇总指标 + 每日序列(缺日补零)+ 场景分布 */
    public void stats(Context ctx) {
        int days = clampInt(ctx.queryParam("days"), 30, 1, 365);
        ctx.json(service.stats(days));
    }

    /** 按扫描任务聚合的消耗序列(趋势图「按扫描」维度),days 口径同 stats */
    public void scanSeries(Context ctx) {
        int days = clampInt(ctx.queryParam("days"), 30, 1, 365);
        ctx.json(service.scanSeries(days));
    }

    /** 最近调用明细分页:page 默认 1,size 默认 20(上限 100),返回 items + total */
    public void logs(Context ctx) {
        int page = clampInt(ctx.queryParam("page"), 1, 1, Integer.MAX_VALUE);
        int size = clampInt(ctx.queryParam("size"), 20, 1, 100);
        ctx.json(service.recentPage(page, size));
    }

    private static int clampInt(String raw, int def, int min, int max) {
        if (raw == null) return def;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
