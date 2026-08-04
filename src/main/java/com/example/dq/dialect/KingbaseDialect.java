package com.example.dq.dialect;

import com.example.dq.model.DbType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

    /** 建库时选定的兼容模式:pg / oracle / mysql */
    @Override
    public String detectDbMode(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("show database_mode")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
