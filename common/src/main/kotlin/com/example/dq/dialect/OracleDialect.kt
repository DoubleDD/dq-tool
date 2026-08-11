package com.example.dq.dialect

import com.example.dq.model.DbType
import com.example.dq.model.TableStat

import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/**
 * Oracle 方言。
 * 注意:Oracle 把空字符串存为 NULL,空串统计恒为 0,NULL 数已覆盖。
 * 按主版本号选择语法特性:
 * - 分页:12c 起 OFFSET/FETCH;11g 用 ROWNUM 包装
 * - 表大小:23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在);受限账号看不到段视图时
 *   (无权限对象 Oracle 同样报 ORA-00942)按 all → dba → user(仅当前用户)→ 不统计 逐级降级,记 warn 日志
 */
class OracleDialect : AbstractDialect() {

    companion object {
        private val log = LoggerFactory.getLogger(OracleDialect::class.java)

        /** Oracle 主版本号(11/12/19/21/23),决定可用语法特性 */
        @Throws(SQLException::class)
        internal fun oracleMajor(conn: Connection): Int {
            return conn.metaData.databaseMajorVersion
        }

        /** ORA-00942:表或视图不存在(Oracle 对无权限访问的对象也报 942) */
        private fun isMissingObject(e: SQLException): Boolean = e.errorCode == 942

        /** 段视图非首选落点的重探间隔:权限可能后被授予/回收,到期从链头重新完整探测 */
        internal const val SEG_VIEW_REPROBE_MS = 3600_000L

        /**
         * 段视图降级链起点决策:无缓存 / 已是首选 / 超过重探间隔 → 返回完整链(从头探测);
         * 否则直接从缓存落点开始,避免每次都先挨一发 ORA-00942。
         * 返回的列表与传入 chain 引用相等时表示本次为从头探测(调用方据此刷新缓存)
         */
        internal fun segmentViewPlan(chain: List<String?>, cached: SegViewChoice?, now: Long): List<String?> {
            if (cached == null) return chain
            val idx = chain.indexOf(cached.view)
            if (idx <= 0) return chain
            if (now - cached.probedAt >= SEG_VIEW_REPROBE_MS) return chain
            return chain.subList(idx, chain.size)
        }

        /** 段视图降级链(按版本):低版本 all → dba → user;23ai 起 ALL_SEGMENTS 被移除(DBA_SEGMENTS 仍在) */
        internal fun segmentViewChain(major: Int): List<String?> {
            return if (major >= 23)
                listOf("dba_segments", "user_segments", null)
            else
                listOf("all_segments", "dba_segments", "user_segments", null)
        }

        /**
         * 按段视图来源生成表清单 SQL。
         * @param segView all_segments / dba_segments(全 schema)、user_segments(仅当前用户)、null(不统计大小)
         */
        internal fun listTablesSql(segView: String?): String {
            val sizeExpr = when (segView) {
                null -> "NULL"
                "user_segments" ->
                    "CASE WHEN t.owner = SYS_CONTEXT('USERENV','SESSION_USER') THEN " +
                    "(SELECT SUM(s.bytes) FROM user_segments s " +
                    "  WHERE s.segment_name = t.table_name OR s.segment_name IN " +
                    "    (SELECT i.index_name FROM all_indexes i WHERE i.owner = t.owner AND i.table_name = t.table_name)) END"
                else ->
                    "(SELECT SUM(s.bytes) FROM " + segView + " s WHERE s.owner = t.owner " +
                    "  AND (s.segment_name = t.table_name OR s.segment_name IN " +
                    "    (SELECT i.index_name FROM all_indexes i WHERE i.owner = t.owner AND i.table_name = t.table_name)))"
            }
            return "SELECT t.table_name, t.num_rows, " + sizeExpr + ", t.tablespace_name, " +
                    "(SELECT c.comments FROM all_tab_comments c WHERE c.owner = t.owner AND c.table_name = t.table_name) " +
                    "FROM all_tables t WHERE t.owner = ? ORDER BY t.table_name"
        }

        /**
         * 23ai 起 ALL_SEGMENTS 被移除;低版本仍用 ALL_SEGMENTS(可统计任意 schema)。
         * withSize=false 时不统计大小(受限账号所有段视图均不可见时的降级)
         */
        internal fun listTablesSql(major: Int, withSize: Boolean = true): String {
            if (!withSize) return listTablesSql(null)
            return listTablesSql(if (major >= 23) "user_segments" else "all_segments")
        }

        /** 指定段视图的体积聚合 SQL;user_segments 无 owner 列,只回当前用户一行 */
        internal fun sumSizeSql(segView: String): String {
            if (segView == "user_segments") {
                return "SELECT SYS_CONTEXT('USERENV','SESSION_USER'), " +
                        "(SELECT SUM(bytes) FROM user_segments) FROM dual"
            }
            return "SELECT owner, SUM(bytes) FROM " + segView + " GROUP BY owner"
        }
    }

    /** 某账号已探明的可用段视图(view=null 表示所有段视图均不可见) */
    internal data class SegViewChoice(val view: String?, val probedAt: Long)

    /** 段视图探测结果缓存(key = 用户名@JDBC URL):换账号/换服务器自动重新探测;进程内存级,重启自然重置 */
    internal val segViewCache = ConcurrentHashMap<String, SegViewChoice>()

    @Throws(SQLException::class)
    private fun segViewCacheKey(conn: Connection): String {
        val meta = conn.metaData
        return meta.userName + "@" + meta.url
    }

    /** 记录本次探测落点:从头探测过,或落点与缓存不一致(权限被收回而下移)时刷新 */
    @Throws(SQLException::class)
    private fun recordSegView(conn: Connection, fromTop: Boolean, view: String?) {
        val key = segViewCacheKey(conn)
        if (fromTop || segViewCache[key]?.view != view) {
            segViewCache[key] = SegViewChoice(view, System.currentTimeMillis())
        }
    }

