package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.example.dq.model.TableStat
import org.junit.jupiter.api.Test

import java.sql.Types

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** 表说明 prompt 组装(纯函数,不依赖大模型接口) */
class AiServiceTest {

    @Test
    fun `prompt包含表信息与字段要素`() {
        val table = TableStat("user_order", 12_500_000L, 1_280_000_000L, "订单表", "InnoDB")
        val cols = listOf(
            ColumnMeta("id", "bigint", "bigint(20)", Types.BIGINT, false, null, "主键", true, 1, false),
            ColumnMeta("mobile", "varchar", "varchar(20)", Types.VARCHAR, true, null, "手机号", false, 0, true),
            ColumnMeta("amount", "decimal", "decimal(10,2)", Types.DECIMAL, false, "0", "", false, 0, false))

        val prompt = AiService.buildTablePrompt(table, cols)

        assertTrue(prompt.contains("表名:user_order"))
        assertTrue(prompt.contains("表注释:订单表"))
        assertTrue(prompt.contains("引擎/表空间:InnoDB"))
        assertTrue(prompt.contains("约行数:12500000"))
        assertTrue(prompt.contains("字段(共 3 个)"))
        assertTrue(prompt.contains("- id bigint(20) [PK] 非空 — 主键"))
        assertTrue(prompt.contains("- mobile varchar(20) [UNI] — 手机号"))
        assertTrue(prompt.contains("- amount decimal(10,2) 非空"))
        assertFalse(prompt.contains("省略"))
    }

    @Test
    fun `空注释与空行数不出现在prompt中`() {
        val table = TableStat("t1", null, null)
        val prompt = AiService.buildTablePrompt(table, listOf(
            ColumnMeta("c1", "int", Types.INTEGER, false, 0, false)))

        assertFalse(prompt.contains("表注释"))
        assertFalse(prompt.contains("约行数"))
        assertTrue(prompt.contains("字段(共 1 个)"))
    }

    @Test
    fun `超过字段上限时截断并标注省略数量`() {
        val table = TableStat("wide_table", 1L, 1L)
        val cols = ArrayList<ColumnMeta>()
        for (i in 1..AiService.MAX_PROMPT_COLUMNS + 30) {
            cols.add(ColumnMeta("col_$i", "int", Types.INTEGER, false, 0, false))
        }

        val prompt = AiService.buildTablePrompt(table, cols)

        assertTrue(prompt.contains("字段(共 " + (AiService.MAX_PROMPT_COLUMNS + 30) + " 个)"))
        assertTrue(prompt.contains("col_" + AiService.MAX_PROMPT_COLUMNS))
        assertFalse(prompt.contains("col_" + (AiService.MAX_PROMPT_COLUMNS + 1) + " "))
        assertTrue(prompt.contains("其余 30 个字段省略"))
    }
}
