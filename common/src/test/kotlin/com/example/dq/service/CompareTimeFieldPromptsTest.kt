package com.example.dq.service

import com.example.dq.model.ColumnMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Types

/**
 * 「数据最新更新时间」时间字段探测纯函数单测:名称/注释规则(update 类优先于 create 类)、
 * 大模型回答解析容错(NONE/JSON/解释文字)、prompt 组装。
 */
class CompareTimeFieldPromptsTest {

    private fun col(name: String, type: String = "DATETIME", comment: String? = null,
                    jdbcType: Int = Types.TIMESTAMP): ColumnMeta =
        ColumnMeta(name, type, type, jdbcType, true, null, comment, false, 0, false)

    // ---------- 名称/注释规则 ----------

    @Test
    fun `update类字段优先于create类字段`() {
        val cols = listOf(
            col("id", "BIGINT", jdbcType = Types.BIGINT),
            col("create_time", "DATETIME", "创建时间"),
            col("update_time", "DATETIME", "更新时间"),
        )
        assertEquals("update_time", CompareTimeFieldPrompts.detectByRule(cols)?.name)
    }

    @Test
    fun `名称带分隔符与驼峰都命中`() {
        val cols = listOf(
            col("gmt_modified", "DATETIME"),
            col("id", "BIGINT", jdbcType = Types.BIGINT),
        )
        assertEquals("gmt_modified", CompareTimeFieldPrompts.detectByRule(cols)?.name)
    }

    @Test
    fun `没有update类字段时用create类字段`() {
        val cols = listOf(
            col("id", "BIGINT", jdbcType = Types.BIGINT),
            col("create_time", "DATETIME"),
            col("remark", "VARCHAR", "备注", Types.VARCHAR),
        )
        assertEquals("create_time", CompareTimeFieldPrompts.detectByRule(cols)?.name)
    }

    @Test
    fun `中文注释命中更新时间`() {
        val cols = listOf(
            col("id", "BIGINT", jdbcType = Types.BIGINT),
            col("mod_dt", "DATETIME", "最后修改时间"),
        )
        assertEquals("mod_dt", CompareTimeFieldPrompts.detectByRule(cols)?.name)
    }

    @Test
    fun `同类里日期时间类型字段优先于非时间类型`() {
        val cols = listOf(
            col("update_time_text", "VARCHAR", null, Types.VARCHAR),
            col("update_time", "DATETIME"),
        )
        assertEquals("update_time", CompareTimeFieldPrompts.detectByRule(cols)?.name)
    }

    @Test
    fun `没有时间字段返回null`() {
        val cols = listOf(
            col("id", "BIGINT", jdbcType = Types.BIGINT),
            col("name", "VARCHAR", "名称", Types.VARCHAR),
        )
        assertNull(CompareTimeFieldPrompts.detectByRule(cols))
    }

    // ---------- 大模型回答解析 ----------

    @Test
    fun `回答裸字段名与JSON包裹都能解析`() {
        val cols = listOf(col("id", "BIGINT", jdbcType = Types.BIGINT), col("update_time"))
        assertEquals("update_time", CompareTimeFieldPrompts.parseAnswer("update_time", cols)?.name)
        assertEquals("update_time", CompareTimeFieldPrompts.parseAnswer("""{"field":"update_time"}""", cols)?.name)
        assertEquals("update_time", CompareTimeFieldPrompts.parseAnswer("`UPDATE_TIME`", cols)?.name)
    }

    @Test
    fun `回答是整句解释时在文中找字段名`() {
        val cols = listOf(col("id", "BIGINT", jdbcType = Types.BIGINT), col("last_modify_time"))
        assertEquals("last_modify_time",
            CompareTimeFieldPrompts.parseAnswer("记录最新修改时间的字段是 last_modify_time。", cols)?.name)
    }

    @Test
    fun `回答NONE或字段不存在返回null`() {
        val cols = listOf(col("update_time"))
        assertNull(CompareTimeFieldPrompts.parseAnswer("NONE", cols))
        assertNull(CompareTimeFieldPrompts.parseAnswer("没有合适的字段,输出 NONE", cols))
        assertNull(CompareTimeFieldPrompts.parseAnswer("", cols))
        assertNull(CompareTimeFieldPrompts.parseAnswer("no_such_column", cols))
    }

    // ---------- 规则优先 + 大模型兜底 ----------

    @Test
    fun `规则命中时不调用大模型`() {
        var called = false
        val cols = listOf(col("update_time"))
        val picked = CompareService.pickLatestTimeColumn(cols, "t_reservoir") { _, _ ->
            called = true
            null
        }
        assertEquals("update_time", picked?.name)
        assertFalse(called)
    }

    @Test
    fun `规则未命中时用大模型挑出的字段`() {
        val cols = listOf(col("id", "BIGINT", jdbcType = Types.BIGINT), col("data_stamp", "DATETIME", "数据戳"))
        val picked = CompareService.pickLatestTimeColumn(cols, "t_reservoir") { _, candidates ->
            CompareTimeFieldPrompts.parseAnswer("data_stamp", candidates)
        }
        assertEquals("data_stamp", picked?.name)
    }

    @Test
    fun `规则未命中且未配置大模型时返回null`() {
        val cols = listOf(col("data_stamp", "DATETIME", "数据戳"))
        assertNull(CompareService.pickLatestTimeColumn(cols, "t_reservoir", null))
    }

    // ---------- prompt ----------

    @Test
    fun `prompt包含表名与字段名注释并约束输出`() {
        val cols = listOf(col("id", "BIGINT", "主键", Types.BIGINT), col("update_time", "DATETIME", "更新时间"))
        val prompt = CompareTimeFieldPrompts.buildPrompt("t_reservoir", cols)
        assertTrue(prompt.contains("t_reservoir"), prompt)
        assertTrue(prompt.contains("update_time"), prompt)
        assertTrue(prompt.contains("# 更新时间"), prompt)
        assertTrue(prompt.contains("NONE"), prompt)
    }
}
