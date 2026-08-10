package com.example.dq.service

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.data.style.BorderStyle
import com.deepoove.poi.data.style.CellStyle
import com.deepoove.poi.policy.RenderPolicy
import com.deepoove.poi.template.ElementTemplate
import com.deepoove.poi.template.run.RunTemplate
import com.deepoove.poi.util.TableTools
import com.deepoove.poi.xwpf.BodyContainer
import com.deepoove.poi.xwpf.BodyContainerFactory
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import java.math.BigInteger

/**
 * Word 报告代码建表工具与渲染策略(第二章分组合并表、第三章按标记分节)。
 * 分组表(每库 N 行、数据库实例列纵向合并)无法用 LoopRowTableRenderPolicy(它把非首行的
 * vMerge restart 一律改 continue,会把各库合并格串组),故这类表与动态数量的标记节都由代码构建。
 * 样式与模板表格一致:表头深蓝底(1A3C5E)白字粗体微软雅黑 10pt,数据行微软雅黑 9pt,全边框。
 */
object WordReportTables {

    private const val HEADER_BG = "1A3C5E"
    private const val FONT = "微软雅黑"
    private const val COLOR_SECTION = "2C5282"
    private const val COLOR_SUB = "555555"

    /** 分组表:首列为组标签(数据库实例,组内纵向合并),rows 为组内各行的剩余列 */
    data class Group(val label: String, val rows: List<List<String>>)
    data class GroupTable(val headers: List<String>, val groups: List<Group>)

    /** 第三章单个标记节的渲染数据(两张表均含末行合计) */
    data class TagSection(
        val sectionNo: String, val tagName: String, val summary: String,
        val overviewHeaders: List<String>, val overviewRows: List<List<String>>,
        val qualityHeaders: List<String>, val qualityRows: List<List<String>>,
        val analysis: String,
    )

    /** 分组表策略:段落标签处插入分组 vMerge 表;无数据时输出一行说明 */
    class GroupTablePolicy : RenderPolicy {
        override fun render(eleTemplate: ElementTemplate, data: Any?, template: XWPFTemplate) {
            val run = (eleTemplate as RunTemplate).run
            run.setText("", 0)
            val body = BodyContainerFactory.getBodyContainer(run)
            val table = data as? GroupTable
            if (table == null || table.groups.all { it.rows.isEmpty() }) {
                insertBodyParagraph(body, run, "(无数据)")
                return
            }
            buildGroupTable(body, run, table)
        }
    }

    /** 标记节策略:按 USER 标记逐个生成 3.x 节(标题/总体情况/质量情况/可用性分析) */
    class TagSectionsPolicy : RenderPolicy {
        override fun render(eleTemplate: ElementTemplate, data: Any?, template: XWPFTemplate) {
            val run = (eleTemplate as RunTemplate).run
            run.setText("", 0)
            val body = BodyContainerFactory.getBodyContainer(run)
            val sections = (data as? List<*>)?.filterIsInstance<TagSection>().orEmpty()
            for (s in sections) {
                insertHeading(body, run, "${s.sectionNo} ${s.tagName}数据(统计结果分析)", 1, COLOR_SECTION, 14)
                insertHeading(body, run, "（一）总体情况", 2, COLOR_SUB, 12)
                insertBodyParagraph(body, run, s.summary)
                insertBodyParagraph(body, run, "${s.tagName}数据所在数据库实例情况如下:")
                buildPlainTable(body, run, s.overviewHeaders, s.overviewRows)
                insertHeading(body, run, "（二）数据质量情况", 2, COLOR_SUB, 12)
                insertBodyParagraph(body, run, "${s.tagName}数据所在数据库实例的质量情况如下:")
                buildPlainTable(body, run, s.qualityHeaders, s.qualityRows)
                insertHeading(body, run, "（三）数据可用性分析（大模型）", 2, COLOR_SUB, 12)
                insertBodyParagraph(body, run, s.analysis)
            }
        }
    }

    // ---------- 建表/段落原语 ----------

    /** 普通表:表头 + 数据行(调用方把合计行作为最后一行数据传入) */
    fun buildPlainTable(body: BodyContainer, run: XWPFRun, headers: List<String>, rows: List<List<String>>) {
        val table = body.insertNewTable(run, rows.size + 1, headers.size)
        fillTable(table, headers, rows)
    }

    /** 分组表:首列按组纵向合并(组首行写标签,组内 >1 行时 mergeCellsVertically) */
    fun buildGroupTable(body: BodyContainer, run: XWPFRun, data: GroupTable) {
        val dataRows = data.groups.sumOf { it.rows.size }
        val table = body.insertNewTable(run, dataRows + 1, data.headers.size)
        val allRows = ArrayList<List<String>>(dataRows)
        for (g in data.groups) {
            g.rows.forEachIndexed { i, row -> allRows.add(if (i == 0) listOf(g.label) + row else listOf("") + row) }
        }
        fillTable(table, data.headers, allRows)
        var r = 1
        for (g in data.groups) {
            if (g.rows.size > 1) {
                TableTools.mergeCellsVertically(table, 0, r, r + g.rows.size - 1)
            }
            r += g.rows.size
        }
    }

    private fun fillTable(table: XWPFTable, headers: List<String>, rows: List<List<String>>) {
        TableTools.borderTable(table, BorderStyle.DEFAULT)
        headers.forEachIndexed { c, h -> setCell(table.rows[0].getCell(c), h, header = true) }
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, text -> setCell(table.rows[r + 1].getCell(c), text, header = false) }
        }
    }

    private fun setCell(cell: XWPFTableCell, text: String, header: Boolean) {
        if (header) {
            TableTools.styleTableCell(cell, CellStyle().apply { backgroundColor = HEADER_BG })
        }
        val p = cell.paragraphs[0]
        val run = p.createRun()
        run.setText(text)
        styleRun(run, bold = header, color = if (header) "FFFFFF" else null, size = if (header) 10 else 9)
    }

    /** 正文段落(雅黑 10.5pt) */
    fun insertBodyParagraph(body: BodyContainer, run: XWPFRun, text: String) {
        val p = body.insertNewParagraph(run)
        val r = p.createRun()
        r.setText(text)
        styleRun(r, bold = false, color = null, size = 10.5)
    }

    /** 节/小节标题:outlineLvl 决定目录收录层级(1=节、2=小节,与模板既有标题一致) */
    fun insertHeading(body: BodyContainer, run: XWPFRun, text: String, outlineLvl: Int, color: String, sizePt: Int) {
        val p = body.insertNewParagraph(run)
        val ctp = p.getCTP()
        val ppr = ctp.pPr ?: ctp.addNewPPr()
        ppr.addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLvl.toLong()))
        val r = p.createRun()
        r.setText(text)
        styleRun(r, bold = true, color = color, size = sizePt)
    }

    private fun styleRun(run: XWPFRun, bold: Boolean, color: String?, size: Double) {
        run.setFontFamily(FONT) // ascii + hAnsi
        val ctr = run.getCTR()
        val rPr = ctr.rPr ?: ctr.addNewRPr()
        val fonts = if (rPr.sizeOfRFontsArray() > 0) rPr.getRFontsArray(0) else rPr.addNewRFonts()
        fonts.setEastAsia(FONT)
        run.setFontSize(size)
        run.isBold = bold
        if (color != null) {
            run.setColor(color)
        }
    }

    private fun styleRun(run: XWPFRun, bold: Boolean, color: String?, size: Int) =
        styleRun(run, bold, color, size.toDouble())
}
