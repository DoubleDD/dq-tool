package com.example.dq.dialect

import com.example.dq.model.DbType

import java.sql.Connection
import java.sql.SQLException

/** 人大金仓 KingbaseES 方言:基于 PG 内核,语法与元数据查询同 PostgreSQL */
class KingbaseDialect : PostgresDialect() {

    override fun type(): DbType {
        return DbType.KINGBASE
    }

    override fun driverClassName(): String {
        return "com.kingbase8.Driver"
    }

    /** 在 PG 系统 schema 之外,金仓常见内置账号 schema(system/sao/sso 三员等) */
    override fun systemSchemas(): Set<String> {
        return super.systemSchemas() + setOf("sys", "sao", "sso")
    }

    /** 建库时选定的兼容模式:pg / oracle / mysql */
    @Throws(SQLException::class)
    override fun detectDbMode(conn: Connection): String? {
        conn.createStatement().use { st ->
            st.executeQuery("show database_mode").use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }
}
