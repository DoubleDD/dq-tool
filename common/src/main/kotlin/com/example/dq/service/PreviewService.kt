package com.example.dq.service

import com.example.dq.dialect.DialectFactory
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream
import java.sql.SQLException

/**
 * 表数据预览:字段明细页「数据预览」页签的只读分页查询。
 * 分页 SQL 走方言 pageRowsSql(LIMIT/OFFSET、SQL Server OFFSET/FETCH、Oracle ROWNUM/OFFSET FETCH);
 * 支持 DataGrip 风格的 WHERE / ORDER BY 用户原文过滤(本地单机工具,与 DataGrip 同口径原文透传;
 * 宽容处理:剥离用户误带的前导 WHERE/ORDER BY 关键字,空白视为未填);
 * 无 ORDER BY 时页间顺序不保证稳定;总数为 COUNT(*) 实时查询,驱动前端翻页;
 * 列结构取自 listColumns 元数据,显式列名查询保证列序与元数据一致;
 * 行值统一 getObject().toString(),NULL 保持 null;单元格截断 1000 字符防大字段撑爆响应。
 * exportTable:同过滤/排序口径的全量导出(SXSSF 流式写 xlsx),复用 pageRowsSql(offset=0,
 * limit=MAX_EXPORT_ROWS)取符合条件的前 20 万行,超上限静默截断;不做 COUNT(*)。
 */
class PreviewService(
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val systemSettingsService: SystemSettingsService,
) {

    data class PreviewColumn(val name: String, val type: String)

    data class PreviewResult(
        val columns: List<PreviewColumn>,
        val rows: List<List<String?>>,
        val total: Long,
        val page: Int,
        val size: Int,
    )

    /** 分页预览:第 page 页(1 起)每页 size 行,可按 where/orderBy 原文过滤排序;参数均有钳制 */
    @Throws(SQLException::class)
    fun previewTable(datasourceId: Long, database: String?, schema: String, table: String,
                     where: String?, orderBy: String?, page: Int?, size: Int?): PreviewResult {
        val p = (page ?: DEFAULT_PAGE).coerceAtLeast(1)
        val s = (size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val w = stripKeyword(where, "where")
        val o = stripKeyword(orderBy, "order by")
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            val columns = dialect.listColumns(conn, schema, table)
                .map { PreviewColumn(it.name, it.displayType) }
            conn.createStatement().use { stmt ->
                // 与分段扫描同口径的单条 SQL 超时(系统设置可改,回落配置文件默认值)
                stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                // 实时总数驱动前端分页(与数据查询同一过滤条件)
                val total = stmt.executeQuery(dialect.countRowsSql(schema, table, w)).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
                val rows = ArrayList<List<String?>>()
                stmt.executeQuery(
                    dialect.pageRowsSql(conn, schema, table, columns.map { it.name }, w, o, (p - 1) * s.toLong(), s)
                ).use { rs ->
                    val colCount = rs.metaData.columnCount
                    while (rs.next()) {
                        val row = ArrayList<String?>(colCount)
                        for (i in 1..colCount) {
                            row.add(truncate(rs.getObject(i)?.toString()))
                        }
                        rows.add(row)
                    }
                }
                return PreviewResult(columns, rows, total, p, s)
            }
        }
    }

    /**
     * 全量导出:按预览同一 where/orderBy 口径查询符合条件的前 MAX_EXPORT_ROWS 行(保持排序),
     * SXSSF 流式写单 sheet xlsx 到 out;表头与前端预览导出一致(列名+类型);
     * 行值序列化/截断同预览;不需要总数,省略 COUNT(*)。
     */
    @Throws(SQLException::class)
    fun exportTable(datasourceId: Long, database: String?, schema: String, table: String,
                    where: String?, orderBy: String?, out: OutputStream) {
        val w = stripKeyword(where, "where")
        val o = stripKeyword(orderBy, "order by")
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            val columns = dialect.listColumns(conn, schema, table)
                .map { PreviewColumn(it.name, it.displayType) }
            conn.createStatement().use { stmt ->
                // 与分段扫描同口径的单条 SQL 超时(系统设置可改,回落配置文件默认值)
                stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                stmt.executeQuery(
                    dialect.pageRowsSql(conn, schema, table, columns.map { it.name }, w, o, 0, MAX_EXPORT_ROWS)
                ).use { rs ->
                    SXSSFWorkbook(200).use { wb ->
                        val sheet = wb.createSheet("数据预览")
                        val head = sheet.createRow(0)
                        columns.forEachIndexed { i, c ->
                            head.createCell(i).setCellValue(if (c.type.isBlank()) c.name else "${c.name} ${c.type}")
                        }
                        var r = 1
                        val colCount = rs.metaData.columnCount
                        while (rs.next()) {
                            val row = sheet.createRow(r++)
                            for (i in 1..colCount) {
                                truncate(rs.getObject(i)?.toString())?.let { row.createCell(i - 1).setCellValue(it) }
                            }
                        }
                        wb.write(out)
                        wb.dispose()
                    }
                }
            }
        }
    }

    companion object {
        /** 默认页码与每页行数(请求参数钳制上限) */
        const val DEFAULT_PAGE = 1
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 200

        /** 单元格截断长度:防 CLOB/大字段撑爆预览响应 */
        const val MAX_CELL_CHARS = 1000

        /** 全量导出行数硬上限:超出静默截断(SQL limit 实现) */
        const val MAX_EXPORT_ROWS = 200_000

        /** 过滤/排序输入归一:trim、空白归 null、剥离误带的前导关键字(如用户把 "WHERE x=1" 整体粘进来) */
        internal fun stripKeyword(clause: String?, keyword: String): String? {
            val t = clause?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t.equals(keyword, ignoreCase = true)) return null
            return if (t.length > keyword.length && t.startsWith(keyword, ignoreCase = true) &&
                t[keyword.length].isWhitespace()
            ) {
                t.substring(keyword.length).trim().takeIf { it.isNotEmpty() }
            } else {
                t
            }
        }

        private fun truncate(s: String?): String? =
            if (s == null || s.length <= MAX_CELL_CHARS) s else s.substring(0, MAX_CELL_CHARS)
    }
}
