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

    /** 测试连通性:按请求参数(未保存也可)合并已存配置后调用大模型接口;成功返回「连接成功」 */
    public void test(Context ctx) {
        service.test(ctx.bodyAsClass(AiConfigRequest.class));
        ctx.json(java.util.Map.of("message", "连接成功"));
    }
}
