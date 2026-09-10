package com.example.dq.controller;

import com.example.dq.model.ManualRelationResult;
import com.example.dq.model.RelationInferRequest;
import com.example.dq.model.RelationManualRequest;
import com.example.dq.service.RelationInferService;
import com.example.dq.service.TableRelationService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;

import java.util.Map;

/** ER 关系推导与表间关系管理(Javalin handler,路由在 WebServer 注册;参数错 400 走 WebServer 统一异常映射) */
public class RelationController {

    private final RelationInferService inferService;
    private final TableRelationService relationService;

    public RelationController(RelationInferService inferService, TableRelationService relationService) {
        this.inferService = inferService;
        this.relationService = relationService;
    }

    /** 提交一轮推导(锚点表+锚点字段,字段可带用户手填映射名,aliasOnly 时仅用映射名匹配),返回 job id;异步执行,前端轮询任务列表看进度 */
    public void submitInfer(Context ctx) {
        RelationInferRequest req = Validators.validate(ctx.bodyAsClass(RelationInferRequest.class));
        ctx.json(Map.of("jobId", inferService.submitInfer(req.getDatasourceId(), req.getDbName(),
                req.getSchemaName(), req.getTable(), req.getFields(), req.getUseSemantic(), req.getAliasOnly())));
    }

    /** 按库查询推导任务列表(新的在前) */
    public void listJobs(Context ctx) {
        ctx.json(inferService.listJobs(dsId(ctx), ctx.queryParam("dbName"), schemaName(ctx)));
    }

    /** 推导任务详情 */
    public void getJob(Context ctx) {
        ctx.json(inferService.getJob(id(ctx)));
    }

    /** 关系列表(status/table 可选过滤) */
    public void list(Context ctx) {
        ctx.json(relationService.list(dsId(ctx), ctx.queryParam("dbName"), schemaName(ctx),
                ctx.queryParam("status"), ctx.queryParam("table")));
    }

    /** 手动补充关系(直接 CONFIRMED;命中唯一键的已存在关系转 CONFIRMED 返回原 id,existing=true) */
    public void addManual(Context ctx) {
        RelationManualRequest req = Validators.validate(ctx.bodyAsClass(RelationManualRequest.class));
        ManualRelationResult r = relationService.addManual(req.getDatasourceId(), req.getDbName(), req.getSchemaName(),
                req.getOneTable(), req.getOneColumn(), req.getManyTable(), req.getManyColumn(),
                req.getCardinality(), req.getRemark());
        ctx.json(Map.of("id", r.getId(), "existing", r.getExisting()));
    }

    /** 确认关系(候选/否决均可转确认) */
    public void confirm(Context ctx) {
        relationService.confirm(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 否决关系(候选/确认均可转否决;否决对再推导时跳过) */
    public void reject(Context ctx) {
        relationService.reject(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 删除关系(仅候选态) */
    public void delete(Context ctx) {
        relationService.delete(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** ER 图数据:table 空=全库总图(CONFIRMED 边+孤儿表,includeCandidate 加候选边);非空=该表星型(含候选边) */
    public void graph(Context ctx) {
        ctx.json(relationService.graph(dsId(ctx), ctx.queryParam("dbName"), schemaName(ctx),
                ctx.queryParam("table"),
                ctx.queryParamAsClass("includeCandidate", Boolean.class).getOrDefault(false)));
    }

    private static long dsId(Context ctx) {
        return ctx.queryParamAsClass("datasourceId", Long.class).get();
    }

    private static String schemaName(Context ctx) {
        String schema = ctx.queryParam("schemaName");
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schemaName 不能为空");
        }
        return schema;
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
