package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 批量设置描述:字典行解析与精准匹配纯函数(MetadataService 伴生对象) */
class SchemaDictMatchTest {

    @Test
    fun `空行跳过并计数,两侧 trim`() {
        val (dict, skipped) = MetadataService.parseDictRows(
            listOf(
                "  order_db " to " 订单系统 ",
                "" to "无库名",
                null to "空库名",
                "no_desc" to null,
                "blank_desc" to "   ",
            )
        )
        assertEquals(4, skipped)
        assertEquals(linkedMapOf("order_db" to "订单系统"), dict)
    }

    @Test
    fun `同名库后者覆盖且保持首次出现顺序`() {
        val (dict, skipped) = MetadataService.parseDictRows(
            listOf(
                "a" to "甲",
                "b" to "乙",
                "a" to "丙",
            )
        )
        assertEquals(0, skipped)
        assertEquals(listOf("a", "b"), dict.keys.toList())
        assertEquals("丙", dict["a"])
    }

    @Test
    fun `精准匹配 trim 后完全相等且大小写敏感`() {
        val dict = linkedMapOf(
            "order_db" to "订单",
            "Order_DB" to "大小写不同",
            "user_db" to "用户",
        )
        val match = MetadataService.matchDictDescriptions(dict, listOf("order_db", "user_db"))
        assertEquals(listOf("order_db", "user_db"), match.matched)
        assertEquals(listOf("Order_DB"), match.unmatched)
        assertEquals(1, match.unmatchedTotal)
    }

    @Test
    fun `未命中样例截断前 50 个但总数完整`() {
        val dict = LinkedHashMap<String, String>()
        for (i in 1..60) dict["db_$i"] = "desc$i"
        val match = MetadataService.matchDictDescriptions(dict, emptyList())
        assertTrue(match.matched.isEmpty())
        assertEquals(60, match.unmatchedTotal)
        assertEquals(50, match.unmatched.size)
        assertEquals("db_1", match.unmatched.first())
    }

    @Test
    fun `空字典无命中也无未命中`() {
        val match = MetadataService.matchDictDescriptions(emptyMap(), listOf("a", "b"))
        assertTrue(match.matched.isEmpty())
        assertTrue(match.unmatched.isEmpty())
        assertEquals(0, match.unmatchedTotal)
    }
}
