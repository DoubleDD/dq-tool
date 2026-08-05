package com.example.dq.dialect;

import com.example.dq.model.DbType;
import com.example.dq.model.TableStat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** PostgreSQL 方言 */
public class PostgresDialect extends AbstractDialect {

    @Override
    public DbType type() {
        return DbType.POSTGRESQL;
    }

    @Override
    public String driverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public List<String> listSchemas(Connection conn) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata "
                             + "WHERE schema_name NOT LIKE 'pg\\_%' AND schema_name <> 'information_schema' "
                             + "ORDER BY schema_name")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    @Override
    public List<TableStat> listTables(Connection conn, String schema) throws SQLException {
        List<TableStat> tables = new ArrayList<>();
        // 注释取 pg_class 的描述,存储信息取表空间(默认表空间显示为空)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.relname, s.n_live_tup, pg_total_relation_size(c.oid), "
                        + "COALESCE(obj_description(c.oid, 'pg_class'), ''), COALESCE(ts.spcname, '') "
                        + "FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid "
                        + "LEFT JOIN pg_tablespace ts ON ts.oid = c.reltablespace "
                        + "WHERE n.nspname = ? AND c.relkind = 'r' ORDER BY c.relname")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(2);
                    Long estRows = rs.wasNull() ? null : rows;
                    tables.add(new TableStat(rs.getString(1), estRows, rs.getLong(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        }
        return tables;
    }

    @Override
    public Map<String, Integer> countTablesBySchema(Connection conn) throws SQLException {
        return queryCountByGroup(conn,
                "SELECT n.nspname, COUNT(*) FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relkind = 'r' AND n.nspname NOT LIKE 'pg\\_%' "
                        + "AND n.nspname <> 'information_schema' GROUP BY n.nspname");
    }

    /** PG 用 TABLESAMPLE 块级采样,速度快且近似随机 */
    @Override
    protected String sampledFrom(String qualifiedTable, long sampleRows, Long estRows) {
        double percent = 10.0;
        if (estRows != null && estRows > 0) {
            percent = Math.min(100.0, Math.max(0.01, 100.0 * sampleRows / estRows));
        }
        return "(SELECT * FROM " + qualifiedTable + " TABLESAMPLE SYSTEM (" + percent + ")) AS dq_sample";
    }

    @Override
    protected String sampledLimit(long sampleRows) {
        return "";
    }
}
