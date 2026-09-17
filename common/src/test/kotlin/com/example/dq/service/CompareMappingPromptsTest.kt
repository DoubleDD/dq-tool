package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 数据比对·列级对比字段映射预生成的 prompt 组装与响应解析纯函数测试 */
class CompareMappingPromptsTest {

    private fun col(name: String, comment: String? = null) =
        ColumnMeta(name, "varchar(64)", 12, false, 0, false)

    private fun cols(vararg names: String) = names.map { col(it) }

    // ---------- prompt 组装 ----------

    @Test
    fun `prompt 含两侧表名 字段行与输出格式约束`() {
        val p = CompareMappingPrompts.buildMappingPrompt(
            "db1.reservoir", listOf(CompareMappingPrompts.ColumnItem("reservoir_code", "varchar(32)", "水库编码")),
            "db2.t_reservoir", listOf(CompareMappingPrompts.ColumnItem("res_code", "varchar(32)", null)))
        assertTrue("db1.reservoir" in p && "db2.t_reservoir" in p)
        assertTrue("- reservoir_code (varchar(32))  # 水库编码" in p)
        // 空注释省略注释段
        assertTrue("- res_code (varchar(32))" in p && "#" !in p.substringAfter("- res_code"))
        assertTrue("{\"基准字段1\":\"目标列1\"" in p)
    }

    @Test
    fun `超宽表截断到上限并标注`() {
        val many = (1..CompareMappingPrompts.MAX_COLUMNS_PER_PROMPT + 5).map {
            CompareMappingPrompts.ColumnItem("c$it", "int", null)
        }
        val p = CompareMappingPrompts.buildMappingPrompt("b.t1", many, "b.t2", many)
        assertTrue("以上仅列出前 ${CompareMappingPrompts.MAX_COLUMNS_PER_PROMPT} 个" in p)
        assertTrue("- c${CompareMappingPrompts.MAX_COLUMNS_PER_PROMPT + 5}" !in p)
    }

    // ---------- 响应解析 ----------

    @Test
    fun `正常解析并忽略大小写归一为实际列名`() {
        val answer = """{"RESERVOIR_CODE": "res_code", "reservoir_name": "RES_NAME"}"""
        val out = CompareMappingPrompts.parseMappingSuggest(
            answer, listOf("reservoir_code", "reservoir_name"), cols("res_code", "res_name"), "reservoir_code")
        assertEquals(mapOf("reservoir_code" to "res_code", "reservoir_name" to "res_name"), out)
    }

    @Test
    fun `丢弃不在比对字段内的基准字段与不存在的目标列`() {
        val answer = """{"reservoir_code": "res_code", "ghost_field": "res_name", "reservoir_name": "no_such_col"}"""
        val out = CompareMappingPrompts.parseMappingSuggest(
            answer, listOf("reservoir_code", "reservoir_name"), cols("res_code", "res_name"), "reservoir_code")
        assertEquals(mapOf("reservoir_code" to "res_code"), out)
    }

    @Test
    fun `同一目标列被重复映射时只保留先出现的条目`() {
        val answer = """{"reservoir_code": "res_code", "reservoir_name": "res_code"}"""
        val out = CompareMappingPrompts.parseMappingSuggest(
            answer, listOf("reservoir_code", "reservoir_name"), cols("res_code", "res_name"), "reservoir_code")
        assertEquals(mapOf("reservoir_code" to "res_code"), out)
    }

    @Test
    fun `主键漏映时按目标表同名列兜底补齐`() {
        val answer = """{"reservoir_name": "res_name"}"""
        val out = CompareMappingPrompts.parseMappingSuggest(
            answer, listOf("reservoir_code", "reservoir_name"), cols("RESERVOIR_CODE", "res_name"), "reservoir_code")
        assertEquals(mapOf("reservoir_name" to "res_name", "reservoir_code" to "RESERVOIR_CODE"), out)
    }

    @Test
    fun `主键已映射时不重复兜底 目标无同名列时不强补`() {
        val mapped = CompareMappingPrompts.parseMappingSuggest(
            """{"reservoir_code": "pk_col"}""",
            listOf("reservoir_code"), cols("pk_col", "reservoir_code"), "reservoir_code")
        assertEquals(mapOf("reservoir_code" to "pk_col"), mapped)
        // 目标侧没有与主键同名的列:不硬补,交由画布人工连
        val unmapped = CompareMappingPrompts.parseMappingSuggest(
            """{"reservoir_name": "res_name"}""",
            listOf("reservoir_code", "reservoir_name"), cols("res_name"), "reservoir_code")
        assertEquals(mapOf("reservoir_name" to "res_name"), unmapped)
    }

