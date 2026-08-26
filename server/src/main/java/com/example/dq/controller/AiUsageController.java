package com.example.dq.controller;

import com.example.dq.service.AiUsageService;
import io.javalin.http.Context;

import java.util.Map;

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

    /** 最近调用明细(默认 50 条,上限 200) */
    public void logs(Context ctx) {
        int limit = clampInt(ctx.queryParam("limit"), 50, 1, 200);
        ctx.json(Map.of("items", service.recent(limit)));
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
