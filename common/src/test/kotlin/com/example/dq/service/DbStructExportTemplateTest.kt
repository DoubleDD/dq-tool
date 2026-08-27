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
 * 数据源整库表结构 Word 模板渲染:守护模板标签完整性 + 两级循环 policy 克隆逻辑。
 * 覆盖:一章汇总、二章数据库清单 LoopRow、三章按 库→模式 两级克隆表清单、四章按 模式→表 两级克隆字段表、
 * 原型小节与锚点段删除、零字段表只留表头、无表 schema 输出「(无数据表)」、空数据源输出「(无数据)」。
 */
class DbStructExportTemplateTest {

    private fun render(
        dbs: List<Map<String, Any>>,
        tableSections: List<DbTableSection>,
        structSections: List<SchemaStructSection>,
    ): XWPFDocument {
        val config = Configure.builder()
            .bind("dbs", LoopRowTableRenderPolicy())
            .bind("tableSections", TableListSectionsPolicy())
            .bind("structSections", StructSectionsPolicy())
            .build()
        val input = javaClass.getResourceAsStream(DbStructExportService.TEMPLATE_PATH)!!
        val out = ByteArrayOutputStream()
        val data = mapOf(
            "dbCount" to "2",
            "schemaCount" to "3",
            "tableCount" to "3",
            "totalRows" to "56,724",
            "totalSize" to "16.9 MB",
            "dbs" to dbs,
            "tableSections" to tableSections,
            "structSections" to structSections,
            "tocEntries" to tocEntriesOf(tableSections, structSections),
        )
        val template = XWPFTemplate.compile(input, config).render(data)
        @Suppress("UNCHECKED_CAST")
        rewriteTocEntries(template.xwpfDocument, data["tocEntries"] as List<TocEntry>)
        template.writeAndClose(out)
        return XWPFDocument(ByteArrayInputStream(out.toByteArray()))
    }

    /** 与 DbStructExportService 同口径的目录条目(编号格式:N. / N.M. / N.M.K.) */
    private fun tocEntriesOf(
        tableSections: List<DbTableSection>, structSections: List<SchemaStructSection>,
    ): List<TocEntry> {
        val entries = ArrayList<TocEntry>()
        entries += TocEntry(1, "1. 总体情况")
        entries += TocEntry(1, "2. 数据库清单")
        entries += TocEntry(1, "3. 表清单")
        tableSections.forEachIndexed { i, sec ->
            entries += TocEntry(2, "3.${i + 1}. ${sec.heading2}")
            sec.schemas.forEachIndexed { j, s -> entries += TocEntry(3, "3.${i + 1}.${j + 1}. ${s.heading3}") }
        }
        entries += TocEntry(1, "4. 表结构")
        structSections.forEachIndexed { i, sec ->
            entries += TocEntry(2, "4.${i + 1}. ${sec.heading2}")
            sec.structs.forEachIndexed { j, s -> entries += TocEntry(3, "4.${i + 1}.${j + 1}. ${s.title}") }
        }
        return entries
    }

    private fun allText(doc: XWPFDocument): String =
        doc.paragraphs.joinToString("\n") { it.text } + "\n" +
            doc.tables.joinToString("\n") { t ->
                t.rows.joinToString("\n") { r -> r.tableCells.joinToString("|") { c -> c.text } }
            }

    /** 两库三模式:db1(s1 两表/s2 一零字段表),db2(s3 无表) */
    private fun sample(): Triple<List<Map<String, Any>>, List<DbTableSection>, List<SchemaStructSection>> {
        val dbs = listOf(
            mapOf("dbName" to "db1", "schemaName" to "s1", "schemaDesc" to "中台库", "tableCount" to "2", "totalSize" to "16.0 MB"),
            mapOf("dbName" to "db1", "schemaName" to "s2", "schemaDesc" to "-", "tableCount" to "1", "totalSize" to "0.9 MB"),
            mapOf("dbName" to "db2", "schemaName" to "s3", "schemaDesc" to "-", "tableCount" to "0", "totalSize" to "0 B"),
        )
        val tableSections = listOf(
            DbTableSection("数据库：db1", listOf(
                SchemaTableSection("模式：s1", listOf(
                    listOf("user_info", "用户表", "53,210", "基础数据"),
                    listOf("order_detail", "-", "3,514", "-"),
                )),
                SchemaTableSection("模式：s2", listOf(
                    listOf("empty_tbl", "空表", "0", "-"),
                )),
            )),
            DbTableSection("数据库：db2", listOf(
                SchemaTableSection("模式：s3", emptyList()),
            )),
        )
        val structSections = listOf(
            SchemaStructSection("数据库：db1中模式：s1", listOf(
                TableStruct("用户表:user_info", listOf(
                    listOf("id", "主键", "bigint", "是", "是", "—"),
                    listOf("name", "姓名", "varchar(64)", "", "", "—"),
                )),
                TableStruct("order_detail", listOf(
                    listOf("order_id", "订单号", "bigint", "是", "是", "—"),
                )),
            )),
            SchemaStructSection("数据库：db1中模式：s2", listOf(
                TableStruct("空表:empty_tbl", emptyList()),
            )),
            SchemaStructSection("数据库：db2中模式：s3", emptyList()),
        )
        return Triple(dbs, tableSections, structSections)
    }

