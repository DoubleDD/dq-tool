package com.example.dq.controller;

import com.example.dq.model.AiConfigRequest;
import com.example.dq.service.AiConfigService;
import io.javalin.http.Context;

/** AI 大模型接口配置(全局单条,页面可视化维护) */
public class AiConfigController {

    private final AiConfigService service;

    public AiConfigController(AiConfigService service) {
        this.service = service;
    }

    public void get(Context ctx) {
        ctx.json(service.get());
    }

    public void save(Context ctx) {
        service.save(ctx.bodyAsClass(AiConfigRequest.class));
    }
}
