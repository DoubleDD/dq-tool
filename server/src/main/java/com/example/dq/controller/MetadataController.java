package com.example.dq.controller;

import com.example.dq.model.SchemaDocUpdateRequest;
import com.example.dq.model.TableDocUpdateRequest;
import com.example.dq.service.MetadataService;
import com.example.dq.service.TableDocService;
import io.javalin.http.Context;

import java.sql.SQLException;
import java.util.Map;

/** 元数据/浏览(Javalin handler,路由在 WebServer 注册,统一挂在 /api/datasources/{dsId} 下) */
public class MetadataController {

    private final MetadataService service;
    private final TableDocService tableDocService;

    public MetadataController(MetadataService service, TableDocService tableDocService) {
        this.service = service;
        this.tableDocService = tableDocService;
    }

    public void listDatabases(Context ctx) throws SQLException {
        ctx.json(service.listDatabases(dsId(ctx), unfiltered(ctx)));
    }

    public void listSchemas(Context ctx) throws SQLException {
        ctx.json(service.listSchemas(dsId(ctx), ctx.queryParam("db"), unfiltered(ctx)));
    }

    /** 库列表页概览:schema + 表数量 + 最近一次扫描 */
    public void listSchemaStats(Context ctx) throws SQLException {
        ctx.json(service.listSchemaStats(dsId(ctx), ctx.queryParam("db")));
    }

    public void listTables(Context ctx) throws SQLException {
        ctx.json(service.listTables(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** 表列表页汇总:schema 下所有基表的字段总数 */
    public void countColumns(Context ctx) throws SQLException {
        ctx.json(service.countColumns(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** 每张表最近一次 DONE 扫描的信息(表名 -> {jobId, finishedAt}),表列表页点击表名直达最新结果、展示最近扫描时间 */
    public void latestScanJobs(Context ctx) {
        ctx.json(service.latestScanJobsByTable(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** 运行中任务里每张未完成表的分段进度(表名 -> {jobId, status, doneChunks, totalChunks}),表列表页轮询展示"扫描中"进度 */
    public void runningScans(Context ctx) {
        ctx.json(service.runningScansByTable(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** AI 表说明(表名 -> 说明文字),本地查询不连业务库 */
    public void tableDocs(Context ctx) {
        ctx.json(tableDocService.list(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema")));
    }

    /** 触发大模型生成单表说明并落库 */
    public void generateTableDoc(Context ctx) throws SQLException {
        ctx.json(tableDocService.generate(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema"),
                ctx.pathParam("table")));
    }

    /** 手动编辑单表说明 */
    public void updateTableDoc(Context ctx) {
        TableDocUpdateRequest req = ctx.bodyAsClass(TableDocUpdateRequest.class);
        ctx.json(tableDocService.update(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema"),
                ctx.pathParam("table"), req.getDescription()));
    }

    /** 编辑库级描述(Word 报告「实例描述」列;空白表示清除) */
    public void updateSchemaDescription(Context ctx) {
        SchemaDocUpdateRequest req = ctx.bodyAsClass(SchemaDocUpdateRequest.class);
        service.updateSchemaDescription(dsId(ctx), ctx.queryParam("db"), ctx.pathParam("schema"),
                req.getDescription());
        ctx.json(Map.of("ok", true));
    }

    private static long dsId(Context ctx) {
        return ctx.pathParamAsClass("dsId", Long.class).get();
    }

    /** all=true 旁路库过滤白名单:仅供编辑对话框「库过滤」页签拉全量列表 */
    private static boolean unfiltered(Context ctx) {
        return "true".equals(ctx.queryParam("all"));
    }
}
