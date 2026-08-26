package com.example.dq.service

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy
import com.deepoove.poi.policy.RenderPolicy
import com.deepoove.poi.template.ElementTemplate
import com.deepoove.poi.template.run.RunTemplate
import com.deepoove.poi.xwpf.BodyContainerFactory
import com.example.dq.model.ScanJobView
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.TagRepository
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl
import java.io.OutputStream

/**
 * 扫描结果 Word 版导出(数据库表结构文档):poi-tl 模板渲染,同步写流(与 Excel 导出同链路)。
 * 数据全部来自该任务快照(scan_table/scan_column)+ 表标记 + 库描述,不回连业务库。
 * 一个扫描任务 = 一个库(schema),故一/二章各一行数据;三章表清单走 LoopRow,
 * 四章表结构逐表「标题 + 字段表」由 TableStructsPolicy 克隆模板原型生成。
 */
class ScanWordExportService(
    private val scanService: ScanService,
    private val tagRepository: TagRepository,
    private val schemaDocRepository: SchemaDocRepository,
) {

    fun export(jobId: Long, out: OutputStream) {
        val job = scanService.getJob(jobId)
        val config = Configure.builder()
            .bind("tables", LoopRowTableRenderPolicy())
            .bind("tableStructs", TableStructsPolicy())
            .build()
        javaClass.getResourceAsStream(TEMPLATE_PATH).use { input ->
            requireNotNull(input) { "模板缺失: $TEMPLATE_PATH" }
            XWPFTemplate.compile(input, config).render(buildRenderData(job)).writeAndClose(out)
        }
    }

    private fun buildRenderData(job: ScanJobView): Map<String, Any> {
        val tables = job.tables.orEmpty()
        val normDb = job.dbName ?: ""
        val schemaName = job.schemaName ?: ""
        val tagsByTable = tagRepository.tableTagsBySchema(job.datasourceId, normDb, schemaName)
        val schemaDesc = schemaDocRepository.findByDatasource(job.datasourceId, normDb)[schemaName]

        var totalRows = 0L
        var sizeBytes = 0L
        val tableRows = ArrayList<Map<String, Any>>(tables.size)
        val structs = ArrayList<TableStruct>(tables.size)
        for (t in tables) {
            val rows = t.totalRows ?: t.scannedRows
            totalRows += rows
            if (t.sizeBytes != null) sizeBytes += t.sizeBytes
            val tags = tagsByTable[t.tableName].orEmpty().joinToString(",") { it.name }
            tableRows += mapOf(
                "name" to t.tableName,
                "comment" to dash(t.comment),
                "totalRows" to String.format("%,d", rows),
                "tags" to tags.ifBlank { "-" },
            )
            val cols = scanService.getColumns(job.id, t.tableName).map { c ->
                listOf(
                    c.columnName ?: "",
                    dash(c.columnComment),
                    c.columnType ?: "",
                    if (c.keyLabel == "PK") "是" else "",
                    if (c.nullable == false) "是" else "",
                    c.defaultValue ?: "—",
                )
            }
            structs += TableStruct(
                title = if (t.comment.isNullOrBlank()) t.tableName else "${t.comment}:${t.tableName}",
                columns = cols,
            )
        }
        return mapOf(
            "title" to (job.datasourceName ?: "数据库"),
            "dbName" to (job.dbName ?: schemaName),
            "schemaName" to schemaName,
            "schemaDesc" to dash(schemaDesc),
            "dbCount" to "1",
            "schemaCount" to "1",
            "tableCount" to String.format("%,d", tables.size.toLong()),
            "totalRows" to String.format("%,d", totalRows),
            "totalSize" to formatBytes(sizeBytes),
            "tables" to tableRows,
            "tableStructs" to structs,
        )
    }

    private fun dash(s: String?): String = if (s.isNullOrBlank()) "-" else s

    companion object {
        const val TEMPLATE_PATH = "/templates/db-structure-report.docx"

        /** 字节数转可读单位,如 16.9 GB(与 Excel 导出 formatBytes 同款) */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB", "PB")
            var v = bytes.toDouble()
            var u = -1
            do {
                v /= 1024.0
                u++
            } while (v >= 1024.0 && u < units.size - 1)
            return String.format("%.1f %s", v, units[u])
        }
    }
}

