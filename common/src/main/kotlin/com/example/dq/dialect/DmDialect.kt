package com.example.dq.dialect

import com.example.dq.model.DbType
import com.example.dq.model.TableStat
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/**
 * 达梦 DM8 方言。
 * 表/库体积依赖段视图或系统函数:部分实例或受限账号看不到段视图(达梦对无权限对象同样报
 * 「无效的表或视图名」),按 all_segments → dba_segments → table_func(TABLE_USED_SPACE 系统函数)
 * → user_segments(仅当前用户)→ 不统计 逐级降级,探测结果按账号缓存,降级记 warn 日志
 */
class DmDialect : AbstractDialect() {

    companion object {
        private val log = LoggerFactory.getLogger(DmDialect::class.java)

        /** 体积来源非首选落点的重探间隔:权限可能后被授予/回收,到期从链头重新完整探测 */
        internal const val SIZE_SOURCE_REPROBE_MS = 3600_000L

        /**
         * 体积来源降级链(非视图来源用伪标识):
         * all_segments / dba_segments(全 schema)→ table_func(TABLE_USED_SPACE 函数,
         * 覆盖面取决于函数对非本账号表的可见性)→ user_segments(仅当前用户)→ 不统计(null 收尾)。
         * table_func 排在 user_segments 之前:受限账号下 user_segments 查询能成功但只覆盖本账号,
         * 会提前截断降级链,让其他 schema 的体积永远空白
         */
        internal fun sizeSourceChain(): List<String?> =
                listOf("all_segments", "dba_segments", "table_func", "user_segments", null)

        /**
         * 体积来源降级链起点决策:无缓存 / 已是首选 / 超过重探间隔 → 返回完整链(从头探测);
         * 否则直接从缓存落点开始,避免受限账号每次都先挨一发「无效的表或视图名」。
         * 返回的列表与传入 chain 引用相等时表示本次为从头探测(调用方据此刷新缓存)
         */
        internal fun sizeSourcePlan(chain: List<String?>, cached: SizeSourceChoice?, now: Long): List<String?> {
            if (cached == null) return chain
            val idx = chain.indexOf(cached.source)
            if (idx <= 0) return chain
            if (now - cached.probedAt >= SIZE_SOURCE_REPROBE_MS) return chain
            return chain.subList(idx, chain.size)
        }

        /**
         * 按体积来源生成表清单 SQL(总大小 = 表段 + 该表全部索引段;存储信息取表空间,注释取 ALL_TAB_COMMENTS)。
         * 段视图口径用「段按 owner+segment_name 聚合后 LEFT JOIN」而不是逐表相关子查询,与 Oracle 方言同口径:
         * 相关子查询对 all_tables 每一行都要跑一遍段视图(约 O(表数 × 段数)),聚合版每张段视图只扫一遍。
         *
         * @param source all_segments / dba_segments(全 schema)、table_func(TABLE_USED_SPACE 函数,
         *               口径为已用页 × 页大小,与段视图的分配字节口径有差异,仅作兜底)、
         *               user_segments(仅当前用户)、null(不统计大小)
         * @param tableNames 仅这些表(null/空 = 全 schema)
         */
        internal fun listTablesSql(source: String?, tableNames: Collection<String>? = null): String {
            val nameFilter = if (tableNames.isNullOrEmpty()) ""
            else " AND t.table_name IN (" + tableNames.joinToString(", ") { "?" } + ")"
            val header = "SELECT t.table_name, t.num_rows, "
            val tail = ", t.tablespace_name, NVL(c.comments, '') FROM all_tables t " +
                    "LEFT JOIN all_tab_comments c ON c.owner = t.owner AND c.table_name = t.table_name "
            val where = " WHERE t.owner = ?" + nameFilter + " ORDER BY t.table_name"
            if (source == null) {
                return header + "NULL" + tail + where
            }
            if (source == "table_func") {
                return header + "TABLE_USED_SPACE(t.owner, t.table_name) * PAGE()" + tail + where
            }
            if (source == "user_segments") {
                // user_segments 无 owner 列,只覆盖当前用户;其他 schema 的大小按未知(NULL)
                val sizeExpr = "CASE WHEN t.owner = USER THEN " +
                        "CASE WHEN ts.bytes IS NULL AND ix.bytes IS NULL THEN NULL " +
                        "ELSE NVL(ts.bytes, 0) + NVL(ix.bytes, 0) END END"
                val tableAgg = "LEFT JOIN (SELECT s.segment_name, SUM(s.bytes) AS bytes FROM user_segments s " +
                        "GROUP BY s.segment_name) ts ON ts.segment_name = t.table_name "
                val indexAgg = "LEFT JOIN (SELECT i.table_name, SUM(s.bytes) AS bytes FROM all_indexes i " +
                        "JOIN user_segments s ON s.segment_name = i.index_name " +
                        "WHERE i.owner = USER " +
                        "GROUP BY i.table_name) ix ON ix.table_name = t.table_name "
                return header + sizeExpr + tail + tableAgg + indexAgg + where
            }
            val sizeExpr = "CASE WHEN ts.bytes IS NULL AND ix.bytes IS NULL THEN NULL " +
                    "ELSE NVL(ts.bytes, 0) + NVL(ix.bytes, 0) END"
            val tableAgg = "LEFT JOIN (SELECT s.owner, s.segment_name, SUM(s.bytes) AS bytes FROM " +
                    source + " s GROUP BY s.owner, s.segment_name) ts " +
                    "ON ts.owner = t.owner AND ts.segment_name = t.table_name "
            val indexAgg = "LEFT JOIN (SELECT i.owner, i.table_name, SUM(s.bytes) AS bytes FROM all_indexes i " +
                    "JOIN " + source + " s ON s.owner = i.owner AND s.segment_name = i.index_name " +
                    "GROUP BY i.owner, i.table_name) ix ON ix.owner = t.owner AND ix.table_name = t.table_name "
            return header + sizeExpr + tail + tableAgg + indexAgg + where
        }

        /** 指定体积来源的库级体积聚合 SQL;user_segments 无 owner 列,只回当前用户一行 */
        internal fun sumSizeSql(source: String): String {
            if (source == "table_func") {
                return "SELECT t.owner, SUM(TABLE_USED_SPACE(t.owner, t.table_name)) * PAGE() " +
                        "FROM all_tables t GROUP BY t.owner"
            }
            if (source == "user_segments") {
                return "SELECT USER, (SELECT SUM(bytes) FROM user_segments) FROM dual"
            }
            return "SELECT owner, SUM(bytes) FROM " + source + " GROUP BY owner"
        }
    }

