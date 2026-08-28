package com.example.dq.util

import org.apache.poi.ss.usermodel.Cell

/**
 * Excel 写入的共用小工具(从 ExportService 抽出,供抽样导出等新服务复用):
 * 单元格写值口径与 sheet 名清洗/截断/去重
 */
object ExcelCells {

    /** 数字写数值单元格,其余写字符串;null 写空串 */
    fun cell(cell: Cell, value: Any?) {
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

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
