package com.example.dq.service

import com.example.dq.model.DbType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 比对任务批量导入 Excel 解析:15 列表头定位/「是否基准表」定位基准行(不要求首行)/数据库类型列/类别自由文本/模版自洽 */
class CompareImportExcelParserTest {

    private val headers = listOf(
        "所属水利对象类别名称", "实际系统或模式描述", "数据库类型", "IP地址", "端口",
        "用户名", "口令", "数据库名称", "模式名称", "表中文名称", "表英文名称",
        "是否基准表", "对象编码字段", "对象名称字段", "是否纳入采集范围")

    /** 造一张多 sheet xlsx:每个 sheet 首行表头(可按 order 打乱顺序、加空白),其后为数据行 */
    private fun xlsx(vararg sheets: Pair<String, List<List<String>>>,
                     headerOrder: List<String> = headers): ByteArray {
        val out = ByteArrayOutputStream()
        XSSFWorkbook().use { wb ->
            for ((name, rows) in sheets) {
                val sheet = wb.createSheet(name)
                val head = sheet.createRow(0)
                headerOrder.forEachIndexed { i, h -> head.createCell(i).setCellValue(h) }
                rows.forEachIndexed { r, values ->
                    val row = sheet.createRow(r + 1)
                    // values 按标准表头顺序给,这里映射到实际列顺序(表头匹配前去空白,与解析口径一致)
                    values.forEachIndexed { i, v ->
                        val idx = headerOrder.indexOfFirst { it.trim() == headers[i] }
                        if (idx >= 0) row.createCell(idx).setCellValue(v)
                    }
                }
            }
            wb.write(out)
        }
        return out.toByteArray()
    }

    private fun baseRow(vararg overrides: Pair<Int, String>): List<String> {
        val row = MutableList(15) { "" }
        row[0] = "供(取)水量监测点"; row[1] = "水资源监控平台-汇集库"; row[2] = "mysql"
        row[3] = "10.0.0.1"; row[4] = "3306"; row[5] = "root"; row[6] = "pw1"
        row[7] = "base_db"; row[8] = "base_db"; row[9] = "取用水监测点基本信息表"; row[10] = "reservoir_base"
        row[11] = "是"; row[12] = "res_code"; row[13] = "res_name"; row[14] = "是"
        overrides.forEach { (i, v) -> row[i] = v }
        return row
    }

    private fun targetRow(vararg overrides: Pair<Int, String>): List<String> {
        val row = MutableList(15) { "" }
        row[1] = "小水电生态泄流系统-用户库"; row[2] = "mysql"; row[3] = "10.0.0.2"; row[4] = "3306"
        row[5] = "root"; row[6] = "pw2"; row[7] = "vendor_db"; row[9] = "取用水监测点基本信息表"
        row[10] = "t_reservoir"; row[11] = "否"; row[14] = "是"
        overrides.forEach { (i, v) -> row[i] = v }
        return row
    }

