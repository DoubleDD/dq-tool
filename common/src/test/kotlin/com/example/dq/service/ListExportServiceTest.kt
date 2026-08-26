package com.example.dq.service

import com.example.dq.model.ListExportSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** 通用列表导出:渲染 xlsx、token 一次性取用、文件名/sheet 名清洗 */
class ListExportServiceTest {

    private val service = ListExportService()

    @Test
    fun `stage 渲染 xlsx 内容可读取且行列对齐`() {
        val token = service.stage(
            "库列表",
            listOf(
                ListExportSheet(
                    "库列表",
                    listOf("库名", "表数量"),
                    listOf(listOf("dqtest", "120"), listOf("app", ""))
                )
            )
        )
        val staged = service.take(token)
        assertNotNull(staged)
        assertEquals("库列表", staged!!.filename)
        XSSFWorkbook(ByteArrayInputStream(staged.bytes)).use { wb ->
            val sheet = wb.getSheet("库列表")
            assertNotNull(sheet)
            assertEquals("库名", sheet.getRow(0).getCell(0).stringCellValue)
            assertEquals("dqtest", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("120", sheet.getRow(1).getCell(1).stringCellValue)
            // null 导出为空单元格
            assertEquals("", sheet.getRow(2).getCell(1).stringCellValue)
        }
    }

    @Test
    fun `token 一次性取用 取完即失效`() {
        val token = service.stage("a", listOf(ListExportSheet("s", listOf("h"), emptyList())))
        assertNotNull(service.take(token))
        assertNull(service.take(token))
        assertNull(service.take("不存在的token"))
    }

    @Test
    fun `空 sheets 直接报错`() {
        assertThrows(IllegalArgumentException::class.java) { service.stage("a", emptyList()) }
    }

    @Test
    fun `文件名与 sheet 名清洗`() {
        val token = service.stage(
            "表列表:prod/main",
            listOf(
                ListExportSheet("a/b", listOf("h"), emptyList()),
                ListExportSheet("a?b", listOf("h"), emptyList()),
                ListExportSheet("超长sheet名".repeat(10), listOf("h"), emptyList())
            )
        )
        val staged = service.take(token)!!
        assertEquals("表列表_prod_main", staged.filename)
        XSSFWorkbook(ByteArrayInputStream(staged.bytes)).use { wb ->
            // 非法字符替换 + 重名加后缀 + 截断 31 字符
            assertTrue(wb.getSheet("a_b") != null)
            assertTrue(wb.getSheet("a_b_2") != null)
            for (i in 0 until wb.numberOfSheets) {
                assertTrue(wb.getSheetName(i).length <= 31)
            }
        }
    }
}
