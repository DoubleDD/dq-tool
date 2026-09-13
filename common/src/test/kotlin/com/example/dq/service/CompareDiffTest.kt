package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 数据比对核心纯函数 diffObjects 单测(缺失/多余/不一致/全一致/数值归一/NULL 与空串/列缺失/边界) */
class CompareDiffTest {

    private fun rowOf(vararg pairs: Pair<String, String?>): Map<String, String?> = linkedMapOf(*pairs)

    private fun baseMapOf(vararg rows: Pair<String, Map<String, String?>>) = linkedMapOf(*rows)

    @Test
    fun `基准有目标无记 MISSING`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to "甲"))
        val result = CompareService.diffObjects(base, emptyMap(), listOf("id", "name"), "id")
        assertEquals(1, result.missing.size)
        assertEquals("1", result.missing[0].objectKey)
        assertEquals(0, result.matchedCount)
        assertEquals(0, result.fieldMismatchCount)
    }

    @Test
    fun `目标有基准无记 EXTRA`() {
        val target = mapOf("9" to rowOf("id" to "9", "name" to "乙"))
        val result = CompareService.diffObjects(baseMapOf(), target, listOf("id", "name"), "id",
            displayField = "name")
        assertEquals(1, result.extra.size)
        assertEquals("9", result.extra[0].objectKey)
        assertEquals("乙", result.extra[0].objectName)
    }

    @Test
    fun `字段不一致记 DIFF 并逐字段展开`() {
        val base = baseMapOf("1" to rowOf("id" to "1", "name" to "甲", "amount" to "100"))
        val target = mapOf("1" to rowOf("id" to "1", "name" to "甲X", "amount" to "100"))
        val result = CompareService.diffObjects(base, target, listOf("id", "name", "amount"), "id")
        assertEquals(1, result.diff.size)
        assertEquals(1, result.matchedCount)
        assertEquals(1, result.fieldMismatchCount)
        val diffs = result.diff[0].diffs!!
        assertEquals(1, diffs.size)
        assertEquals("name", diffs[0].field)
        assertEquals("甲", diffs[0].base)
        assertEquals("甲X", diffs[0].value)
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
        assertEquals(CompareService.MISSING_COLUMN_MARK, result.diff[0].diffs!![0].value)
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
}