/** 表结构节数据:标题(中文名:英文名,无注释只留英文名)+ 字段行(字段英文/中文/类型/主键/非空/默认值) */
data class TableStruct(val title: String, val columns: List<List<String>>)

/**
 * 第四章「表结构」渲染策略。模板在锚点 {{tableStructs}} 段落后保留一个原型小节(H3 标题段 + 字段表),
 * 渲染时按表逐个深拷贝原型并填充数据行,最后删除原型与锚点段落——标题编号(numPr)、表格底纹/边框
 * 全部随克隆保留,与模板样式完全一致。表头行沿用原型文本(六列固定),只重写数据行。
 */
class TableStructsPolicy : RenderPolicy {

    override fun render(eleTemplate: ElementTemplate, data: Any?, template: XWPFTemplate) {
        val run = (eleTemplate as RunTemplate).run
        run.setText("", 0)
        val doc = run.paragraph.document as XWPFDocument
        val anchor = run.paragraph
        val elements = doc.bodyElements
        val anchorIdx = elements.indexOfFirst { it is XWPFParagraph && (it === anchor || it.ctp === anchor.ctp) }
        require(anchorIdx >= 0) { "表结构模板缺少 {{tableStructs}} 锚点段" }
        val protoP = elements.drop(anchorIdx + 1).filterIsInstance<XWPFParagraph>().firstOrNull()
        val protoT = elements.drop(anchorIdx + 1).filterIsInstance<XWPFTable>().firstOrNull()
        require(protoP != null && protoT != null) { "表结构模板缺少原型小节(H3 标题段 + 字段表)" }
        val protoCtp = protoP.ctp.copy()
        val protoTbl = protoT.ctTbl.copy()

        val structs = (data as? List<*>)?.filterIsInstance<TableStruct>().orEmpty()
        val body = BodyContainerFactory.getBodyContainer(run)
        if (structs.isEmpty()) {
            val p = body.insertNewParagraph(run)
            setParagraphText(p.ctp, "(无数据)")
        }
        for (s in structs) {
            // BodyContainer 依调用顺序在锚点段后插入(同 TagSectionsPolicy);插入后整体替换为原型克隆
            val heading = body.insertNewParagraph(run)
            heading.ctp.set(protoCtp.copy())
            setParagraphText(heading.ctp, s.title)
            val table = body.insertNewTable(run, 2, 6)
            table.ctTbl.set(protoTbl.copy())
            fillRows(table.ctTbl, s.columns)
        }

        // 删除原型小节与锚点段落(先删靠后的,避免下标前移)
        val all = doc.bodyElements
        val idxT = all.indexOfFirst { it === protoT }
        val idxP = all.indexOfFirst { it === protoP }
        val idxA = all.indexOfFirst { it is XWPFParagraph && (it === anchor || it.ctp === anchor.ctp) }
        listOf(idxT, idxP, idxA).sortedDescending().forEach { if (it >= 0) doc.removeBodyElement(it) }
    }

    /** 段落文本替换:保留首个 run(含字符格式),重写其 w:t */
    private fun setParagraphText(p: CTP, text: String) {
        while (p.sizeOfRArray() > 1) p.removeR(1)
        val r = if (p.sizeOfRArray() > 0) p.getRArray(0) else p.addNewR()
        while (r.sizeOfTArray() > 0) r.removeT(0)
        r.addNewT().setStringValue(text)
    }

    /** 字段表填充:原型 = 表头 + 一行数据原型;按字段数克隆数据行后逐格重写 */
    private fun fillRows(tbl: CTTbl, columns: List<List<String>>) {
        if (columns.isEmpty()) {
            if (tbl.sizeOfTrArray() > 1) tbl.removeTr(1)
            return
        }
        val protoRow = tbl.getTrArray(1).copy()
        for (i in 2..columns.size) {
            tbl.insertNewTr(i).set(protoRow.copy())
        }
        columns.forEachIndexed { r, row ->
            val tcs = tbl.getTrArray(r + 1).getTcArray()
            row.forEachIndexed { c, text -> setCellText(tcs[c], text) }
        }
    }

    private fun setCellText(tc: CTTc, text: String) {
        while (tc.sizeOfPArray() > 1) tc.removeP(1)
        setParagraphText(tc.getPArray(0), text)
    }
}
