package com.example.dq.dialect;

import com.example.dq.model.DbType;

/** OceanBase(MySQL 模式)方言:元数据与 SQL 语法同 MySQL */
public class OceanBaseDialect extends MySqlDialect {

    @Override
    public DbType type() {
        return DbType.OCEANBASE;
    }

    @Override
    public String driverClassName() {
        return "com.oceanbase.jdbc.Driver";
    }
}
