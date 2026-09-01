package com.example.dq.controller;

import com.example.dq.service.SqlConsoleService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.validation.constraints.NotBlank;

import java.sql.SQLException;

/** SQL 控制台(Javalin handler,路由在 WebServer 注册,挂在 /api/datasources/{dsId} 下):执行任意 SQL,返回结果集或受影响行数 */
public class SqlConsoleController {

    private final SqlConsoleService service;

    public SqlConsoleController(SqlConsoleService service) {
        this.service = service;
    }

    /** 执行 SQL 原文:sql 空白 400(参数校验)、目标库错误 502(统一异常映射) */
    public void execute(Context ctx) throws SQLException {
        SqlExecuteRequest req = Validators.validate(ctx.bodyAsClass(SqlExecuteRequest.class));
        long dsId = ctx.pathParamAsClass("dsId", Long.class).get();
        ctx.json(service.execute(dsId, req.sql()));
    }

    /** SQL 执行请求体;sql 为用户输入的 SQL 原文,透传业务库(JDBC)执行 */
    public record SqlExecuteRequest(@NotBlank String sql) {
    }
}
