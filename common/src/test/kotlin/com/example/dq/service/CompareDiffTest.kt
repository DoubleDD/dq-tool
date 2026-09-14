package com.example.dq.service

import com.example.dq.model.ColumnMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Types

/** 数据比对核心纯函数 diffObjects 单测(缺失/多余/不一致/全一致/数值归一/NULL 与空串/列缺失/边界) */
class CompareDiffTest {

    private fun rowOf(vararg pairs: Pair<String, String?>): Map<String, String?> = linkedMapOf(*pairs)

    private fun baseMapOf(vararg rows: Pair<String, Map<String, String?>>) = linkedMapOf(*rows)

    /** 真正不一致的字段:整行快照用 matched 显式标定,一致字段也带真实目标值 */
    private fun mismatchOf(diffs: List<com.example.dq.model.FieldDiff>) = diffs.filter { it.matched == false }

    @Test
    fun `基准有目标无记 MISSING`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to "甲"))
        val result = CompareService.diffObjects(base, emptyMap(), listOf("id", "name"), "id")
        assertEquals(1, result.missing.size)
        assertEquals("1", result.missing[0].objectKey)
        assertEquals(0, result.matchedCount)
        assertEquals(0, result.fieldMismatchCount)
        // 整行快照:基准值齐备、目标侧 value 为 null,matched 恒 false
        val snap = result.missing[0].diffs!!
        assertEquals(2, snap.size)
        assertTrue(snap.all { it.matched == false })
        assertEquals(listOf("1", "甲"), snap.map { it.base })
        assertTrue(snap.all { it.value == null })
    }

    @Test
    fun `目标有基准无记 EXTRA`() {
        val target = mapOf("9" to rowOf("id" to "9", "name" to "乙"))
        val result = CompareService.diffObjects(baseMapOf(), target, listOf("id", "name"), "id",
            displayField = "name")
        assertEquals(1, result.extra.size)
        assertEquals("9", result.extra[0].objectKey)
        assertEquals("乙", result.extra[0].objectName)
        // 整行快照:基准侧 base 为 null、目标值齐备,matched 恒 false
        val snap = result.extra[0].diffs!!
        assertEquals(listOf("9", "乙"), snap.map { it.value })
        assertTrue(snap.all { it.matched == false && it.base == null })
    }

    @Test
    fun `字段不一致记 DIFF 并逐字段展开`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to "甲", "amount" to "100"))
        val target = mapOf("1" to rowOf("id" to "1", "name" to "甲X", "amount" to "100"))
        val result = CompareService.diffObjects(base, target, listOf("id", "name", "amount"), "id")
        assertEquals(1, result.diff.size)
        assertEquals(1, result.matchedCount)
        assertEquals(1, result.fieldMismatchCount)
        // diff_json 落整行快照:全字段各一条,matched 标定是否一致,一致字段也保存目标真实值
        val diffs = result.diff[0].diffs!!
        assertEquals(3, diffs.size)
        assertEquals(listOf(true, false, true), diffs.map { it.matched })
        assertEquals("1", diffs[0].base)
        assertEquals("1", diffs[0].value)   // 一致字段仍有真实目标值(界面每格显示真实值的基础)
        val mismatch = mismatchOf(diffs)
        assertEquals(1, mismatch.size)
        assertEquals("name", mismatch[0].field)
        assertEquals("甲", mismatch[0].base)
        assertEquals("甲X", mismatch[0].value)
    }

    @Test
    fun `完全一致记 SAME`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to "甲"))
        val target = mapOf("1" to rowOf("id" to "1", "name" to "甲"))
        val result = CompareService.diffObjects(base, target, listOf("id", "name"), "id")
        assertEquals(1, result.same.size)
        assertEquals(1, result.matchedCount)
        assertEquals(0, result.fieldMismatchCount)
        assertNull(result.same[0].diffs)
        // 目标侧已比对单元格 = matched × 比对字段数 = 2,且均非空
        assertEquals(2, result.comparedCells)
        assertEquals(2, result.nonNullCells)
    }

    @Test
    fun `字符串比较忽略首尾空白`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to " 甲 "))
        val target = mapOf("1" to rowOf("id" to "1", "name" to "甲"))
        val result = CompareService.diffObjects(base, target, listOf("id", "name"), "id")
        assertEquals(1, result.same.size)
    }

    @Test
    fun `数值字段千分位与小数尾零归一后视为一致`() {
        val fields = listOf("id", "amount")
        val numeric = setOf("amount")
        // "38,333" vs "38333.0" vs 38333 三种写法同值
        val base = baseMapOf(
            "1" to rowOf("id" to "1", "amount" to "38,333"),
            "2" to rowOf("id" to "2", "amount" to "38333.0"),
        )
        val target = mapOf(
            "1" to rowOf("id" to "1", "amount" to "38333.0"),
            "2" to rowOf("id" to "2", "amount" to "38333"),
        )
        val result = CompareService.diffObjects(base, target, fields, "id", numericFields = numeric)
        assertEquals(2, result.same.size)
        assertEquals(0, result.fieldMismatchCount)
    }

    @Test
    fun `数值字段不同值记不一致`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "amount" to "100"))
        val target = mapOf("1" to rowOf("id" to "1", "amount" to "100.01"))
        val result = CompareService.diffObjects(base, target, listOf("id", "amount"), "id",
            numericFields = setOf("amount"))
        assertEquals(1, result.diff.size)
        assertEquals(1, result.fieldMismatchCount)
    }

    @Test
    fun `NULL 与空串视为一致`() {
        val base = baseMapOf(
            "1" to rowOf("id" to "1", "name" to null),
            "2" to rowOf("id" to "2", "name" to ""),
            "3" to rowOf("id" to "3", "name" to "  "),
        )
        val target = mapOf(
            "1" to rowOf("id" to "1", "name" to ""),
            "2" to rowOf("id" to "2", "name" to null),
            "3" to rowOf("id" to "3", "name" to null),
        )
        val result = CompareService.diffObjects(base, target, listOf("id", "name"), "id")
        assertEquals(3, result.same.size)
        // 空值单元格计入已比对但不计非空
        assertEquals(6, result.comparedCells)
        assertEquals(3, result.nonNullCells) // 仅 3 个 id 单元格非空
    }

    @Test
    fun `目标缺列按列缺失全部计不一致`() {
        val base = baseMapOf(
            "1" to rowOf("id" to "1", "name" to "甲"),
            "2" to rowOf("id" to "2", "name" to "乙"),
        )
        // 目标行 map 不含 name 列(列缺失)
        val target = mapOf(
            "1" to rowOf("id" to "1"),
            "2" to rowOf("id" to "2"),
        )
        val result = CompareService.diffObjects(base, target, listOf("id", "name"), "id")
        assertEquals(2, result.diff.size)
        assertEquals(2, result.fieldMismatchCount)
        assertEquals(CompareService.MISSING_COLUMN_MARK,
            mismatchOf(result.diff[0].diffs!!).single().value)
    }

    @Test
    fun `空基准表全部记 EXTRA 边界`() {
        val target = mapOf(
            "1" to rowOf("id" to "1", "name" to "甲"),
            "2" to rowOf("id" to "2", "name" to "乙"),
        )
        val result = CompareService.diffObjects(baseMapOf(), target, listOf("id", "name"), "id")
        assertEquals(2, result.extra.size)
        assertEquals(0, result.matchedCount)
        assertEquals(0, result.comparedCells)
        assertTrue(result.same.isEmpty() && result.diff.isEmpty() && result.missing.isEmpty())
    }

    @Test
    fun `显示名取显示字段值无则空串`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to " 甲 "))
        val result = CompareService.diffObjects(base, emptyMap(), listOf("id", "name"), "id",
            displayField = "name")
        assertEquals("甲", result.missing[0].objectName)
        val noDisplay = CompareService.diffObjects(base, emptyMap(), listOf("id", "name"), "id")
        assertEquals("", noDisplay.missing[0].objectName)
    }

    // ---------- 对象名称(显示名)字段解析:用户指定优先,未指定回退第一个文本型非主键字段 ----------

    private fun col(name: String, jdbc: Int, pk: Boolean = false) =
        ColumnMeta(name, "t", jdbc, pk, if (pk) 1 else 0, false)

    /** 基准表:id(BIGINT 主键) / reservoir_name(VARCHAR) / capacity(DECIMAL) / remark(CLOB) */
    private fun baseCols() = listOf(
        col("id", Types.BIGINT, pk = true),
        col("reservoir_name", Types.VARCHAR),
        col("capacity", Types.DECIMAL),
        col("remark", Types.CLOB),
    ).associateBy { it.name.lowercase() }

    @Test
    fun `未指定显示字段时取第一个文本型非主键字段`() {
        val fields = listOf("id", "capacity", "reservoir_name", "remark")
        assertEquals("reservoir_name",
            CompareService.resolveDisplayField(null, fields, baseCols(), "id"))
        // 空串视为未指定
        assertEquals("reservoir_name",
            CompareService.resolveDisplayField("  ", fields, baseCols(), "id"))
    }

    @Test
    fun `用户指定显示字段优先且忽略大小写归一为基准表实际列名`() {
        val fields = listOf("id", "capacity", "reservoir_name", "remark")
        assertEquals("remark", CompareService.resolveDisplayField("REMARK", fields, baseCols(), "id"))
        // 数值字段也允许手动指定(不做文本型限制)
        assertEquals("capacity", CompareService.resolveDisplayField("capacity", fields, baseCols(), "id"))
    }

    @Test
    fun `指定的显示字段不在比对字段内直接报错`() {
        val fields = listOf("id", "capacity")
        val e = assertThrows(IllegalArgumentException::class.java) {
            CompareService.resolveDisplayField("reservoir_name", fields, baseCols(), "id")
        }
        assertTrue(e.message!!.contains("对象名称字段必须在比对字段内"))
    }

    @Test
    fun `没有可用文本字段时显示字段为 null`() {
        // 全数值型(主键也不参与选取)
        val fields = listOf("id", "capacity")
        assertNull(CompareService.resolveDisplayField(null, fields, baseCols(), "id"))
        // 唯一的文本型字段就是主键:不能拿来当显示名
        val keyIsText = listOf(
            col("code", Types.VARCHAR, pk = true),
            col("capacity", Types.DECIMAL),
        ).associateBy { it.name.lowercase() }
        assertNull(CompareService.resolveDisplayField(null, listOf("code", "capacity"), keyIsText, "code"))
    }
}
