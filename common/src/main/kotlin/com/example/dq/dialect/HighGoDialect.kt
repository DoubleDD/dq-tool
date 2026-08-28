package com.example.dq.dialect

import com.example.dq.model.DbType

/** 瀚高 HighGo 方言:基于 PG 内核,语法与元数据查询同 PostgreSQL */
class HighGoDialect : PostgresDialect() {

    override fun type(): DbType {
        return DbType.HIGHGO
    }

    override fun driverClassName(): String {
        return "com.highgo.jdbc.Driver"
    }
}