    @Test
    fun `渲染四章正文且无标签与原型残留`() {
        val (dbs, tableSections, structSections) = sample()
        render(dbs, tableSections, structSections).use { doc ->
            val text = allText(doc)
            assertFalse(text.contains("{{"), "存在未渲染标签: $text")
            assertFalse(text.contains("PROTO"), "原型小节未删除: $text")
            // 章节标题渲染(全角冒号):三章 H2/H3、四章 H2
            assertTrue(doc.paragraphs.any { it.text == "数据库：db1" })
            assertTrue(doc.paragraphs.any { it.text == "数据库：db2" })
            assertTrue(doc.paragraphs.any { it.text == "模式：s1" })
            assertTrue(doc.paragraphs.any { it.text == "数据库：db1中模式：s1" })
            assertTrue(doc.paragraphs.any { it.text == "数据库：db2中模式：s3" })
            // 三章 H2 沿用模板样式(编号 3.x),四章表标题沿用 H3 样式(编号 4.x.x)
            assertEquals("4", doc.paragraphs.first { it.text == "数据库：db1" }.style)
            assertEquals("5", doc.paragraphs.first { it.text == "模式：s1" }.style)
            assertEquals("5", doc.paragraphs.first { it.text == "用户表:user_info" }.style)
            // 无表 schema 提示
            assertTrue(doc.paragraphs.any { it.text.contains("无数据表") })
            // 表格:总体 1 + 数据库清单 1 + 表清单 3 + 字段表 3
            assertEquals(8, doc.tables.size)
            // 数据库清单:表头 + 3 数据行
            assertEquals(4, doc.tables[1].rows.size)
            assertEquals("db1", doc.tables[1].rows[1].tableCells[0].text)
            assertEquals("s3", doc.tables[1].rows[3].tableCells[1].text)
            // 表清单:s1 两表、s2 一表、s3 只剩表头
            assertEquals(3, doc.tables[2].rows.size)
            assertEquals("user_info", doc.tables[2].rows[1].tableCells[0].text)
            assertEquals(2, doc.tables[3].rows.size)
            assertEquals(1, doc.tables[4].rows.size, "无表 schema 的表清单应只剩表头行")
            // 字段表:表头 + 字段行;零字段表只留表头
            assertEquals(3, doc.tables[5].rows.size)
            assertEquals("name", doc.tables[5].rows[2].tableCells[0].text)
            assertEquals(2, doc.tables[6].rows.size)
            assertEquals(1, doc.tables[7].rows.size, "零字段表应只剩表头行")

            // 目录条目已按渲染结果重写,无模板示例残留;TOC 域结构保留(页码待客户端刷新)
            val xml = doc.document.body.xmlText()
            assertFalse(xml.contains("ln_reservoir_matrix"), "目录仍残留模板示例条目")
            assertTrue(xml.contains("3.1. 数据库：db1"))
            assertTrue(xml.contains("3.1.2. 模式：s2"))
            assertTrue(xml.contains("3.2. 数据库：db2"))
            assertTrue(xml.contains("4.2.1. 空表:empty_tbl"))
            assertTrue(xml.contains("4.3. 数据库：db2中模式：s3"))
            assertTrue(xml.contains("TOC \\o"), "目录 TOC 域应保留")
            // 条目顺序与正文一致
            val order = listOf("1. 总体情况", "2. 数据库清单", "3. 表清单", "3.1. 数据库：db1",
                "3.1.2. 模式：s2", "3.2. 数据库：db2", "4. 表结构", "4.3. 数据库：db2中模式：s3")
            assertTrue(order.zipWithNext().all { (a, b) -> xml.indexOf(a) < xml.indexOf(b) },
                "目录条目顺序异常")
        }
    }

    @Test
    fun `空数据源输出无数据提示且无标签残留`() {
        render(emptyList(), emptyList(), emptyList()).use { doc ->
            val text = allText(doc)
            assertFalse(text.contains("{{"), "存在未渲染标签: $text")
            assertFalse(text.contains("PROTO"), "原型小节未删除")
            assertEquals(2, doc.tables.size, "空数据源不生成表清单与字段表")
            assertTrue(doc.paragraphs.count { it.text.contains("无数据") } >= 2)
            // 目录只剩四章一级条目
            val xml = doc.document.body.xmlText()
            assertTrue(xml.contains("1. 总体情况"))
            assertTrue(xml.contains("4. 表结构"))
            assertFalse(xml.contains("3.1."))
        }
    }
}
