package com.example.dq.service

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 扫描结果 Word 模板渲染:守护模板标签完整性 + TableStructsPolicy 克隆逻辑。
 * 覆盖:一/二章单行、三章 LoopRow 表清单、四章逐表「H3 标题 + 字段表」克隆、
 * 原型小节与锚点段删除、零字段表只留表头、无表时输出「(无数据)」。
 */
class ScanWordExportTemplateTest {

    private fun render(structs: List<TableStruct>): XWPFDocument {
        val config = Configure.builder()
            .bind("tables", LoopRowTableRenderPolicy())
            .bind("tableStructs", TableStructsPolicy())
            .build()
        val input = javaClass.getResourceAsStream(ScanWordExportService.TEMPLATE_PATH)!!
        val out = ByteArrayOutputStream()
        val data = mapOf(
            "title" to "渭河",
            "dbName" to "dtwin_mid_test",
            "schemaName" to "dtwin_mid_test",
            "schemaDesc" to "中台库",
            "dbCount" to "1",
            "schemaCount" to "1",
            "tableCount" to "2",
            "totalRows" to "56,724",
            "totalSize" to "16.9 MB",
            "tables" to listOf(
                mapOf("name" to "user_info", "comment" to "用户表", "totalRows" to "53,210", "tags" to "基础数据"),
                mapOf("name" to "order_detail", "comment" to "-", "totalRows" to "3,514", "tags" to "-"),
            ),
            "tableStructs" to structs,
        )
        XWPFTemplate.compile(input, config).render(data).writeAndClose(out)
        return XWPFDocument(ByteArrayInputStream(out.toByteArray()))
    }

    private fun allText(doc: XWPFDocument): String =
        doc.paragraphs.joinToString("\n") { it.text } + "\n" +
            doc.tables.joinToString("\n") { t ->
                t.rows.joinToString("\n") { r -> r.tableCells.joinToString("|") { c -> c.text } }
            }

    @Test
    fun `渲染四章正文且无标签与原型残留`() {
        val structs = listOf(
            TableStruct(
                "用户表:user_info", listOf(
                    listOf("id", "主键", "bigint", "是", "是", "—"),
                    listOf("name", "姓名", "varchar(64)", "", "", "—"),
                    listOf("email", "邮箱", "varchar(64)", "", "是", "—"),
                )
            ),
            TableStruct(
                "order_detail", listOf(
                    listOf("order_id", "订单号", "bigint", "是", "是", "—"),
                )
            ),
        )
        render(structs).use { doc ->
            val text = allText(doc)
            assertFalse(text.contains("{{"), "存在未渲染标签: $text")
            assertFalse(text.contains("reservoir_base"), "原型小节未删除")
            assertFalse(text.contains("tableStructs"), "锚点段未删除")
            // 章节标题渲染(全角冒号)
            assertTrue(doc.paragraphs.any { it.text == "数据库：dtwin_mid_test" })
            assertTrue(doc.paragraphs.any { it.text == "数据库：dtwin_mid_test中模式：dtwin_mid_test" })
            // 表结构标题(半角冒号拼接)
            val h3 = doc.paragraphs.firstOrNull { it.text == "用户表:user_info" }
            assertTrue(h3 != null, "缺少表结构标题")
            assertEquals("5", h3!!.style, "表结构标题应沿用模板 H3 样式(编号 4.1.x)")
            assertTrue(doc.paragraphs.any { it.text == "order_detail" })
            // 表格:总体 1 + 数据库清单 1 + 表清单 1 + 字段表 2
            assertEquals(5, doc.tables.size)
            // 表清单:表头 + 2 数据行
            assertEquals(3, doc.tables[2].rows.size)
            assertEquals("user_info", doc.tables[2].rows[1].tableCells[0].text)
            // 字段表:表头 + 字段行
            assertEquals(4, doc.tables[3].rows.size)
            assertEquals("email", doc.tables[3].rows[3].tableCells[0].text)
            assertEquals(2, doc.tables[4].rows.size)
        }
    }

    @Test
    fun `零字段表只留表头,无表时输出无数据提示`() {
        render(listOf(TableStruct("空表:empty_tbl", emptyList()))).use { doc ->
            assertEquals(4, doc.tables.size)
            assertEquals(1, doc.tables[3].rows.size, "零字段表应只剩表头行")
        }
        render(emptyList()).use { doc ->
            assertEquals(3, doc.tables.size, "无表时不生成字段表")
            assertTrue(doc.paragraphs.any { it.text.contains("无数据") })
            assertFalse(allText(doc).contains("{{"))
        }
    }
}
