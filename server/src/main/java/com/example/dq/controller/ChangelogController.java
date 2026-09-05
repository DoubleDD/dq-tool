package com.example.dq.controller;

import com.example.dq.service.ChangelogService;
import io.javalin.http.Context;

/**
 * 更新日志端点:CHANGELOG.md(构建期拷入 jar)的解析结果。
 * /api/changelog 在授权前置校验中放行(与 /api/diagnostics 同理,未激活也能看版本更新说明)。
 */
public class ChangelogController {

    private final ChangelogService changelogService;

    public ChangelogController(ChangelogService changelogService) {
        this.changelogService = changelogService;
    }

    /** GET /api/changelog:当前版本号 + 全部版本条目(最新在前) */
    public void overview(Context ctx) {
        ctx.json(changelogService.overview());
    }
}
