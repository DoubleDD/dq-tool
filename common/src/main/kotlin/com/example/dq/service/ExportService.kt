package com.example.dq.service

import com.example.dq.model.NullRule
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import com.example.dq.repository.TableDocRepository
import com.example.dq.util.ExcelCells
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.IOException
import java.io.OutputStream
import java.time.format.DateTimeFormatter

/** 扫描结果导出 xlsx(SXSSF 流式写,大结果集不占内存) */
class ExportService(
    private val scanService: ScanService,
    private val tableDocRepository: TableDocRepository,
) {

    /** 带 key 的列定义,供导出预览按 key 选择要导出的列 */
    private interface Keyed {
        val key: String
        val header: String
    }

    /** 字段明细 sheet 的可选列定义;"英文表名/中文表名/字段"为固定前列,不参与选择 */
    private data class Col(
        override val key: String,
        override val header: String,
        val value: (ScanColumnView) -> Any?,
    ) : Keyed

    /** 表列表 sheet 的可选列定义;"英文表名"列恒为第一列,不参与选择;value 第二参为该表字段平均有值率,第三参为表名 -> AI 表描述 */
    private data class TCol(
        override val key: String,
        override val header: String,
        val value: (ScanTableView, Double, Map<String, String>) -> Any?,
    ) : Keyed

    @Throws(IOException::class)
    fun export(jobId: Long, out: OutputStream) {
        export(jobId, null, null, out)
    }

    /**
     * 导出扫描结果。
     *
     * @param tableCols 表列表 sheet 要导出的列 key(见 TABLE_DEFS);null = 全部列,空集 = 只要固定首列
     * @param cols      字段明细/字段汇总 sheet 要导出的列 key(见 COLUMN_DEFS);null = 全部列,空集 = 只要固定列(英文表名/中文表名/字段)
     */
    @Throws(IOException::class)
    fun export(jobId: Long, tableCols: List<String>?, cols: List<String>?, out: OutputStream) {
        val job = scanService.getJob(jobId)
        val tables = job.tables ?: emptyList()
        // AI 表描述:按数据源+库+schema 一次性取出,无库概念的方言 db 落空串(与 TableDocService 一致)
        val docs = tableDocRepository.findBySchema(job.datasourceId, job.dbName ?: "", job.schemaName ?: "")
        val colsOf: (ScanTableView) -> List<ScanColumnView> = { scanService.getColumns(job.id, it.tableName!!) }
        SXSSFWorkbook(200).use { wb ->
            writeOverview(wb, job, tables, colsOf)
            writeTables(wb, tables, docs, tableCols, colsOf)
            writeAllColumns(wb, tables, cols, colsOf)
            writeColumns(wb, tables, cols, colsOf)
            writeFailed(wb, tables)
            wb.write(out)
            wb.dispose()
        }
    }

    /**
     * 最新扫描结果导出:每表取最近一次表级 DONE 的快照(跨任务,不依赖指定任务记录),
     * 无任何 DONE 数据时抛 IllegalStateException(server 映射 409)。
     * 与任务导出的差异:概览为最新口径文案,且不生成「异常表」sheet(口径内的表全是 DONE)。
     */
    @Throws(IOException::class)
    fun exportLatest(datasourceId: Long, dbName: String?, schemaName: String,
                     tableCols: List<String>?, cols: List<String>?, out: OutputStream) {
        val tables = scanService.latestDoneTables(datasourceId, dbName, schemaName)
        if (tables.isEmpty()) throw IllegalStateException("该库还没有已完成的扫描数据,请先扫描")
        val docs = tableDocRepository.findBySchema(datasourceId, dbName ?: "", schemaName)
        val colsOf: (ScanTableView) -> List<ScanColumnView> = { scanService.getColumns(it.id) }
        SXSSFWorkbook(200).use { wb ->
            writeLatestOverview(wb, tables, datasourceId, dbName, schemaName, colsOf)
            writeTables(wb, tables, docs, tableCols, colsOf)
            writeAllColumns(wb, tables, cols, colsOf)
            writeColumns(wb, tables, cols, colsOf)
            wb.write(out)
            wb.dispose()
        }
    }

    private fun writeOverview(wb: SXSSFWorkbook, job: ScanJobView, tables: List<ScanTableView>,
                              colsOf: (ScanTableView) -> List<ScanColumnView>) {
        val sheet = wb.createSheet("概览")
        var r = 0
        r = kv(sheet, r, "数据源", nullSafe(job.datasourceName))
        r = kv(sheet, r, "库/Schema", job.schemaName)
        r = kv(sheet, r, "状态", job.status!!.name)
        r = kv(sheet, r, "强制全量", if (job.forceFull) "是" else "否")
        r = kv(sheet, r, "空值规则", rulesText(job.nullRules))
        r = kv(sheet, r, "开始时间", if (job.startedAt != null) FMT.format(job.startedAt) else "")
        r = kv(sheet, r, "结束时间", if (job.finishedAt != null) FMT.format(job.finishedAt) else "")
        r++
        writeSummary(sheet, r, tables, colsOf)
    }

    /** 最新结果导出的概览:数据源/库·Schema/数据口径说明/最晚扫描完成时间 + 统计总结(口径内的表全为 DONE) */
    private fun writeLatestOverview(wb: SXSSFWorkbook, tables: List<ScanTableView>,
                                    datasourceId: Long, dbName: String?, schemaName: String,
                                    colsOf: (ScanTableView) -> List<ScanColumnView>) {
        val sheet = wb.createSheet("概览")
        var r = 0
        r = kv(sheet, r, "数据源", nullSafe(scanService.datasourceName(datasourceId)))
        r = kv(sheet, r, "库/Schema", if (dbName.isNullOrBlank()) schemaName else "$dbName / $schemaName")
        r = kv(sheet, r, "数据口径", "各表最近一次已完成扫描的快照,可能来自不同任务")
        val latestFinished = tables.mapNotNull { it.finishedAt }.maxOrNull()
        r = kv(sheet, r, "最晚扫描完成时间", if (latestFinished != null) FMT.format(latestFinished) else "")
        r++
        writeSummary(sheet, r, tables, colsOf)
    }

    /** 统计总结:表/字段规模、空表空字段、总行数、占用空间(空表/空字段口径与前端一致:0 行 / 有值数为 0) */
    private fun writeSummary(sheet: Sheet, r0: Int, tables: List<ScanTableView>,
                             colsOf: (ScanTableView) -> List<ScanColumnView>) {
        var r = r0
        var done = 0L
        var failed = 0L
        var emptyTables = 0
        var fieldTotal = 0
        var emptyFields = 0
        var totalRows = 0L
        var sizeBytes = 0L
        var anySampled = false
        for (t in tables) {
            if (t.status == ScanStatus.DONE) {
                done++
            } else if (t.status == ScanStatus.FAILED) {
                failed++
            }
            val cols = colsOf(t)
            fieldTotal += cols.size
            emptyFields += cols.count { it.valueCount == 0L }
            val tableRows = t.totalRows
            if (tableRows != null) {
                totalRows += tableRows
                if (tableRows == 0L) {
                    emptyTables++
                }
            } else {
                totalRows += t.scannedRows
            }
            if (t.sizeBytes != null) {
                sizeBytes += t.sizeBytes
            }
            anySampled = anySampled || t.sampled
        }
        sheet.createRow(r++).createCell(0).setCellValue("统计总结")
        r = kv(sheet, r, "统计表数", String.format("%,d(完成 %,d,失败 %,d)", tables.size.toLong(), done, failed))
        r = kv(sheet, r, "空表数(0 行)", String.format("%,d", emptyTables))
        r = kv(sheet, r, "空表率", percent(emptyTables, tables.size))
        r = kv(sheet, r, "字段总数", String.format("%,d", fieldTotal))
        r = kv(sheet, r, "空字段数(有值数为 0)", String.format("%,d", emptyFields))
        r = kv(sheet, r, "空字段率", percent(emptyFields, fieldTotal))
        r = kv(sheet, r, "总数据行数", String.format("%,d", totalRows) + if (anySampled) "(含采样估算)" else "")
        kv(sheet, r, "总占用空间", formatBytes(sizeBytes))
    }

    /** 百分比(保留两位小数),分母为 0 时返回 "-" */
    private fun percent(part: Int, total: Int): String =
        if (total > 0) String.format("%.2f%%", part * 100.0 / total) else "-"

    private fun writeTables(wb: SXSSFWorkbook, tables: List<ScanTableView>, docs: Map<String, String>,
                            tableCols: List<String>?, colsOf: (ScanTableView) -> List<ScanColumnView>) {
        val selected = selectCols(TABLE_DEFS, tableCols)
        val sheet = wb.createSheet("表列表")
        writeHeader(sheet.createRow(0), selected, "英文表名")
        var r = 1
        for (t in tables) {
            val cols = colsOf(t)
            val avgRate = if (cols.isEmpty()) 0.0 else cols.map { it.fillRate }.average()
            val row = sheet.createRow(r++)
            var c = 0
            row.createCell(c++).setCellValue(t.tableName)
            for (def in selected) {
                cell(row.createCell(c++), def.value(t, avgRate, docs))
            }
        }
    }

    private fun writeColumns(wb: SXSSFWorkbook, tables: List<ScanTableView>, cols: List<String>?,
                             colsOf: (ScanTableView) -> List<ScanColumnView>) {
        val selected = selectCols(COLUMN_DEFS, cols)
        // 预留后续 sheet 名,防止某张表恰好叫「字段汇总」/「异常表」导致 createSheet 重名抛异常
        val usedNames = HashSet(listOf("字段汇总", "异常表"))
        for (t in tables) {
            if (t.status != ScanStatus.DONE) {
                continue
            }
            val sheet = wb.createSheet(sheetName(t.tableName!!, usedNames))
            writeHeader(sheet.createRow(0), selected, "英文表名", "中文表名", "字段")
            writeColumnRows(sheet, 1, t, selected, colsOf)
        }
    }

    /** 字段汇总:所有 DONE 表的字段合并到同一个 sheet,行结构与单表字段明细完全相同 */
    private fun writeAllColumns(wb: SXSSFWorkbook, tables: List<ScanTableView>, cols: List<String>?,
                                colsOf: (ScanTableView) -> List<ScanColumnView>) {
        val selected = selectCols(COLUMN_DEFS, cols)
        val sheet = wb.createSheet("字段汇总")
        writeHeader(sheet.createRow(0), selected, "英文表名", "中文表名", "字段")
        var r = 1
        for (t in tables) {
            if (t.status != ScanStatus.DONE) {
                continue
            }
            r = writeColumnRows(sheet, r, t, selected, colsOf)
        }
    }

    /** 把单张表的字段行写入 sheet(固定前列 英文表名/中文表名/字段 + 选中的可选列),返回下一个可用行号 */
    private fun writeColumnRows(sheet: Sheet, r0: Int, t: ScanTableView, selected: List<Col>,
                                colsOf: (ScanTableView) -> List<ScanColumnView>): Int {
        var r = r0
        val tableName = t.tableName!!
        for (col in colsOf(t)) {
            val row = sheet.createRow(r++)
            var c = 0
            row.createCell(c++).setCellValue(tableName)
            row.createCell(c++).setCellValue(nullSafe(t.comment))
            row.createCell(c++).setCellValue(col.columnName)
            for (def in selected) {
                cell(row.createCell(c++), def.value(col))
            }
        }
        return r
    }

    private fun writeFailed(wb: SXSSFWorkbook, tables: List<ScanTableView>) {
        val failed = tables.filter { it.status == ScanStatus.FAILED }
        if (failed.isEmpty()) {
            return
        }
        val sheet = wb.createSheet("异常表")
        header(sheet.createRow(0), "表名", "错误信息")
        var r = 1
        for (t in failed) {
            val row = sheet.createRow(r++)
            row.createCell(0).setCellValue(t.tableName)
            row.createCell(1).setCellValue(nullSafe(t.error))
        }
    }

    private fun kv(sheet: Sheet, r: Int, k: String, v: String?): Int {
        val row = sheet.createRow(r)
        row.createCell(0).setCellValue(k)
        row.createCell(1).setCellValue(v)
        return r + 1
    }

    private fun header(row: Row, vararg names: String) {
        for (i in names.indices) {
            row.createCell(i).setCellValue(names[i])
        }
    }

    private fun rulesText(rules: List<NullRule>?): String {
        if (rules.isNullOrEmpty()) {
            return "默认(NULL + 空字符串)"
        }
        return "默认(NULL + 空字符串) + " + rules.joinToString("; ") { r ->
            r.column + " IN (" + r.values.orEmpty().joinToString(",") + ")"
        }
    }

    private companion object {
        val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val COLUMN_DEFS: List<Col> = listOf(
            Col("comment", "注释") { nullSafe(it.columnComment) },
            Col("type", "类型") { nullSafe(it.columnType) },
            Col("key", "键") { nullSafe(it.keyLabel) },
            Col("nullable", "可空") { c -> if (c.nullable == null) "" else if (c.nullable) "是" else "否" },
            Col("default", "默认值") { nullSafe(it.defaultValue) },
            Col("totalRows", "总行数", ScanColumnView::totalRows),
            Col("nullCount", "NULL数", ScanColumnView::nullCount),
            Col("emptyCount", "空串数", ScanColumnView::emptyCount),
            Col("ruleHitCount", "规则命中数", ScanColumnView::ruleHitCount),
            Col("valueCount", "有值数", ScanColumnView::valueCount),
            Col("fillRate", "有值率%") { round2(it.fillRate) },
        )

        val TABLE_DEFS: List<TCol> = listOf(
            TCol("comment", "中文表名") { t, _, _ -> nullSafe(t.comment) },
            TCol("description", "表描述") { t, _, docs -> nullSafe(docs[t.tableName]) },
            TCol("storage", "引擎/表空间") { t, _, _ -> nullSafe(t.storageInfo) },
            TCol("totalRows", "总行数") { t, _, _ -> t.totalRows ?: "" },
            TCol("sampled", "是否采样") { t, _, _ -> if (t.sampled) "是(估算)" else "否" },
            TCol("sampleRows", "采样行数") { t, _, _ -> t.sampleRows ?: "" },
            TCol("fillRate", "整体有值率%") { _, avg, _ -> round2(avg) },
            TCol("status", "状态") { t, _, _ -> t.status!!.name },
        )

        /** 表头:固定前列 + 选中的可选列 */
        fun writeHeader(head: Row, selected: List<Keyed>, vararg fixedCols: String) {
            var c = 0
            for (fixed in fixedCols) {
                head.createCell(c++).setCellValue(fixed)
            }
            for (def in selected) {
                head.createCell(c++).setCellValue(def.header)
            }
        }

        /** 按请求的 key 过滤列定义,保持定义顺序;null = 全部列,空集/全部未知 = 只留固定首列 */
        fun <K : Keyed> selectCols(defs: List<K>, cols: List<String>?): List<K> {
            if (cols == null) {
                return defs
            }
            val keys = HashSet(cols)
            return defs.filter { it.key in keys }
        }

        /** 数字写数值单元格,其余写字符串;null 写空串(实现收敛在 ExcelCells,供其他导出服务复用) */
        fun cell(cell: org.apache.poi.ss.usermodel.Cell, value: Any?) = ExcelCells.cell(cell, value)

        /** sheet 名取自表名:替换非法字符、截断到 31 字符,重名时追加 _2/_3 后缀(实现同上报) */
        fun sheetName(tableName: String, usedNames: MutableSet<String>): String =
            ExcelCells.sheetName(tableName, usedNames)

        fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)

        fun nullSafe(s: String?): String = s ?: ""

        fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

        /** 字节数转可读单位,如 1.3 GB */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) {
                return "$bytes B"
            }
            val units = arrayOf("KB", "MB", "GB", "TB", "PB")
            var v = bytes.toDouble()
            var u = -1
            do {
                v /= 1024
                u++
            } while (v >= 1024 && u < units.size - 1)
            return String.format("%.1f %s", v, units[u])
        }
    }
}
