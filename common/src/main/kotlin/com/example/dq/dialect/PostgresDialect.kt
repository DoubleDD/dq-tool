package com.example.dq.dialect

import com.example.dq.model.DbType
import com.example.dq.model.TableStat

import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import kotlin.math.ceil

/** PostgreSQL 方言 */
open class PostgresDialect : AbstractDialect() {

    companion object {
        /**
         * 解析 SHOW statement_timeout 的返回值("0" / "5000ms" / "30s" / "1min 30s" / "1h")为秒;
         * 无法解析返回 null(调用方按无限制处理);纯函数便于单测(金仓/瀚高继承同口径)
         */
        internal fun parsePgTimeoutSeconds(raw: String?): Int? {
            if (raw == null) return null
            val s = raw.trim()
            if (s == "0") return 0
            var total = 0.0
            var matched = false
            for (m in Regex("""(\d+(?:\.\d+)?)\s*(ms|s|min|h)""").findAll(s)) {
                matched = true
                val v = m.groupValues[1].toDoubleOrNull() ?: return null
                total += when (m.groupValues[2]) {
                    "ms" -> v / 1000.0
                    "s" -> v
                    "min" -> v * 60
                    "h" -> v * 3600
                    else -> 0.0
                }
            }
            // 亚秒上限按 1s 计(保守);完全解析不出按「未知」交回调用方
            return if (matched) maxOf(1, ceil(total).toInt()) else null
        }
    }

    override fun type(): DbType {
        return DbType.POSTGRESQL
    }

    override fun driverClassName(): String {
        return "org.postgresql.Driver"
    }

    /** listSchemas 已排除 pg_ 前缀与 information_schema,此处仅为库过滤标注兜底 */
    override fun systemSchemas(): Set<String> {
        return setOf("pg_catalog", "information_schema", "pg_toast")
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

    /** PG 的当前 schema 由 search_path 决定(瀚高继承同口径) */
    @Throws(SQLException::class)
    override fun currentSchema(conn: Connection): String? {
        return queryFirstString(conn, "SELECT current_schema()")
    }

    /** PG 系驱动仅在 autocommit=false + fetchSize>0 时走服务端游标,否则整表缓冲进客户端内存 */
    override fun configureStreamingRead(conn: Connection, stmt: Statement) {
        conn.autoCommit = false
        stmt.fetchSize = 1000
    }

    /** PG 系服务端语句上限:statement_timeout(USERSET,本会话可放宽;金仓/瀚高继承同口径) */
    override fun probeServerStatementLimitSeconds(conn: Connection): Int? {
        return try {
            conn.createStatement().use { st ->
                try {
                    st.execute("SET statement_timeout = 0")
                } catch (e: SQLException) {
                    // 放宽失败不致命,下面的 SHOW 读到的是仍生效的值
                }
                st.executeQuery("SHOW statement_timeout").use { rs ->
                    if (!rs.next()) return null
                    parsePgTimeoutSeconds(rs.getString(1))?.takeIf { it > 0 }
                }
            }
        } catch (e: Exception) {
            // 探测失败按无限制走流式,流式真被杀还有分页降级兜底
            null
        }
    }

    @Throws(SQLException::class)
    override fun useSchema(conn: Connection, schema: String) {
        executeCommand(conn, "SET search_path TO " + quote(schema))
    }

    /** PG 系(含金仓/瀚高):connectTimeout/socketTimeout 均以秒为单位 */
    @Throws(SQLException::class)
    override fun connectionTimeoutProperties(connectTimeoutMs: Int, readTimeoutMs: Int): Map<String, String> =
        buildMap {
            if (connectTimeoutMs > 0) put("connectTimeout", ((connectTimeoutMs + 999) / 1000).toString())
            if (readTimeoutMs > 0) put("socketTimeout", ((readTimeoutMs + 999) / 1000).toString())
        }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> =
        queryTables(conn, schema, null)

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String, tableNames: Collection<String>): List<TableStat> =
        queryTables(conn, schema, tableNames)

    private fun queryTables(conn: Connection, schema: String, tableNames: Collection<String>?): List<TableStat> {
        val tables = ArrayList<TableStat>()
        // 注释取 pg_class 的描述,存储信息取表空间(默认表空间显示为空)
        val sql = "SELECT c.relname, s.n_live_tup, pg_total_relation_size(c.oid), " +
                "COALESCE(obj_description(c.oid, 'pg_class'), ''), COALESCE(ts.spcname, '') " +
                "FROM pg_class c " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid " +
                "LEFT JOIN pg_tablespace ts ON ts.oid = c.reltablespace " +
                "WHERE n.nspname = ? AND c.relkind = 'r'" +
                tableNameInClause("c.relname", tableNames) +
                " ORDER BY c.relname"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            bindNames(ps, 2, tableNames)
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