    /** 某账号已探明的可用体积来源(source=null 表示所有来源均不可用) */
    internal data class SizeSourceChoice(val source: String?, val probedAt: Long)

    /** 体积来源探测结果缓存(key = 用户名@JDBC URL):换账号/换服务器自动重新探测;进程内存级,重启自然重置 */
    internal val sizeSourceCache = ConcurrentHashMap<String, SizeSourceChoice>()

    @Throws(SQLException::class)
    private fun sizeSourceCacheKey(conn: Connection): String {
        val meta = conn.metaData
        return meta.userName + "@" + meta.url
    }

    /** 记录本次探测落点:从头探测过,或落点与缓存不一致(权限被收回而下移)时刷新 */
    @Throws(SQLException::class)
    private fun recordSizeSource(conn: Connection, fromTop: Boolean, source: String?) {
        val key = sizeSourceCacheKey(conn)
        if (fromTop || sizeSourceCache[key]?.source != source) {
            sizeSourceCache[key] = SizeSourceChoice(source, System.currentTimeMillis())
        }
    }

    override fun type(): DbType {
        return DbType.DM
    }

    override fun driverClassName(): String {
        return "dm.jdbc.driver.DmDriver"
    }

    /** 达梦兼容 Oracle 的 DBMS_METADATA.GET_DDL;权限不足时降级为元数据拼接 */
    @Throws(SQLException::class)
    override fun tableDdl(conn: Connection, schema: String, table: String): String {
        return dbmsMetadataDdl(conn, schema, table) ?: super.tableDdl(conn, schema, table)
    }

