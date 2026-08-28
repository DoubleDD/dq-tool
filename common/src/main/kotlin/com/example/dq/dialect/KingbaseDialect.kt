package com.example.dq.dialect

import com.example.dq.model.DbType

import java.sql.Connection
import java.sql.SQLException

/**
 * 人大金仓 KingbaseES 方言:基于 PG 内核,语法与元数据查询同 PostgreSQL。
 *
 * 多库方言:PG 协议连接绑定建连时的库、无法像 SQL Server 那样 USE 切库,
 * 因此按库分池时改写 URL 路径段([jdbcUrlForDatabase]);URL 未指定库时驱动按用户名当库名,
 * 无同名库即连接失败,[connectionUrlCandidates] 回落到内置维护库候选(kingbase/test/template1)。
 *
 * 注意:库过滤白名单对本方言作用于数据库层级(与 SQL Server 一致),schema 层级不过滤。
 */
class KingbaseDialect : PostgresDialect() {

    companion object {
        /** URL 未指定库时的维护库候选(按序尝试,首个连通者胜) */
        private val MAINTENANCE_DBS = listOf("kingbase", "test", "template1")

        /** jdbc:kingbase8://host(:port)(/db)(?params):1=前缀(到端口),2=路径段(含 /),3=查询串 */
        private val URL_PARTS = Regex("""^(jdbc:kingbase8://[^/?]+)(/[^?]*)?(\?.*)?$""")

        /** 取出 URL 路径段中的库名;无路径或仅有 "/" 返回 null */
        internal fun databaseOf(url: String): String? {
            val m = URL_PARTS.matchEntire(url) ?: return null
            return m.groupValues[2].removePrefix("/").takeIf { it.isNotEmpty() }
        }

        /** 把 URL 路径段替换/补齐为指定库名,保留查询串 */
        internal fun withDatabase(url: String, database: String): String {
            val m = URL_PARTS.matchEntire(url)
                ?: throw IllegalArgumentException("无法解析 Kingbase JDBC URL: $url")
            return m.groupValues[1] + "/" + database + m.groupValues[3]
        }
    }

    override fun type(): DbType {
        return DbType.KINGBASE
    }

    override fun driverClassName(): String {
        return "com.kingbase8.Driver"
    }

    override fun supportsMultiDatabase(): Boolean = true

    /** 在线可连的非模板库(template0/1 不出现在列表;datallowconn=false 的库连不上也没有展示意义) */
    @Throws(SQLException::class)
    override fun listDatabases(conn: Connection): List<String> {
        val databases = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                    "SELECT datname FROM pg_database " +
                            "WHERE NOT datistemplate AND datallowconn ORDER BY datname").use { rs ->
                while (rs.next()) {
                    databases.add(rs.getString(1))
                }
            }
        }
        return databases
    }

    /** 多库方言的白名单作用于数据库层级:此处为金仓内置维护库(template 系本就不在列表,列出仅为标注兜底) */
    override fun systemSchemas(): Set<String> {
        return setOf("kingbase", "test", "security", "template0", "template1")
    }

    /** PG 协议无法切库:按库分池的 URL 直接指向目标库 */
    override fun jdbcUrlForDatabase(baseUrl: String, database: String): String {
        return withDatabase(baseUrl, database)
    }

    /** URL 已指定库时原样;未指定时依次回落到维护库候选(首个连通者由调用方决定) */
    override fun connectionUrlCandidates(url: String): List<String> {
        if (databaseOf(url) != null) {
            return listOf(url)
        }
        return listOf(url) + MAINTENANCE_DBS.map { withDatabase(url, it) }
    }

    /** 建库时选定的兼容模式:pg / oracle / mysql */
    @Throws(SQLException::class)
    override fun detectDbMode(conn: Connection): String? {
        conn.createStatement().use { st ->
            st.executeQuery("show database_mode").use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }
}
