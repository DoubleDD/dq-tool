package com.example.dq.dialect

import com.example.dq.model.DbType
import com.example.dq.model.TableStat

import java.sql.Connection
import java.sql.SQLException

/** 达梦 DM8 方言 */
class DmDialect : AbstractDialect() {

    companion object {
        /** 总大小 = 表段 + 该表全部索引段;存储信息取表空间,注释取 ALL_TAB_COMMENTS */
        internal fun listTablesSql(withSize: Boolean): String {
            val sizeExpr = if (withSize)
                "(SELECT SUM(s.bytes) FROM all_segments s WHERE s.owner = t.owner " +
                "  AND (s.segment_name = t.table_name OR s.segment_name IN " +
                "    (SELECT i.index_name FROM all_indexes i WHERE i.owner = t.owner AND i.table_name = t.table_name)))"
            else
                "NULL"
            return "SELECT t.table_name, t.num_rows, " + sizeExpr + ", " +
                    "t.tablespace_name, NVL(c.comments, '') " +
                    "FROM all_tables t " +
                    "LEFT JOIN all_tab_comments c ON c.owner = t.owner AND c.table_name = t.table_name " +
                    "WHERE t.owner = ? ORDER BY t.table_name"
        }
    }

    override fun type(): DbType {
        return DbType.DM
    }

    override fun driverClassName(): String {
        return "dm.jdbc.driver.DmDriver"
    }

    override fun quote(identifier: String): String {
        return "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    @Throws(SQLException::class)
    override fun listSchemas(conn: Connection): List<String> {
        // DM 的 schema 与用户一一对应
        val schemas = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT username FROM all_users ORDER BY username").use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        return schemas
    }

    @Throws(SQLException::class)
    override fun countTablesBySchema(conn: Connection): Map<String, Int> {
        return queryCountByGroup(conn, "SELECT owner, COUNT(*) FROM all_tables GROUP BY owner")
    }

    @Throws(SQLException::class)
    override fun sumSizeBySchema(conn: Connection): Map<String, Long> {
        try {
            return queryLongByGroup(conn, "SELECT owner, SUM(bytes) FROM all_segments GROUP BY owner")
        } catch (e: SQLException) {
            // 部分 DM 实例或受限账号没有 ALL_SEGMENTS 视图,降级为不统计体积
            return emptyMap()
        }
    }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> {
        try {
            return queryTables(conn, schema, listTablesSql(true))
        } catch (e: SQLException) {
            // 部分 DM 实例或受限账号没有 ALL_SEGMENTS 视图,降级为不统计表大小
            return queryTables(conn, schema, listTablesSql(false))
        }
    }

    @Throws(SQLException::class)
    private fun queryTables(conn: Connection, schema: String, sql: String): List<TableStat> {
        val tables = ArrayList<TableStat>()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val rows = rs.getLong(2)
                    val estRows: Long? = if (rs.wasNull()) null else rows
                    val bytes = rs.getLong(3)
                    val sizeBytes: Long? = if (rs.wasNull()) null else bytes
                    tables.add(TableStat(rs.getString(1), estRows, sizeBytes,
                            rs.getString(5), rs.getString(4)))
                }
            }
        }
        return tables
    }
}
