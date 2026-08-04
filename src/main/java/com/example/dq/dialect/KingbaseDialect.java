package com.example.dq.dialect;

import com.example.dq.model.DbType;

/** 人大金仓 KingbaseES 方言:基于 PG 内核,语法与元数据查询同 PostgreSQL */
public class KingbaseDialect extends PostgresDialect {

    @Override
    public DbType type() {
        return DbType.KINGBASE;
    }

    @Override
    public String driverClassName() {
        return "com.kingbase8.Driver";
    }
}