    @Test
    fun `正常解析 一sheet一任务 基准加对比`() {
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("监测点" to listOf(baseRow(), targetRow()))))
        assertEquals(1, r.sheets.size)
        assertTrue(r.skippedSheets.isEmpty() && r.skippedRows.isEmpty())
        val sheet = r.sheets.single()
        assertEquals("监测点", sheet.sheetName)
        assertEquals("供(取)水量监测点", sheet.objectCategory) // 自由文本原样(不在旧内置列表也保留)
        assertTrue(sheet.base.base)
        assertEquals("res_code", sheet.base.codeField)
        assertEquals(DbType.MYSQL, sheet.base.dbType)
        assertEquals("MYSQL|10.0.0.1|3306|base_db", sheet.base.dsKey) // 定位 key 含数据库类型
        assertEquals("水资源监控平台-汇集库(base_db)", sheet.base.dsDisplayName)
        assertEquals("取用水监测点基本信息表", sheet.base.tableCnName) // 表中文名称解析留档
        assertEquals(1, sheet.targets.size)
        assertEquals("t_reservoir", sheet.targets[0].tableName)
        assertEquals("pw2", sheet.targets[0].password) // 口令只在内存行上
    }

    @Test
    fun `表头按名定位 顺序打乱与加空白都能识别`() {
        val shuffled = listOf(
            " 表英文名称 ", "是否基准表", "口令", "IP地址", "数据库名称", "端口", "实际系统或模式描述",
            "所属水利对象类别名称", "模式名称", "对象名称字段", "对象编码字段", "用户名",
            "数据库类型", "表中文名称", "是否纳入采集范围")
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(), targetRow()), headerOrder = shuffled)))
        assertEquals(1, r.sheets.size)
        assertEquals("reservoir_base", r.sheets[0].base.tableName)
        assertEquals("pw1", r.sheets[0].base.password)
    }

    @Test
    fun `缺必需列表头直接报错并带 sheet 名`() {
        val partial = headers - "表英文名称"
        val e = assertThrows(IllegalArgumentException::class.java) {
            CompareImportExcelParser.parse(ByteArrayInputStream(
                xlsx("坏表" to listOf(baseRow(), targetRow()), headerOrder = partial)))
        }
        assertTrue(e.message!!.contains("坏表") && e.message!!.contains("表英文名称"), e.message)
    }

    @Test
    fun `全空行跳过 非法行进 skippedRows`() {
        val badPort = targetRow(4 to "not-a-port")
        val missingTable = targetRow(10 to "")
        val badBaseFlag = targetRow(11 to "主角")
        val badType = targetRow(2 to "access")
        val blank = MutableList(15) { "" }
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(), blank, badPort, missingTable, badBaseFlag, badType, targetRow()))))
        assertEquals(1, r.sheets.size)
        assertEquals(1, r.sheets[0].targets.size)
        assertEquals(4, r.skippedRows.size)
        assertTrue(r.skippedRows[0].reason.contains("端口无法识别"))
        assertTrue(r.skippedRows[1].reason.contains("缺少必需列"))
        assertTrue(r.skippedRows[2].reason.contains("是否基准表"), r.skippedRows[2].reason)
        assertTrue(r.skippedRows[3].reason.contains("数据库类型无法识别: access"), r.skippedRows[3].reason)
    }

    @Test
    fun `数据库类型必填 大小写不敏感`() {
        val ok = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(2 to "MySQL"), targetRow(2 to " Kingbase ")))))
        assertEquals(DbType.MYSQL, ok.sheets[0].base.dbType)
        assertEquals(DbType.KINGBASE, ok.sheets[0].targets[0].dbType)

        val missing = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(), targetRow(2 to "")))))
        assertTrue(missing.skippedRows.single().reason.contains("缺少必需列: 数据库类型"),
            missing.skippedRows.single().reason)
    }

    @Test
    fun `基准行规则 按是否基准表定位 零行多行缺身份字段整sheet跳过 位置不限`() {
        val noBase = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("无基准" to listOf(targetRow()))))
        assertEquals(1, noBase.skippedSheets.size)
        assertTrue(noBase.skippedSheets[0].reason.contains("缺少基准行"))

        val twoBases = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("双基准" to listOf(baseRow(), baseRow(10 to "another"), targetRow()))))
        assertTrue(twoBases.skippedSheets[0].reason.contains("有且仅有一行"))

        // 基准行不再要求首行:对比行在前、基准行在中也正常解析
        val baseLast = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("基准在后" to listOf(targetRow(), baseRow(), targetRow(3 to "10.0.0.3")))))
        assertEquals(1, baseLast.sheets.size)
        assertTrue(baseLast.skippedSheets.isEmpty() && baseLast.skippedRows.isEmpty())
        assertEquals(2, baseLast.sheets[0].targets.size)

        val noIdentity = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("缺身份" to listOf(baseRow(12 to "", 13 to ""), targetRow()))))
        assertTrue(noIdentity.skippedSheets[0].reason.contains("对象编码字段"), noIdentity.skippedSheets[0].reason)

        // 全部 sheet 非法时 sheets 为空(由 service 层决定报错)
        assertTrue(noIdentity.sheets.isEmpty())
    }

    @Test
    fun `基准行被行级校验丢掉时 sheet 原因带出真实缺列`() {
        // 现场场景:填表人把库名填进「模式名称」列、「数据库名称」留空 → 「是」行作为非法数据行被丢,
        // 若只报「缺少基准行」,用户找不到真实问题(见 2026-09-17 错误中心 WARN)
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("示例任务" to listOf(baseRow(7 to "", 8 to "hyd_ln"), targetRow()))))
        assertTrue(r.sheets.isEmpty())
        val reason = r.skippedSheets.single().reason
        assertTrue(reason.contains("基准行无效") && reason.contains("数据库名称"), reason)
        assertTrue(r.skippedRows.any { it.reason.contains("数据库名称") }, r.skippedRows.toString())

        // 「否」行全部非法时,「没有有效对比行」同样带出原因
        val noTarget = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(), targetRow(7 to "")))))
        assertTrue(noTarget.skippedSheets.single().reason.contains("数据库名称"), noTarget.skippedSheets.single().reason)
    }

    @Test
    fun `没有对比行的 sheet 整体跳过`() {
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("只有基准" to listOf(baseRow()))))
        assertTrue(r.sheets.isEmpty())
        assertTrue(r.skippedSheets.single().reason.contains("没有有效对比行"))
    }

    @Test
    fun `所属水利对象类别名称 自由文本原样 空则留空`() {
        val custom = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(0 to " 火电站 "), targetRow()))))
        assertEquals("火电站", custom.sheets[0].objectCategory) // trim 后原样,不校验内置列表

        val missing = CompareImportExcelParser.parse(ByteArrayInputStream(
            xlsx("s1" to listOf(baseRow(0 to ""), targetRow()))))
        assertNull(missing.sheets[0].objectCategory)
    }

    @Test
    fun `多 sheet 各自成任务 对比行身份字段可空`() {
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(xlsx(
            "sheetA" to listOf(baseRow(), targetRow()),
            "sheetB" to listOf(baseRow(7 to "base_db2", 10 to "t2"), targetRow(12 to "v_code", 13 to "v_name")))))
        assertEquals(2, r.sheets.size)
        assertNull(r.sheets[0].targets[0].codeField) // 对比行没填身份字段 → 交大模型推导
        assertEquals("v_code", r.sheets[1].targets[0].codeField) // 填了 → 锁定进映射
    }

    @Test
    fun `模版自洽 writeTemplate 产物可直接解析`() {
        val out = ByteArrayOutputStream()
        CompareImportExcelParser.writeTemplate(out)
        val r = CompareImportExcelParser.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, r.sheets.size)
        assertTrue(r.skippedSheets.isEmpty() && r.skippedRows.isEmpty())
        assertEquals("供(取)水量监测点", r.sheets[0].objectCategory)
        assertEquals(DbType.MYSQL, r.sheets[0].base.dbType)
        assertEquals("wr_mp_b", r.sheets[0].base.tableName)
        assertEquals(1, r.sheets[0].targets.size)
    }
}
