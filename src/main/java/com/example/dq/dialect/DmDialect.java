package com.example.dq.dialect;

import com.example.dq.model.DbType;
import com.example.dq.model.TableStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 达梦 DM8 方言 */
public class DmDialect extends AbstractDialect {

    private static final Logger log = LoggerFactory.getLogger(DmDialect.class);

    @Override
    public DbType type() {
        return DbType.DM;
    }

    @Override
    public String driverClassName() {
        return "dm.jdbc.driver.DmDriver";
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public List<String> listSchemas(Connection conn) throws SQLException {
        // DM 的 schema 与用户一一对应
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
    public Map<String, Integer> countTablesBySchema(Connection conn) throws SQLException {
        return queryCountByGroup(conn, "SELECT owner, COUNT(*) FROM all_tables GROUP BY owner");
    }

    @Override
    public Map<String, Long> sumSizeBySchema(Connection conn) throws SQLException {
        try {
            return queryLongByGroup(conn, "SELECT owner, SUM(bytes) FROM all_segments GROUP BY owner");
        } catch (SQLException e) {
            // 部分 DM 实例或受限账号没有 ALL_SEGMENTS 视图,降级为不统计体积
            log.warn("达梦 ALL_SEGMENTS 查询失败,降级为不统计体积: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<TableStat> listTables(Connection conn, String schema) throws SQLException {
        try {
            return queryTables(conn, schema, listTablesSql(true));
        } catch (SQLException e) {
            // 部分 DM 实例或受限账号没有 ALL_SEGMENTS 视图,降级为不统计表大小
            log.warn("达梦 ALL_SEGMENTS 查询失败,降级为不统计表大小: {}", e.getMessage());
            return queryTables(conn, schema, listTablesSql(false));
        }
    }

    /** 总大小 = 表段 + 该表全部索引段;存储信息取表空间,注释取 ALL_TAB_COMMENTS */
    static String listTablesSql(boolean withSize) {
        String sizeExpr = withSize
                ? "(SELECT SUM(s.bytes) FROM all_segments s WHERE s.owner = t.owner "
                + "  AND (s.segment_name = t.table_name OR s.segment_name IN "
                + "    (SELECT i.index_name FROM all_indexes i WHERE i.owner = t.owner AND i.table_name = t.table_name)))"
                : "NULL";
        return "SELECT t.table_name, t.num_rows, " + sizeExpr + ", "
                + "t.tablespace_name, NVL(c.comments, '') "
                + "FROM all_tables t "
                + "LEFT JOIN all_tab_comments c ON c.owner = t.owner AND c.table_name = t.table_name "
                + "WHERE t.owner = ? ORDER BY t.table_name";
    }

    private List<TableStat> queryTables(Connection conn, String schema, String sql) throws SQLException {
        List<TableStat> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(2);
                    Long estRows = rs.wasNull() ? null : rows;
                    long bytes = rs.getLong(3);
                    Long sizeBytes = rs.wasNull() ? null : bytes;
                    tables.add(new TableStat(rs.getString(1), estRows, sizeBytes,
                            rs.getString(5), rs.getString(4)));
                }
            }
        }
        return tables;
    }
}
