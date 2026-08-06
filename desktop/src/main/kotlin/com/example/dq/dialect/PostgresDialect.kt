package com.example.dq.dialect

import com.example.dq.model.DbType
import com.example.dq.model.TableStat

import java.sql.Connection
import java.sql.SQLException

/** PostgreSQL 方言 */
open class PostgresDialect : AbstractDialect() {

    override fun type(): DbType {
        return DbType.POSTGRESQL
    }

    override fun driverClassName(): String {
        return "org.postgresql.Driver"
    }

    override fun quote(identifier: String): String {
        return "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    @Throws(SQLException::class)
    override fun listSchemas(conn: Connection): List<String> {
        val schemas = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                    "SELECT schema_name FROM information_schema.schemata " +
                            "WHERE schema_name NOT LIKE 'pg\\_%' AND schema_name <> 'information_schema' " +
                            "ORDER BY schema_name").use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        return schemas
    }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> {
        val tables = ArrayList<TableStat>()
        // 注释取 pg_class 的描述,存储信息取表空间(默认表空间显示为空)
        conn.prepareStatement(
                "SELECT c.relname, s.n_live_tup, pg_total_relation_size(c.oid), " +
                        "COALESCE(obj_description(c.oid, 'pg_class'), ''), COALESCE(ts.spcname, '') " +
                        "FROM pg_class c " +
                        "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid " +
                        "LEFT JOIN pg_tablespace ts ON ts.oid = c.reltablespace " +
                        "WHERE n.nspname = ? AND c.relkind = 'r' ORDER BY c.relname").use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val rows = rs.getLong(2)
                    val estRows: Long? = if (rs.wasNull()) null else rows
                    tables.add(TableStat(rs.getString(1), estRows, rs.getLong(3),
                            rs.getString(4), rs.getString(5)))
                }
            }
        }
        return tables
    }

    @Throws(SQLException::class)
    override fun countTablesBySchema(conn: Connection): Map<String, Int> {
        return queryCountByGroup(conn,
                "SELECT n.nspname, COUNT(*) FROM pg_class c " +
                        "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE c.relkind = 'r' AND n.nspname NOT LIKE 'pg\\_%' " +
                        "AND n.nspname <> 'information_schema' GROUP BY n.nspname")
    }

    @Throws(SQLException::class)
    override fun sumSizeBySchema(conn: Connection): Map<String, Long> {
        return queryLongByGroup(conn,
                "SELECT n.nspname, SUM(pg_total_relation_size(c.oid)) FROM pg_class c " +
                        "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE c.relkind = 'r' AND n.nspname NOT LIKE 'pg\\_%' " +
                        "AND n.nspname <> 'information_schema' GROUP BY n.nspname")
    }

    /** PG 用 TABLESAMPLE 块级采样,速度快且近似随机 */
    override fun sampledFrom(qualifiedTable: String, sampleRows: Long, estRows: Long?): String {
        var percent = 10.0
        if (estRows != null && estRows > 0) {
            percent = minOf(100.0, maxOf(0.01, 100.0 * sampleRows / estRows))
        }
        return "(SELECT * FROM $qualifiedTable TABLESAMPLE SYSTEM ($percent)) AS dq_sample"
    }

    override fun sampledLimit(sampleRows: Long): String {
        return ""
    }
}
