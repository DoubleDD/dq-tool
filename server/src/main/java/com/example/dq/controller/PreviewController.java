package com.example.dq.controller;

import com.example.dq.service.PreviewService;
import io.javalin.http.Context;

import java.sql.SQLException;

/** 表数据预览(只读抽样查询;Javalin handler,路由在 WebServer 注册,挂在 /api/datasources/{dsId} 下) */
public class PreviewController {

    private final PreviewService service;

    public PreviewController(PreviewService service) {
        this.service = service;
    }

    /** 数据预览:列结构 + 第 page 页 size 行数据 + 全表总数;支持 where/orderBy 原文过滤(DataGrip 风格);page 缺省 1、size 缺省 20 */
    public void preview(Context ctx) throws SQLException {
        long dsId = ctx.pathParamAsClass("dsId", Long.class).get();
        Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
        Integer size = ctx.queryParamAsClass("size", Integer.class).getOrDefault(20);
        ctx.json(service.previewTable(dsId, ctx.queryParam("db"),
                ctx.pathParam("schema"), ctx.pathParam("table"),
                ctx.queryParam("where"), ctx.queryParam("orderBy"), page, size));
    }
}
