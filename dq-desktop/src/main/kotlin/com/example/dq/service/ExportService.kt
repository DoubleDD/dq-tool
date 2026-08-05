package com.example.dq.service

import com.example.dq.model.NullRule
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.IOException
import java.io.OutputStream
import java.time.format.DateTimeFormatter

/** 扫描结果导出 xlsx(SXSSF 流式写,大结果集不占内存) */
class ExportService(
    private val scanService: ScanService,
) {

    /** 带 key 的列定义,供导出预览按 key 选择要导出的列 */
    private interface Keyed {
        val key: String
        val header: String
    }

    /** 字段明细 sheet 的可选列定义;"表名/表注释/字段"为固定前列,不参与选择 */
    private data class Col(
        override val key: String,
        override val header: String,
        val value: (ScanColumnView) -> Any?,
    ) : Keyed

    /** 表列表 sheet 的可选列定义;"表名"列恒为第一列,不参与选择;value 第二参为该表字段平均有值率 */
    private data class TCol(
        override val key: String,
        override val header: String,
        val value: (ScanTableView, Double) -> Any?,
    ) : Keyed

    @Throws(IOException::class)
    fun export(jobId: Long, out: OutputStream) {
        export(jobId, null, null, out)
    }

    /**
     * 导出扫描结果。
     *
     * @param tableCols 表列表 sheet 要导出的列 key(见 TABLE_DEFS);null = 全部列,空集 = 只要固定首列
     * @param cols      字段明细 sheet 要导出的列 key(见 COLUMN_DEFS);null = 全部列,空集 = 只要固定列(表名/表注释/字段)
     */
    @Throws(IOException::class)
    fun export(jobId: Long, tableCols: List<String>?, cols: List<String>?, out: OutputStream) {
        val job = scanService.getJob(jobId)
        SXSSFWorkbook(200).use { wb ->
            writeOverview(wb, job)
            writeTables(wb, job, tableCols)
            writeColumns(wb, job, cols)
            writeFailed(wb, job)
            wb.write(out)
            wb.dispose()
        }
    }

    private fun writeOverview(wb: SXSSFWorkbook, job: ScanJobView) {
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
        writeSummary(sheet, r, job)
    }

    /** 统计总结:表/字段规模、空表空字段、总行数、占用空间(空表/空字段口径与前端一致:0 行 / 有值数为 0) */
    private fun writeSummary(sheet: Sheet, r0: Int, job: ScanJobView) {
        var r = r0
        val tables = job.tables ?: emptyList()
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
            val cols = scanService.getColumns(job.id, t.tableName!!)
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

    private fun writeTables(wb: SXSSFWorkbook, job: ScanJobView, tableCols: List<String>?) {
        val selected = selectCols(TABLE_DEFS, tableCols)
        val sheet = wb.createSheet("表列表")
        writeHeader(sheet.createRow(0), selected, "表名")
        var r = 1
        for (t in job.tables ?: emptyList()) {
            val cols = scanService.getColumns(job.id, t.tableName!!)
            val avgRate = if (cols.isEmpty()) 0.0 else cols.map { it.fillRate }.average()
            val row = sheet.createRow(r++)
            var c = 0
            row.createCell(c++).setCellValue(t.tableName)
            for (def in selected) {
                cell(row.createCell(c++), def.value(t, avgRate))
            }
        }
    }

    private fun writeColumns(wb: SXSSFWorkbook, job: ScanJobView, cols: List<String>?) {
        val selected = selectCols(COLUMN_DEFS, cols)
        val usedNames = HashSet<String>()
        for (t in job.tables ?: emptyList()) {
            if (t.status != ScanStatus.DONE) {
                continue
            }
            val tableName = t.tableName!!
            val sheet = wb.createSheet(sheetName(tableName, usedNames))
            writeHeader(sheet.createRow(0), selected, "表名", "表注释", "字段")
            var r = 1
            for (col in scanService.getColumns(job.id, tableName)) {
                val row = sheet.createRow(r++)
                var c = 0
                row.createCell(c++).setCellValue(tableName)
                row.createCell(c++).setCellValue(nullSafe(t.comment))
                row.createCell(c++).setCellValue(col.columnName)
                for (def in selected) {
                    cell(row.createCell(c++), def.value(col))
                }
            }
        }
    }

    private fun writeFailed(wb: SXSSFWorkbook, job: ScanJobView) {
        val failed = job.tables.orEmpty().filter { it.status == ScanStatus.FAILED }
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
            TCol("comment", "注释") { t, _ -> nullSafe(t.comment) },
            TCol("storage", "引擎/表空间") { t, _ -> nullSafe(t.storageInfo) },
            TCol("totalRows", "总行数") { t, _ -> t.totalRows ?: "" },
            TCol("sampled", "是否采样") { t, _ -> if (t.sampled) "是(估算)" else "否" },
            TCol("sampleRows", "采样行数") { t, _ -> t.sampleRows ?: "" },
            TCol("fillRate", "整体有值率%") { _, avg -> round2(avg) },
            TCol("status", "状态") { t, _ -> t.status!!.name },
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

        /** 数字写数值单元格,其余写字符串;null 写空串 */
        fun cell(cell: org.apache.poi.ss.usermodel.Cell, value: Any?) {
            when (value) {
                null -> cell.setCellValue("")
                is Number -> cell.setCellValue(value.toDouble())
                else -> cell.setCellValue(value.toString())
            }
        }

        /** sheet 名取自表名:替换非法字符、截断到 31 字符,重名时追加 _2/_3 后缀 */
        fun sheetName(tableName: String, usedNames: MutableSet<String>): String {
            val base = tableName.replace(Regex("[\\\\/?*\\[\\]:]"), "_")
            var name = truncate(base, 31)
            var n = 2
            while (!usedNames.add(name)) {
                name = truncate(base, 31 - ("_$n").length) + "_" + n
                n++
            }
            return name
        }

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
