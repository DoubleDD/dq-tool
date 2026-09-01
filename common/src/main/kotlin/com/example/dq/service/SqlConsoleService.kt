package com.example.dq.service

import java.sql.SQLException

/**
 * SQL 控制台:对指定数据源执行用户输入的任意 SQL(JDBC 原文透传,内网单机工具不做语句限制),
 * 返回结果集(列名 + 行数据)或受影响行数。
 * 与 PreviewService 同一序列化约定:列名取 ResultSetMetaData.getColumnLabel,
 * 行值 getObject().toString()、NULL 保 null、单元格截断 1000 字符防大字段撑爆响应;
 * 单条 SQL 超时与分段扫描同口径(系统设置 statementTimeoutSeconds);
 * 结果集最多取 MAX_ROWS 行防内存/响应打爆,超出即停并标记 truncated。
 */
class SqlConsoleService(
    private val dataSourceService: DataSourceService,
    private val systemSettingsService: SystemSettingsService,
) {

    /** SQL 执行结果:query=true 表示结果集查询,否则为更新/DDL 语句 */
    data class SqlExecuteResult(
        /** 是否返回了结果集(true=查询,false=更新/DDL) */
        val query: Boolean,
        /** 结果集列名;非查询为空列表 */
        val columns: List<String>,
        /** 结果集行数据(值为字符串或 null);非查询为空列表 */
        val rows: List<List<String?>>,
        /** 实际返回的行数(上限 MAX_ROWS) */
        val total: Int,
        /** 结果集行数超出 MAX_ROWS,已截断 */
        val truncated: Boolean,
        /** 受影响行数(DDL 可能为 -1);查询语句固定 -1 */
        val updateCount: Int,
        /** 执行耗时(毫秒,含取连接) */
        val durationMs: Long,
    )

    /** 执行 SQL 原文;空白抛 IllegalArgumentException;连接/执行错误抛 SQLException(壳层统一 502) */
    @Throws(SQLException::class)
    fun execute(datasourceId: Long, sql: String): SqlExecuteResult {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("SQL 不能为空")
        }
        val start = System.currentTimeMillis()
        // getConnection 返回池化连接,use 块结束 close 即归还(与 PreviewService 同用法)
        dataSourceService.getConnection(datasourceId).use { conn ->
            conn.createStatement().use { stmt ->
                // 与分段扫描/数据预览同口径的单条 SQL 超时(系统设置可改,回落配置文件默认值)
                stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                val hasResultSet = stmt.execute(trimmed)
                if (hasResultSet) {
                    stmt.resultSet.use { rs ->
                        val meta = rs.metaData
                        val colCount = meta.columnCount
                        val columns = (1..colCount).map { meta.getColumnLabel(it) }
                        val rows = ArrayList<List<String?>>(MAX_ROWS)
                        var truncated = false
                        while (rs.next()) {
                            // 超出上限即停:剩余行丢弃,由 truncated 告知前端结果被截断
                            if (rows.size >= MAX_ROWS) {
                                truncated = true
                                break
                            }
                            val row = ArrayList<String?>(colCount)
                            for (i in 1..colCount) {
                                row.add(truncate(rs.getObject(i)?.toString()))
                            }
                            rows.add(row)
                        }
                        return SqlExecuteResult(
                            query = true, columns = columns, rows = rows, total = rows.size,
                            truncated = truncated, updateCount = -1,
                            durationMs = System.currentTimeMillis() - start,
                        )
                    }
                }
                return SqlExecuteResult(
                    query = false, columns = emptyList(), rows = emptyList(), total = 0,
                    truncated = false, updateCount = stmt.updateCount,
                    durationMs = System.currentTimeMillis() - start,
                )
            }
        }
    }

    companion object {
        /** 结果集最大返回行数:防大表无过滤查询把内存/响应打爆 */
        const val MAX_ROWS = 1000

        /** 单元格截断长度:防 CLOB/大字段撑爆响应(与 PreviewService 同口径) */
        const val MAX_CELL_CHARS = 1000

        private fun truncate(s: String?): String? =
            if (s == null || s.length <= MAX_CELL_CHARS) s else s.substring(0, MAX_CELL_CHARS)
    }
}
