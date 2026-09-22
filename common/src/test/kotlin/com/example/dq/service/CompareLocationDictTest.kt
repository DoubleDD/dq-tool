package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 比对导出「库/模式名反查字典」(V74)纯函数 [CompareService.applyLocationDict] 单测。
 *
 * 覆盖:①`db.schema` 合并串命中;②schema 单值命中;③db 单值命中;④优先级(合并键优先于单值);
 * ⑤大小写敏感不命中;⑥命中后空段省略(空串转 空/null);⑦未命中原样返回;⑧空字典原样返回。
 */
class CompareLocationDictTest {

    private fun dictOf(vararg rows: Pair<String, Pair<String?, String?>>) = linkedMapOf(*rows)

    @Test
    fun `合并键命中 db点schema`() {
        val dict = dictOf("旧库.旧模式" to ("真实库" to "真实模式"))
        assertEquals("真实库" to "真实模式",
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
    }

    @Test
    fun `schema 单值命中`() {
        val dict = dictOf("旧模式" to ("真实库" to "真实模式"))
        assertEquals("真实库" to "真实模式",
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
    }

    @Test
    fun `db 单值命中(schema 无匹配)`() {
        val dict = dictOf("旧库" to ("真实库" to "真实模式"))
        assertEquals("真实库" to "真实模式",
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
    }

    @Test
    fun `优先级 合并键优先于 schema 与 db 单值`() {
        val dict = dictOf(
            "旧库.旧模式" to ("合并库" to "合并模式"),
            "旧模式" to ("schema库" to "schema模式"),
            "旧库" to ("db库" to "db模式"))
        assertEquals("合并库" to "合并模式",
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
        // 合并键不在字典时落到 schema 单值
        assertEquals("schema库" to "schema模式",
            CompareService.applyLocationDict(dict, "别的库", "旧模式"))
        // schema 单值也不在时落到 db 单值
        assertEquals("db库" to "db模式",
            CompareService.applyLocationDict(dict, "旧库", "别的模式"))
    }

    @Test
    fun `db 为空时合并键不带点 按 schema 单值查`() {
        val dict = dictOf("旧模式" to ("真实库" to "真实模式"))
        assertEquals("真实库" to "真实模式",
            CompareService.applyLocationDict(dict, "", "旧模式"))
    }

    @Test
    fun `大小写敏感 不命中`() {
        val dict = dictOf("TestDB.TestSchema" to ("真实库" to "真实模式"))
        // db 段大小写不同:合并键/schema 单值/db 单值都不命中,原样返回
        assertEquals("testdb" to "TestSchema",
            CompareService.applyLocationDict(dict, "testdb", "TestSchema"))
        assertEquals("TestDB" to "testschema",
            CompareService.applyLocationDict(dict, "TestDB", "testschema"))
    }

    @Test
    fun `键与值 trim 后匹配`() {
        val dict = dictOf("旧库.旧模式" to ("  真实库  " to " 真实模式 "))
        // 查询侧 db/schema 带空白也 trim 后匹配
        assertEquals("真实库" to "真实模式",
            CompareService.applyLocationDict(dict, " 旧库 ", " 旧模式 "))
    }

    @Test
    fun `命中后空段省略 空串转 空或null`() {
        val dict = dictOf(
            "只有库" to ("真实库" to ""),
            "只有模式" to ("" to "真实模式"))
        assertEquals("真实库" to null,
            CompareService.applyLocationDict(dict, "只有库", "任意"))
        assertEquals("" to "真实模式",
            CompareService.applyLocationDict(dict, "只有模式", "任意"))
    }

    @Test
    fun `未命中原样返回`() {
        val dict = dictOf("别的" to ("真实库" to "真实模式"))
        assertEquals("旧库" to "旧模式",
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
        assertEquals("旧库" to null,
            CompareService.applyLocationDict(dict, "旧库", null))
    }

    @Test
    fun `空字典原样返回`() {
        assertEquals("旧库" to "旧模式",
            CompareService.applyLocationDict(emptyMap(), "旧库", "旧模式"))
    }
}