    @Test
    fun `主键字段不在比对字段内时不兜底也不抛异常`() {
        val out = CompareMappingPrompts.parseMappingSuggest(
            """{"reservoir_name": "res_name"}""",
            listOf("reservoir_name"), cols("res_name"), "reservoir_code")
        assertEquals(mapOf("reservoir_name" to "res_name"), out)
    }

    @Test
    fun `带多余文字的坏 JSON 容错 完全无法解析返回空`() {
        val out = CompareMappingPrompts.parseMappingSuggest(
            """说明:{"reservoir_code":"res_code"} 以上供参考""",
            listOf("reservoir_code"), cols("res_code"), "reservoir_code")
        assertEquals(mapOf("reservoir_code" to "res_code"), out)
        assertTrue(CompareMappingPrompts.parseMappingSuggest(
            "抱歉我无法完成", listOf("reservoir_code"), cols("res_code"), "reservoir_code").isEmpty())
    }

    // ---------- lockedFields(比对批量导入:表格给定的身份字段锁定,不进推导范围) ----------

    @Test
    fun `lockedFields 从待推导清单剔除并在输出约定里排除`() {
        val p = CompareMappingPrompts.buildMappingPrompt(
            "db1.reservoir",
            listOf(CompareMappingPrompts.ColumnItem("reservoir_code", "varchar(32)", "水库编码"),
                CompareMappingPrompts.ColumnItem("reservoir_name", "varchar(64)", "水库名称"),
                CompareMappingPrompts.ColumnItem("reservoir_type", "varchar(8)", null)),
            "db2.t_reservoir", listOf(CompareMappingPrompts.ColumnItem("res_code", "varchar(32)", null)),
            lockedFields = mapOf("reservoir_code" to "res_code"))
        // 锁定字段不出现在待映射清单,数量相应减少
        assertTrue("需要映射的字段(共 2 个)" in p)
        assertTrue("- reservoir_code" !in p)
        assertTrue("- reservoir_name" in p)
        // 输出约定里明示排除
        assertTrue("已人工锁定映射" in p && "\"reservoir_code\"→\"res_code\"" in p)
    }

    @Test
    fun `推导结果与锁定项合并后必含主键`() {
        val baseFields = listOf(
            CompareMappingPrompts.ColumnItem("reservoir_code", "varchar(32)", null),
            CompareMappingPrompts.ColumnItem("reservoir_name", "varchar(64)", null))
        val locked = mapOf("reservoir_code" to "res_code")
        val p = CompareMappingPrompts.buildMappingPrompt("b.t1", baseFields, "b.t2",
            cols("res_code", "res_name").map { CompareMappingPrompts.ColumnItem(it.name, it.displayType, null) },
            locked)
        // 锁定字段不在 prompt 里,模型不可能返回它;合并锁定项后映射必含主键
        assertTrue("reservoir_code" !in p.substringBefore("目标表"))
        val suggested = CompareMappingPrompts.parseMappingSuggest(
            """{"reservoir_name": "res_name"}""",
            listOf("reservoir_code", "reservoir_name"), cols("res_code", "res_name"), "reservoir_code")
        val merged = suggested + locked
        assertEquals(mapOf("reservoir_name" to "res_name", "reservoir_code" to "res_code"), merged)
        assertTrue(merged.keys.any { it.equals("reservoir_code", ignoreCase = true) })
    }

    // ---------- 对比模式归一 ----------

    @Test
    fun `对比模式归一 空为行级 未知值报错`() {
        assertEquals(CompareMode.ROW, CompareMode.normalize(null))
        assertEquals(CompareMode.ROW, CompareMode.normalize("  "))
        assertEquals(CompareMode.COLUMN, CompareMode.normalize("column"))
        assertEquals(CompareMode.COLUMN, CompareMode.normalize("Column"))
        assertThrows(IllegalArgumentException::class.java) { CompareMode.normalize("CELL") }
    }
}
