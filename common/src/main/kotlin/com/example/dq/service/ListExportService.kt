package com.example.dq.service

import com.example.dq.model.ListExportSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 通用列表导出:前端提交当前表格所见的表头与行数据,渲染成 xlsx 后暂存,
 * 返回一次性下载 token(GET /api/list-exports/{token} 取走后即删,超时未取自动过期)。
 * 与扫描结果导出(ExportService)的区别:这里不含任何业务查询,数据完全由前端按展示口径组装,
 * 因此各列表页(数据源/库/表/字段/索引/扫描记录)都能复用,且导出内容与页面过滤结果一致。
 */
class ListExportService {

    /** 暂存的导出产物(一次性下载) */
    data class StagedExport(val filename: String, val bytes: ByteArray, val expireAt: Long)

    private val staged = ConcurrentHashMap<String, StagedExport>()

    /** 渲染 xlsx 并暂存,返回下载 token */
    fun stage(filename: String, sheets: List<ListExportSheet>): String {
        require(sheets.isNotEmpty()) { "sheets 不能为空" }
        val bytes = render(sheets)
        sweep()
        val token = UUID.randomUUID().toString().replace("-", "")
        staged[token] = StagedExport(sanitizeFilename(filename), bytes, System.currentTimeMillis() + TTL_MS)
        return token
    }

    /** 取走暂存产物(一次性);不存在或已过期返回 null */
    fun take(token: String): StagedExport? {
        sweep()
        val s = staged.remove(token) ?: return null
        return if (s.expireAt >= System.currentTimeMillis()) s else null
    }

    /** 惰性清理过期产物(每次 stage/take 顺手扫一遍,量极小) */
    private fun sweep() {
        val now = System.currentTimeMillis()
        staged.entries.removeIf { it.value.expireAt < now }
    }

    private fun render(sheets: List<ListExportSheet>): ByteArray {
        val out = ByteArrayOutputStream()
        SXSSFWorkbook(200).use { wb ->
            val usedNames = HashSet<String>()
            for (s in sheets) {
                val sheet = wb.createSheet(sheetName(s.name, usedNames))
                val head = sheet.createRow(0)
                s.headers.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }
                s.rows.forEachIndexed { r, row ->
                    val excelRow = sheet.createRow(r + 1)
                    row.forEachIndexed { c, v -> excelRow.createCell(c).setCellValue(v) }
                }
            }
            wb.write(out)
            wb.dispose()
        }
        return out.toByteArray()
    }

    private companion object {
        /** 暂存产物有效期:前端拿到 token 后立即触发下载,5 分钟足够 */
        const val TTL_MS = 5 * 60 * 1000L

        /** 文件名:替换各平台非法字符,避免桌面端保存对话框拿到带路径分隔符的名字 */
        fun sanitizeFilename(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "export" }

        /** sheet 名:替换非法字符、截断到 31 字符,重名时追加 _2/_3 后缀(与 ExportService 同口径) */
        fun sheetName(name: String, usedNames: MutableSet<String>): String {
            val base = name.replace(Regex("[\\\\/?*\\[\\]:]"), "_").ifBlank { "Sheet" }
            var n = if (base.length <= 31) base else base.substring(0, 31)
            var i = 2
            while (!usedNames.add(n)) {
                val suffix = "_$i"
                n = (if (base.length <= 31 - suffix.length) base else base.substring(0, 31 - suffix.length)) + suffix
                i++
            }
            return n
        }
    }
}
