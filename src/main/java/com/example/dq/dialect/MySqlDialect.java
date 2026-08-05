package com.example.dq.dialect;

import com.example.dq.model.DbType;
import com.example.dq.model.TableStat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MySQL 方言 */
public class MySqlDialect extends AbstractDialect {

    @Override
    public DbType type() {
        return DbType.MYSQL;
    }

    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    protected boolean catalogBased() {
        return true;
    }

    @Override
    public List<String> listSchemas(Connection conn) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    @Override
    public List<TableStat> listTables(Connection conn, String schema) throws SQLException {
        List<TableStat> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME, TABLE_ROWS, COALESCE(DATA_LENGTH,0) + COALESCE(INDEX_LENGTH,0), "
                        + "COALESCE(TABLE_COMMENT,''), COALESCE(ENGINE,'') "
                        + "FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new TableStat(rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        }
        return tables;
    }

    @Override
    public Map<String, Integer> countTablesBySchema(Connection conn) throws SQLException {
        return queryCountByGroup(conn,
                "SELECT TABLE_SCHEMA, COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_TYPE = 'BASE TABLE' GROUP BY TABLE_SCHEMA");
    }

    @Override
    public Map<String, Long> sumSizeBySchema(Connection conn) throws SQLException {
        return queryLongByGroup(conn,
                "SELECT TABLE_SCHEMA, SUM(COALESCE(DATA_LENGTH,0) + COALESCE(INDEX_LENGTH,0)) "
                        + "FROM information_schema.TABLES WHERE TABLE_TYPE = 'BASE TABLE' GROUP BY TABLE_SCHEMA");
    }

    /** MySQL 走 information_schema 拿准确的字段注释与完整类型(含长度) */
    @Override
    public List<com.example.dq.model.ColumnMeta> listColumns(Connection conn, String schema, String table)
            throws SQLException {
        Map<String, com.example.dq.model.ColumnMeta> base = new LinkedHashMap<>();
        for (com.example.dq.model.ColumnMeta c : super.listColumns(conn, schema, table)) {
            base.put(c.name(), c);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, COLUMN_TYPE, COALESCE(COLUMN_COMMENT,''), IS_NULLABLE, COLUMN_DEFAULT "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    com.example.dq.model.ColumnMeta c = base.get(rs.getString(1));
                    if (c == null) continue;
                    base.put(c.name(), new com.example.dq.model.ColumnMeta(
                            c.name(), c.typeName(), rs.getString(2), c.jdbcType(),
                            "YES".equalsIgnoreCase(rs.getString(4)), rs.getString(5),
                            rs.getString(3), c.primaryKey(), c.pkSeq(), c.uniqueIndexFirst()));
                }
            }
        }
        return new ArrayList<>(base.values());
    }
}
