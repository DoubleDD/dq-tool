package com.example.dq.util

import com.example.dq.model.DbType

/**
 * JDBC URL 主机端口解析与改写(SSH 隧道本地端口转发用):
 * 解析出目标库真实 host:port 作为隧道远端,或把 URL 中的 host:port 替换为 127.0.0.1:本地转发端口。
 */
object JdbcUrlRewriter {

    /** URL 无法解析出主机端口时的统一报错 */
    private const val UNPARSEABLE = "无法从 JDBC URL 解析主机端口,SSH 隧道不可用"

    /** 常规形态:scheme://host(:port)/... 与 Oracle 服务名形态 @//host(:port)/service */
    private val NORMAL = Regex("""(?:@//|://)([^/;:?,]+)(?::(\d+))?""")

    /** Oracle SID 形态:@host:port:SID(端口必填) */
    private val ORACLE_SID = Regex("""@([^/;:?,]+):(\d+):[^;:?,]+""")

    /** 解析 JDBC URL 中的目标主机与端口;端口缺省时按库类型给默认端口,无法解析抛 IllegalArgumentException */
    fun extractHostPort(url: String): Pair<String, Int> {
        NORMAL.find(url)?.let { m ->
            val host = m.groupValues[1]
            val port = m.groupValues[2].toIntOrNull() ?: defaultPort(url)
            return host to port
        }
        ORACLE_SID.find(url)?.let { m ->
            return m.groupValues[1] to m.groupValues[2].toInt()
        }
        throw IllegalArgumentException(UNPARSEABLE)
    }

    /** 把 URL 中的 host:port 替换为 127.0.0.1:localPort(原缺省端口的显式补上),其余部分原样保留 */
    fun rewrite(url: String, localPort: Int): String {
        NORMAL.find(url)?.let { m ->
            val hostRange = m.groups[1]!!.range
            // 无端口组时替换区间止于 host 末尾,新串里把本地端口显式带上
            val replaceEnd = m.groups[2]?.let { it.range.last + 1 } ?: (hostRange.last + 1)
            return url.substring(0, hostRange.first) + "127.0.0.1:$localPort" + url.substring(replaceEnd)
        }
        ORACLE_SID.find(url)?.let { m ->
            val start = m.groups[1]!!.range.first
            val end = m.groups[2]!!.range.last + 1
            return url.substring(0, start) + "127.0.0.1:$localPort" + url.substring(end)
        }
        throw IllegalArgumentException(UNPARSEABLE)
    }

    /** 各库默认端口(端口缺省时回落;Oracle 服务名形态缺省也是 1521) */
    private fun defaultPort(url: String): Int = when (DbType.fromJdbcUrl(url)) {
        DbType.MYSQL -> 3306
        DbType.POSTGRESQL -> 5432
        DbType.SQLSERVER -> 1433
        DbType.ORACLE -> 1521
        DbType.DM -> 5236
        DbType.KINGBASE -> 54321
        DbType.OCEANBASE -> 2881
    }
}