    override fun type(): DbType {
        return DbType.ORACLE
    }

    override fun driverClassName(): String {
        return "oracle.jdbc.OracleDriver"
    }

    override fun quote(identifier: String): String {
        return "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    @Throws(SQLException::class)
    override fun listSchemas(conn: Connection): List<String> {
        // Oracle 的 schema 与用户一一对应
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
        // 段视图降级链探测结果按账号缓存,避免受限账号每次先挨一发 ORA-00942
        val chain = segmentViewChain(oracleMajor(conn))
        val views = segmentViewPlan(chain, segViewCache[segViewCacheKey(conn)], System.currentTimeMillis())
        val fromTop = views === chain
        for (view in views) {
            if (view == null) break
            try {
                val result = queryLongByGroup(conn, sumSizeSql(view))
                recordSegView(conn, fromTop, view)
                return result
            } catch (e: SQLException) {
                if (!isMissingObject(e)) throw e
                // 受限账号看不到段视图(Oracle 对无权限对象也报 ORA-00942),降级到下一环
                log.warn("段视图 {} 不可访问,库列表体积统计降级: {}", view, e.message)
            }
        }
        recordSegView(conn, fromTop, null)
        log.warn("账号无任何段视图访问权,库列表体积统计降级为未知")
        return emptyMap()
    }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> {
        // 总大小 = 表段 + 该表全部索引段;存储信息取表空间,注释取 ALL_TAB_COMMENTS
        // 段视图降级链与 sumSizeBySchema 一致(探测结果按账号缓存),最后一环为不统计大小
        val chain = segmentViewChain(oracleMajor(conn))
        val views = segmentViewPlan(chain, segViewCache[segViewCacheKey(conn)], System.currentTimeMillis())
        val fromTop = views === chain
        for (view in views) {
            if (view == null) {
                val result = queryTables(conn, schema, listTablesSql(null))
                recordSegView(conn, fromTop, null)
                return result
            }
            try {
                val result = queryTables(conn, schema, listTablesSql(view))
                recordSegView(conn, fromTop, view)
                return result
            } catch (e: SQLException) {
                if (!isMissingObject(e)) throw e
                log.warn("段视图 {} 不可访问,表列表大小统计降级: {}", view, e.message)
            }
        }
        throw IllegalStateException("unreachable")
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
                    val comment = rs.getString(5)
                    tables.add(TableStat(rs.getString(1), estRows, sizeBytes,
                            comment ?: "", rs.getString(4)))
                }
            }
        }
        return tables
    }

    @Throws(SQLException::class)
    override fun boundaryQuery(conn: Connection, qTable: String, qKey: String,
                               prev: String?, offset: Long): String {
        return boundaryQuerySql(qTable, qKey, prev, offset, oracleMajor(conn))
    }

    /** 12c 起 OFFSET/FETCH;11g 用 ROWNUM 双层包装取第 offset 行。prev 非空时为 seek 定位(见 AbstractDialect.boundaryQuery) */
    internal fun boundaryQuerySql(qTable: String, qKey: String, prev: String?, offset: Long, major: Int): String {
        val seek = if (prev == null) "" else " AND " + qKey + " > " + quoteString(prev)
        if (major >= 12) {
            return "SELECT " + qKey + " FROM " + qTable +
                    " WHERE " + qKey + " IS NOT NULL" + seek +
                    " ORDER BY " + qKey +
                    boundarySuffix(offset)
        }
        return "SELECT " + qKey + " FROM (SELECT ROWNUM dq_rn, dq_inner.* FROM (SELECT " + qKey +
                " FROM " + qTable + " WHERE " + qKey + " IS NOT NULL" + seek + " ORDER BY " + qKey + ") dq_inner" +
                " WHERE ROWNUM <= " + (offset + 1) + ") WHERE dq_rn = " + (offset + 1)
    }

    @Throws(SQLException::class)
    override fun nullChunkProbeQuery(conn: Connection, qTable: String, qKey: String): String {
        return nullChunkProbeSql(qTable, qKey, oracleMajor(conn))
    }

    /** 12c 起 FETCH FIRST;11g 用 ROWNUM */
    internal fun nullChunkProbeSql(qTable: String, qKey: String, major: Int): String {
        if (major >= 12) {
            return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL" +
                    " ORDER BY " + qKey + limitClause(1)
        }
        return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL AND ROWNUM <= 1"
    }

    override fun limitClause(n: Long): String {
        return " FETCH FIRST $n ROWS ONLY"
    }

    override fun boundarySuffix(offset: Long): String {
        return " OFFSET $offset ROWS FETCH NEXT 1 ROWS ONLY"
    }

    /** Oracle 原生 SAMPLE BLOCK 块采样 */
    override fun sampledFrom(qualifiedTable: String, sampleRows: Long, estRows: Long?): String {
        var percent = 10.0
        if (estRows != null && estRows > 0) {
            percent = minOf(100.0, maxOf(0.000001, 100.0 * sampleRows / estRows))
        }
        return "$qualifiedTable SAMPLE BLOCK ($percent)"
    }

    override fun sampledLimit(sampleRows: Long): String {
        return ""
    }

    /** ROWNUM 过滤全版本可用;limitClause 的 FETCH FIRST 仅 12c+,此处无连接可探测版本 */
    override fun sampleRowsSql(schema: String, table: String, columns: List<String>, limit: Int): String {
        val cols = if (columns.isEmpty()) "*" else columns.joinToString(", ") { quote(it) }
        return "SELECT $cols FROM " + qualifiedTable(schema, table) + " WHERE ROWNUM <= $limit"
    }
}
