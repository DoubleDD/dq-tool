package com.example.dq.controller;

import com.example.dq.service.BatchAiTagTaskService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 批量 AI 打标后台任务(Javalin handler,路由在 WebServer 注册)。
 * 提交立即返回 taskId,任务体后台逐表执行;进度经 /active 由后台任务中心 1s 轮询,
 * 终态回源详情取汇总计数弹完成通知;校验在内核 BatchAiTagTaskService/BatchAiTagService。
 */
public class AiTagBatchTaskController {

    private final BatchAiTagTaskService service;

    public AiTagBatchTaskController(BatchAiTagTaskService service) {
        this.service = service;
    }

    /** 提交批量 AI 打标任务(body {tableNames, tagIds}),返回 {taskId};db 走 query 参数 */
    public void submit(Context ctx) {
        BatchAiTagRequest req = Validators.validate(ctx.bodyAsClass(BatchAiTagRequest.class));
        ctx.json(Map.of("taskId", service.submit(
                ctx.pathParamAsClass("dsId", Long.class).get(), ctx.queryParam("db"),
                ctx.pathParam("schema"), req.tableNames(), req.tagIds())));
    }

    /** 后台任务中心轮询:全部未完成任务(跨库),active 须注册在 /{id} 之前 */
    public void listActive(Context ctx) {
        ctx.json(service.listActive());
    }

    /** 任务详情(终态回源:完成通知取 tagged/unmatched/skipped 汇总计数) */
    public void detail(Context ctx) {
        ctx.json(service.detail(ctx.pathParamAsClass("id", Long.class).get()));
    }

    /** 批量 AI 打标提交请求体:表名列表 + 候选标记 id 列表 */
    public record BatchAiTagRequest(@NotNull List<String> tableNames, @NotNull List<Long> tagIds) {
    }
}
