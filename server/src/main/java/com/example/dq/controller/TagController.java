package com.example.dq.controller;

import com.example.dq.service.TagService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 表标记与统计(Javalin handler,路由在 WebServer 注册)。
 * 校验已在内核 TagService:重名 409、操作空表标记/参数错 400,走 WebServer 统一异常映射;
 * db query 参数原样透传(无库概念的方言为 null,内核归一为空串)。
 */
public class TagController {

    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    /** 全部标记(含系统「空表」),带打标表数 */
    public void list(Context ctx) {
        ctx.json(service.list());
    }

    public void create(Context ctx) {
        TagRequest req = Validators.validate(ctx.bodyAsClass(TagRequest.class));
        ctx.json(service.create(req.name(), req.color()));
    }

    public void update(Context ctx) {
        TagRequest req = Validators.validate(ctx.bodyAsClass(TagRequest.class));
        ctx.json(service.update(tagId(ctx), req.name(), req.color()));
    }

    public void delete(Context ctx) {
        service.delete(tagId(ctx));
    }

    /** 标记维度统计:顶部指标 + 按库分布 */
    public void stats(Context ctx) {
        ctx.json(service.stats(tagId(ctx)));
    }

    /** 库维度标记计数(库列表页标签块):只返回有标记表的库 */
    public void schemaTagStats(Context ctx) {
        ctx.json(service.schemaTagStats(dsId(ctx), ctx.queryParam("db")));
    }

    /** 某库下全部表的打标 map:表名 -> 标记数组(表列表页标记列) */
    public void tableTags(Context ctx) {
        ctx.json(service.tableTags(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** 整体替换单表的 USER 标记,返回该表最新标记数组(含空表标记) */
    public void replaceTableTags(Context ctx) {
        TableTagsRequest req = Validators.validate(ctx.bodyAsClass(TableTagsRequest.class));
        ctx.json(service.replaceTableTags(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema"),
                ctx.pathParam("table"), req.tagIds()));
    }

    private static long dsId(Context ctx) {
        return ctx.pathParamAsClass("dsId", Long.class).get();
    }

    private static long tagId(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }

    /** 标记新建/编辑请求体;名称为空、颜色缺省等细节由 TagService 归一 */
    public record TagRequest(String name, String color) {
    }

    /** 单表打标请求体:整体替换的 USER 标记 id 列表 */
    public record TableTagsRequest(@NotNull List<Long> tagIds) {
    }
}
