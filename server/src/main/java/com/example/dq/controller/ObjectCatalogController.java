package com.example.dq.controller;

import com.example.dq.model.ObjectBatchResult;
import com.example.dq.model.ObjectDirCreateRequest;
import com.example.dq.model.ObjectDirRenameRequest;
import com.example.dq.model.ObjectTableMountRequest;
import com.example.dq.model.ObjectTableRelRequest;
import com.example.dq.service.ObjectCatalogService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;

import java.util.Map;

/**
 * 对象管理(数据目录)Javalin handler,路由在 WebServer 注册。
 * 校验已在内核 ObjectCatalogService:参数错 400,走 WebServer 统一异常映射;
 * db 字段原样透传(无库概念的方言为 null,内核归一为空串)。
 */
public class ObjectCatalogController {

    private final ObjectCatalogService service;

    public ObjectCatalogController(ObjectCatalogService service) {
        this.service = service;
    }

    /** 某数据源的目录树(根为虚拟节点 id=0,真实顶层目录在其 children 下) */
    public void tree(Context ctx) {
        ctx.json(service.loadTree(ctx.pathParamAsClass("dsId", Long.class).get()));
    }

    /** 新建目录:parentId=0 表示挂在根下;同级重名 400 */
    public void createDir(Context ctx) {
        ObjectDirCreateRequest req = Validators.validate(ctx.bodyAsClass(ObjectDirCreateRequest.class));
        ctx.json(Map.of("id", service.createDir(req.getDatasourceId(), req.getParentId(), req.getName())));
    }

    /** 重命名目录 */
    public void renameDir(Context ctx) {
        ObjectDirRenameRequest req = Validators.validate(ctx.bodyAsClass(ObjectDirRenameRequest.class));
        service.renameDir(id(ctx), req.getName());
    }

    /** 删除目录:级联删除子孙目录与挂载/关系记录,返回 {dirs, tables, rels} 级联统计 */
    public void deleteDir(Context ctx) {
        ctx.json(service.deleteDir(id(ctx)));
    }

    /** 批量挂载表到目录:逐表可选关系类型(relKind);重复挂载幂等跳过,返回 {mounted, existing} 统计 */
    public void mountTable(Context ctx) {
        ObjectTableMountRequest req = Validators.validate(ctx.bodyAsClass(ObjectTableMountRequest.class));
        ObjectBatchResult r = service.mountTables(id(ctx), req.getDbName(), req.getSchemaName(),
                req.getItems(), req.getRemark());
        ctx.json(Map.of("mounted", r.getMounted(), "existing", r.getExisting()));
    }

    /** 取消挂载:级联删除其关系记录 */
    public void unmount(Context ctx) {
        service.unmount(id(ctx));
    }

    /** 批量登记关系表:逐表可选关系类型(relKind);不允许指向挂载表自身;重复登记幂等跳过,返回 {mounted, existing} 统计 */
    public void addRelation(Context ctx) {
        ObjectTableRelRequest req = Validators.validate(ctx.bodyAsClass(ObjectTableRelRequest.class));
        ObjectBatchResult r = service.addRelations(id(ctx), req.getDbName(), req.getSchemaName(),
                req.getItems(), req.getRemark());
        ctx.json(Map.of("mounted", r.getMounted(), "existing", r.getExisting()));
    }

    /** 移除关系表 */
    public void removeRelation(Context ctx) {
        service.removeRelation(id(ctx));
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
