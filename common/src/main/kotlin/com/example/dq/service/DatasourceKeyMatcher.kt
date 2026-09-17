package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.util.JdbcUrlRewriter

/**
 * 数据源身份匹配(共享):按方言从 jdbcUrl 反解 host/port/库名,供两条批量导入链路共用——
 * - 抽样导出(SampleExportService):全量身份 key = type|host|port|username|database(含用户名);
 * - 比对批量导入(CompareImportService):定位 key = type|host|port|database(不含用户名,
 *   表格里用户名是可选项,只按「类型+地址+端口+库」匹配已建档数据源;类型取自表格「数据库类型」列)。
 * 反解不出(无法识别类型/解析失败/提取不到库名)一律返回 null:该数据源不参与去重匹配。
 */
object DatasourceKeyMatcher {

    /** jdbcUrl 反解结果:类型 + host + port + 库名(DM 无库名,恒空串) */
    data class JdbcAddress(val dbType: DbType, val host: String, val port: Int, val database: String)

    /** 从 jdbcUrl 反解连接地址;URL 无法识别/解析不出/提取不到库名返回 null */
    fun parseJdbcUrl(url: String?): JdbcAddress? {
        if (url.isNullOrBlank()) return null
        val type = try {
            DbType.fromJdbcUrl(url)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val (host, port) = try {
            JdbcUrlRewriter.extractHostPort(url)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val database = extractDatabase(type, url) ?: return null
        return JdbcAddress(type, host, port, database)
    }

    /** 已存数据源的连接地址(读 [DataSourceConfig.jdbcUrl]);解析不出返回 null */
    fun addressOf(ds: DataSourceConfig): JdbcAddress? = parseJdbcUrl(ds.jdbcUrl)

    /** 抽样导出口径身份 key:type|host小写|port|username或空|database;两侧(Excel 行/已存数据源)口径必须一致 */
    fun fullKey(dbType: DbType, host: String, port: Int, username: String?, database: String): String =
        "${dbType.name}|${host.trim().lowercase()}|$port|${username.orEmpty()}|$database"

    /** 已存数据源的全量身份 key(含类型与用户名);类型取 [DataSourceConfig.dbType],解析不出返回 null */
    fun fullKeyOf(ds: DataSourceConfig): String? {
        val (host, port, database) = hostPortDbOf(ds) ?: return null
        return fullKey(ds.dbType!!, host, port, ds.username, database)
    }

    /** 从 jdbcUrl + 用户名算全量身份 key(与 [fullKeyOf] 同口径);解析不出返回 null */
    fun fullKeyOfJdbcUrl(url: String?, username: String?): String? {
        val addr = parseJdbcUrl(url) ?: return null
        return fullKey(addr.dbType, addr.host, addr.port, username, addr.database)
    }

    /** 比对导入口径定位 key:type|host小写|port|database(含类型,不含用户名) */
    fun locationKey(dbType: DbType, host: String, port: Int, database: String): String =
        "${dbType.name}|${host.trim().lowercase()}|$port|$database"

    /** 已存数据源的定位 key(比对导入按「类型+地址+端口+库」匹配);类型/地址解析不出返回 null */
    fun locationKeyOf(ds: DataSourceConfig): String? {
        val type = ds.dbType ?: return null
        val (host, port, database) = hostPortDbOf(ds) ?: return null
        return locationKey(type, host, port, database)
    }

    /** 已存数据源的 (host, port, 库名):类型取 [DataSourceConfig.dbType],host/port 经 JdbcUrlRewriter 反解 */
    private fun hostPortDbOf(ds: DataSourceConfig): Triple<String, Int, String>? {
        val type = ds.dbType ?: return null
        val url = ds.jdbcUrl ?: return null
        val (host, port) = try {
            JdbcUrlRewriter.extractHostPort(url)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val database = extractDatabase(type, url) ?: return null
        return Triple(host, port, database)
    }

    /** 从 jdbcUrl 提取库名:普通形态取 `://h:p/` 后到 ? 前的路径段;sqlserver 取 databaseName 参数;oracle 取 @// 后服务名;dm 无库名恒空串 */
    private fun extractDatabase(type: DbType, url: String): String? = when (type) {
        DbType.DM -> ""
        DbType.SQLSERVER ->
            Regex("""databaseName=([^;]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        DbType.ORACLE ->
            Regex("""@//[^/]+/([^?;]+)""").find(url)?.groupValues?.get(1)
        else ->
            Regex("""://[^/]+/([^?;]+)""").find(url)?.groupValues?.get(1)
    }
}
