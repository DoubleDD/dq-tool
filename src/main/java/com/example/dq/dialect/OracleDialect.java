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

/**
 * Oracle 方言。
 * 注意:Oracle 把空字符串存为 NULL,空串统计恒为 0,NULL 数已覆盖。
 * 按主版本号选择语法特性:
 * - 分页:12c 起 OFFSET/FETCH;11g 用 ROWNUM 包装
 * - 表大小:23ai 起 ALL_SEGMENTS 被移除,改用 USER_SEGMENTS(仅当前用户 schema,其余为 NULL)
 */
public class OracleDialect extends AbstractDialect {

    @Override
    public DbType type() {
        return DbType.ORACLE;
    }

    @Override
    public String driverClassName() {
        return "oracle.jdbc.OracleDriver";
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public List<String> listSchemas(Connection conn) throws SQLException {
        // Oracle 的 schema 与用户一一对应
        List<String> schemas = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM all_users ORDER BY username")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    @Override
    public List<TableStat> listTables(Connection conn, String schema) throws SQLException {
        // 总大小 = 表段 + 该表全部索引段;存储信息取表空间,注释取 ALL_TAB_COMMENTS
        List<TableStat> tables = new ArrayList<>();
        String sql = listTablesSql(oracleMajor(conn));
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(2);
                    Long estRows = rs.wasNull() ? null : rows;
                    long bytes = rs.getLong(3);
                    Long sizeBytes = rs.wasNull() ? null : bytes;
                    String comment = rs.getString(5);
                    tables.add(new TableStat(rs.getString(1), estRows, sizeBytes,
                            comment == null ? "" : comment, rs.getString(4)));
                }
            }
        }
        return tables;
    }

    /** Oracle 主版本号(11/12/19/21/23),决定可用语法特性 */
    static int oracleMajor(Connection conn) throws SQLException {
        return conn.getMetaData().getDatabaseMajorVersion();
    }

    /** 23ai 起 ALL_SEGMENTS 被移除;低版本仍用 ALL_SEGMENTS(可统计任意 schema) */
    static String listTablesSql(int major) {
        String sizeExpr = major >= 23
                ? "CASE WHEN t.owner = SYS_CONTEXT('USERENV','SESSION_USER') THEN "
                + "(SELECT SUM(s.bytes) FROM user_segments s "
                + "  WHERE s.segment_name = t.table_name OR s.segment_name IN "
                + "    (SELECT i.index_name FROM all_indexes i WHERE i.owner = t.owner AND i.table_name = t.table_name)) END"
                : "(SELECT SUM(s.bytes) FROM all_segments s WHERE s.owner = t.owner "
                + "  AND (s.segment_name = t.table_name OR s.segment_name IN "
                + "    (SELECT i.index_name FROM all_indexes i WHERE i.owner = t.owner AND i.table_name = t.table_name)))";
        return "SELECT t.table_name, t.num_rows, " + sizeExpr + ", t.tablespace_name, "
                + "(SELECT c.comments FROM all_tab_comments c WHERE c.owner = t.owner AND c.table_name = t.table_name) "
                + "FROM all_tables t WHERE t.owner = ? ORDER BY t.table_name";
    }

    @Override
    protected String boundaryQuery(Connection conn, String qTable, String qKey, long offset) throws SQLException {
        return boundaryQuerySql(qTable, qKey, offset, oracleMajor(conn));
    }

    /** 12c 起 OFFSET/FETCH;11g 用 ROWNUM 双层包装取第 offset 行 */
    String boundaryQuerySql(String qTable, String qKey, long offset, int major) {
        if (major >= 12) {
            return "SELECT " + qKey + " FROM " + qTable
                    + " WHERE " + qKey + " IS NOT NULL ORDER BY " + qKey
                    + boundarySuffix(offset);
        }
        return "SELECT " + qKey + " FROM (SELECT ROWNUM dq_rn, dq_inner.* FROM (SELECT " + qKey
                + " FROM " + qTable + " WHERE " + qKey + " IS NOT NULL ORDER BY " + qKey + ") dq_inner"
                + " WHERE ROWNUM <= " + (offset + 1) + ") WHERE dq_rn = " + (offset + 1);
    }

    @Override
    protected String nullChunkProbeQuery(Connection conn, String qTable, String qKey) throws SQLException {
        return nullChunkProbeSql(qTable, qKey, oracleMajor(conn));
    }

    /** 12c 起 FETCH FIRST;11g 用 ROWNUM */
    String nullChunkProbeSql(String qTable, String qKey, int major) {
        if (major >= 12) {
            return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL"
                    + " ORDER BY " + qKey + limitClause(1);
        }
        return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL AND ROWNUM <= 1";
    }

    @Override
    protected String limitClause(long n) {
        return " FETCH FIRST " + n + " ROWS ONLY";
    }

    @Override
    protected String boundarySuffix(long offset) {
        return " OFFSET " + offset + " ROWS FETCH NEXT 1 ROWS ONLY";
    }

    /** Oracle 原生 SAMPLE BLOCK 块采样 */
    @Override
    protected String sampledFrom(String qualifiedTable, long sampleRows, Long estRows) {
        double percent = 10.0;
        if (estRows != null && estRows > 0) {
            percent = Math.min(100.0, Math.max(0.000001, 100.0 * sampleRows / estRows));
        }
        return qualifiedTable + " SAMPLE BLOCK (" + percent + ")";
    }

    @Override
    protected String sampledLimit(long sampleRows) {
        return "";
    }
}
