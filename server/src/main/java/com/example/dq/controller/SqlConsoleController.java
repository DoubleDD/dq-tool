package com.example.dq.controller;

import com.example.dq.service.LocalH2ConsoleService;
import com.example.dq.service.SqlConsoleService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.validation.constraints.NotBlank;

import java.sql.SQLException;

/**
 * SQL 控制台(Javalin handler,路由在 WebServer 注册):对数据源执行任意 SQL 返回结果集/受影响行数;
 * 另提供本地 H2 库(应用自身配置库)的只读入口——只能查询,写语句一律 400。
 */
public class SqlConsoleController {

    private final SqlConsoleService service;
    private final LocalH2ConsoleService localH2Service;

    public SqlConsoleController(SqlConsoleService service, LocalH2ConsoleService localH2Service) {
        this.service = service;
        this.localH2Service = localH2Service;
    }

    /** 执行 SQL 原文:sql 空白 400(参数校验)、目标库错误 502(统一异常映射) */
    public void execute(Context ctx) throws SQLException {
        SqlExecuteRequest req = Validators.validate(ctx.bodyAsClass(SqlExecuteRequest.class));
        long dsId = ctx.pathParamAsClass("dsId", Long.class).get();
        ctx.json(service.execute(dsId, req.sql(), req.schema()));
    }

    /** 本地 H2 库只读执行:非查询语句(INSERT/UPDATE/DDL/EXPLAIN ANALYZE 等)400,执行错误 502 */
    public void executeLocalH2(Context ctx) throws SQLException {
        SqlExecuteRequest req = Validators.validate(ctx.bodyAsClass(SqlExecuteRequest.class));
        ctx.json(localH2Service.execute(req.sql(), req.schema()));
    }

    /** 本地 H2 库 schema 清单(控制台库下拉) */
    public void localH2Schemas(Context ctx) throws SQLException {
        ctx.json(localH2Service.listSchemas());
    }

    /** 本地 H2 库指定 schema 的表/视图清单(智能提示);schema 缺省=库默认 schema */
    public void localH2Tables(Context ctx) throws SQLException {
        ctx.json(localH2Service.listTables(ctx.queryParam("schema")));
    }

    /** 本地 H2 库指定 schema 的整库字段清单(智能提示);schema 缺省=库默认 schema */
    public void localH2Columns(Context ctx) throws SQLException {
        ctx.json(localH2Service.listColumns(ctx.queryParam("schema")));
    }

    /** SQL 执行请求体;sql 为用户输入的 SQL 原文,透传业务库(JDBC)执行;schema 为选中的目标库(可空=数据源默认库) */
    public record SqlExecuteRequest(@NotBlank String sql, String schema) {
    }
}
