package com.example.dq.controller;

import com.example.dq.model.ManualCollectItem;
import com.example.dq.service.ManualCollectService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 人工采集(Javalin handler,路由在 WebServer 注册)。
 * 校验已在内核 ManualCollectService:参数错 400,走 WebServer 统一异常映射;
 * db query 参数原样透传(无库概念的方言为 null,内核归一为空串)。
 */
public class ManualCollectController {

    private final ManualCollectService service;

    public ManualCollectController(ManualCollectService service) {
        this.service = service;
    }

    /** 全部采集记录(跨数据源,采集时间倒序),带数据源名 */
    public void list(Context ctx) {
        ctx.json(service.list());
    }

    /** 某库下已采集表 map:表名 -> 采集记录 id(表列表页行内「采集/已采集」状态) */
    public void tableCollectMap(Context ctx) {
        ctx.json(service.tableCollectMap(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** 批量采集(单表采集也是一项的批量):已存在的跳过,返回 {added, skipped} */
    public void addBatch(Context ctx) {
        CollectRequest req = Validators.validate(ctx.bodyAsClass(CollectRequest.class));
        List<ManualCollectItem> items = req.items().stream()
                .map(it -> new ManualCollectItem(it.datasourceId(), it.dbName(), it.schemaName(),
                        it.tableName(), it.tableComment()))
                .toList();
        ctx.json(service.addBatch(items));
    }

    /** 取消采集 */
    public void delete(Context ctx) {
        service.delete(ctx.pathParamAsClass("id", Long.class).get());
    }

    /** 批量取消采集:不存在的 id 跳过(幂等),返回 {deleted} */
    public void deleteBatch(Context ctx) {
        DeleteBatchRequest req = Validators.validate(ctx.bodyAsClass(DeleteBatchRequest.class));
        ctx.json(Map.of("deleted", service.deleteBatch(req.ids())));
    }

    private static long dsId(Context ctx) {
        return ctx.pathParamAsClass("dsId", Long.class).get();
    }

    /** 批量采集请求体:四元组定位一张表,tableComment 为采集时注释快照(可空) */
    public record CollectRequest(@NotNull List<CollectItem> items) {
    }

    public record CollectItem(@NotNull Long datasourceId, String dbName, String schemaName,
                              String tableName, String tableComment) {
    }

    /** 批量取消采集请求体:按记录 id 删除 */
    public record DeleteBatchRequest(@NotNull List<Long> ids) {
    }
}