    /** DM8 系统账号(schema 与用户一一对应):SYS/SYSDBA/SYSAUDITOR/SYSSSO/SYSMAINT */
    override fun systemSchemas(): Set<String> {
        return setOf("sys", "sysdba", "sysauditor", "syssso", "sysmaint")
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

    /** DM8 兼容 Oracle 的 SYS_CONTEXT 取当前模式 */
    @Throws(SQLException::class)
    override fun currentSchema(conn: Connection): String? {
        return queryFirstString(conn, "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")
    }

    /** DM8 用 SET SCHEMA 切当前模式(2022 年后版本;老版本不支持时错误原样抛给前端) */
    @Throws(SQLException::class)
    override fun useSchema(conn: Connection, schema: String) {
        executeCommand(conn, "SET SCHEMA " + quote(schema))
    }

    @Throws(SQLException::class)
    override fun countTablesBySchema(conn: Connection): Map<String, Int> {
        return queryCountByGroup(conn, "SELECT owner, COUNT(*) FROM all_tables GROUP BY owner")
    }

    @Throws(SQLException::class)
    override fun sumSizeBySchema(conn: Connection): Map<String, Long> {
        // 体积来源探测结果按账号缓存,避免受限账号每次先挨一发「无效的表或视图名」
        val chain = sizeSourceChain()
        val sources = sizeSourcePlan(chain, sizeSourceCache[sizeSourceCacheKey(conn)], System.currentTimeMillis())
        val fromTop = sources === chain
        for (source in sources) {
            if (source == null) break
            try {
                val result = queryLongByGroup(conn, sumSizeSql(source))
                recordSizeSource(conn, fromTop, source)
                return result
            } catch (e: SQLException) {
                // 部分 DM 实例或受限账号没有/看不到段视图或系统函数,降级到下一环;
                // 达梦对视图不存在与无权限的报错口径随实例配置不一,这里不细分错误码一律降级
                log.warn("体积来源 {} 不可用,库列表体积统计降级: {}", source, e.message)
            }
        }
        recordSizeSource(conn, fromTop, null)
        log.warn("账号无任何体积来源可用,库列表体积统计降级为未知")
        return emptyMap()
    }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> =
        listTablesInternal(conn, schema, null)

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String, tableNames: Collection<String>): List<TableStat> =
        listTablesInternal(conn, schema, tableNames)

    private fun listTablesInternal(conn: Connection, schema: String, tableNames: Collection<String>?): List<TableStat> {
        // 体积来源降级链与 sumSizeBySchema 一致(探测结果按账号缓存),最后一环为不统计大小
        val chain = sizeSourceChain()
        val sources = sizeSourcePlan(chain, sizeSourceCache[sizeSourceCacheKey(conn)], System.currentTimeMillis())
        val fromTop = sources === chain
        for (source in sources) {
            if (source == null) {
                val result = queryTables(conn, schema, listTablesSql(null, tableNames), tableNames)
                recordSizeSource(conn, fromTop, null)
                return result
            }
            try {
                val result = queryTables(conn, schema, listTablesSql(source, tableNames), tableNames)
                recordSizeSource(conn, fromTop, source)
                return result
            } catch (e: SQLException) {
                // 同 sumSizeBySchema:不细分错误码一律降级到下一环
                log.warn("体积来源 {} 不可用,表列表大小统计降级: {}", source, e.message)
            }
        }
        throw IllegalStateException("unreachable")
    }

    @Throws(SQLException::class)
    private fun queryTables(conn: Connection, schema: String, sql: String,
                            tableNames: Collection<String>?): List<TableStat> {
        val tables = ArrayList<TableStat>()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            bindNames(ps, 2, tableNames)
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
