package com.example.dq.dialect;

import com.example.dq.model.ColumnMeta;
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

/**
 * SQL Server 方言。
 * "库"按 schema 处理(dbo 等);数据库(DATABASE)在界面上选择,
 * 通过 {@link #useDatabase} 切换连接 catalog,jdbcUrl 中的 databaseName 仅作默认库。
 * 注释走扩展属性 MS_Description。
 */
public class SqlServerDialect extends AbstractDialect {

    @Override
    public DbType type() {
        return DbType.SQLSERVER;
    }

    @Override
    public String driverClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public String quote(String identifier) {
        return "[" + identifier.replace("]", "]]" ) + "]";
    }

    /** 在线且有访问权限的数据库(保留 master,用户表可能建在里面) */
    @Override
    public List<String> listDatabases(Connection conn) throws SQLException {
        List<String> databases = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sys.databases WHERE state = 0 AND name <> 'tempdb' "
                             + "AND HAS_DBACCESS(name) = 1 ORDER BY name")) {
            while (rs.next()) {
                databases.add(rs.getString(1));
            }
        }
        return databases;
    }

    /** 切换当前库;连接池不重置 catalog,每条使用路径都要在借出后显式调用 */
    @Override
    public void useDatabase(Connection conn, String database) throws SQLException {
        if (database != null && !database.isBlank()) {
            conn.setCatalog(database);
        }
    }

    @Override
    public List<String> listSchemas(Connection conn) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sys.schemas WHERE name NOT IN "
                             + "('sys','INFORMATION_SCHEMA','guest','db_owner','db_accessadmin','db_securityadmin',"
                             + "'db_ddladmin','db_backupoperator','db_datareader','db_datawriter','db_denydatareader',"
                             + "'db_denydatawriter') ORDER BY name")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    @Override
    public List<TableStat> listTables(Connection conn, String schema) throws SQLException {
        List<TableStat> tables = new ArrayList<>();
        String sql = "SELECT t.name, "
                + "(SELECT SUM(d.row_count) FROM sys.dm_db_partition_stats d "
                + "  WHERE d.object_id = t.object_id AND d.index_id IN (0,1)), "
                + "(SELECT SUM(d.used_page_count) * 8192 FROM sys.dm_db_partition_stats d "
                + "  WHERE d.object_id = t.object_id), "
                + "CAST((SELECT ep.value FROM sys.extended_properties ep "
                + "  WHERE ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description') AS NVARCHAR(4000)) "
                + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id "
                + "WHERE s.name = ? ORDER BY t.name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(2);
                    Long estRows = rs.wasNull() ? null : rows;
                    long bytes = rs.getLong(3);
                    Long sizeBytes = rs.wasNull() ? null : bytes;
                    String comment = rs.getString(4);
                    tables.add(new TableStat(rs.getString(1), estRows, sizeBytes,
                            comment == null ? "" : comment, ""));
                }
            }
        }
        return tables;
    }

    /** 字段注释走扩展属性 MS_Description(minor_id = column_id) */
    @Override
    public List<ColumnMeta> listColumns(Connection conn, String schema, String table) throws SQLException {
        Map<String, ColumnMeta> base = new LinkedHashMap<>();
        for (ColumnMeta c : super.listColumns(conn, schema, table)) {
            base.put(c.name(), c);
        }
        String sql = "SELECT c.name, CAST(ep.value AS NVARCHAR(4000)) "
                + "FROM sys.columns c "
                + "JOIN sys.tables t ON t.object_id = c.object_id "
                + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
                + "LEFT JOIN sys.extended_properties ep ON ep.major_id = c.object_id "
                + "  AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' "
                + "WHERE s.name = ? AND t.name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta c = base.get(rs.getString(1));
                    if (c == null) continue;
                    String comment = rs.getString(2);
                    base.put(c.name(), new ColumnMeta(c.name(), c.typeName(), c.displayType(), c.jdbcType(),
                            c.nullable(), c.defaultValue(), comment == null ? "" : comment,
                            c.primaryKey(), c.pkSeq(), c.uniqueIndexFirst()));
                }
            }
        }
        return new ArrayList<>(base.values());
    }

    @Override
    protected String limitClause(long n) {
        return " OFFSET 0 ROWS FETCH NEXT " + n + " ROWS ONLY";
    }

    @Override
    protected String boundarySuffix(long offset) {
        return " OFFSET " + offset + " ROWS FETCH NEXT 1 ROWS ONLY";
    }

    /** SQL Server 原生 TABLESAMPLE(按行数,近似) */
    @Override
    protected String sampledFrom(String qualifiedTable, long sampleRows, Long estRows) {
        return qualifiedTable + " TABLESAMPLE (" + Math.max(1, sampleRows) + " ROWS)";
    }

    @Override
    protected String sampledLimit(long sampleRows) {
        return "";
    }
}
