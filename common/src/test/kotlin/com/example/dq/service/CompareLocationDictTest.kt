package com.example.dq.service

import com.example.dq.service.CompareService.Companion.LocationDictEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 比对导出「库/模式名反查字典」(V74)纯函数 [CompareService.applyLocationDict] 单测。
 *
 * 覆盖:①`db.schema` 合并串命中;②schema 单值命中;③db 单值命中;④优先级(合并键优先于单值);
 * ⑤忽略大小写命中;⑥命中后空段省略(空串转 空/null);⑦未命中原样返回(系统名 null);
 * ⑧空字典原样返回;⑨真实系统名带出(命中给系统名/字典未配系统字段回落 null)。
 * 注:dict 键按 [CompareService] 读取侧(loadLocationDict)口径须为 trim + 小写归一后的键。
 */
class CompareLocationDictTest {

    private fun dictOf(vararg rows: Pair<String, LocationDictEntry>) = linkedMapOf(*rows)

    /** 只有库/模式的字典条目(等价老任务未配真实系统字段) */
    private fun entry(db: String, schema: String?) = LocationDictEntry(db, schema, null)

    @Test
    fun `合并键命中 db点schema`() {
        val dict = dictOf("旧库.旧模式" to entry("真实库", "真实模式"))
        assertEquals(entry("真实库", "真实模式"),
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
    }

    @Test
    fun `schema 单值命中`() {
        val dict = dictOf("旧模式" to entry("真实库", "真实模式"))
        assertEquals(entry("真实库", "真实模式"),
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
    }

    @Test
    fun `db 单值命中(schema 无匹配)`() {
        val dict = dictOf("旧库" to entry("真实库", "真实模式"))
        assertEquals(entry("真实库", "真实模式"),
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
    }

    @Test
    fun `优先级 合并键优先于 schema 与 db 单值`() {
        val dict = dictOf(
            "旧库.旧模式" to entry("合并库", "合并模式"),
            "旧模式" to entry("schema库", "schema模式"),
            "旧库" to entry("db库", "db模式"))
        assertEquals(entry("合并库", "合并模式"),
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
        // 合并键不在字典时落到 schema 单值
        assertEquals(entry("schema库", "schema模式"),
            CompareService.applyLocationDict(dict, "别的库", "旧模式"))
        // schema 单值也不在时落到 db 单值
        assertEquals(entry("db库", "db模式"),
            CompareService.applyLocationDict(dict, "旧库", "别的模式"))
    }

    @Test
    fun `db 为空时合并键不带点 按 schema 单值查`() {
        val dict = dictOf("旧模式" to entry("真实库", "真实模式"))
        assertEquals(entry("真实库", "真实模式"),
            CompareService.applyLocationDict(dict, "", "旧模式"))
    }

    @Test
    fun `忽略大小写 命中`() {
        // 字典键按读取侧口径为小写归一;查询侧(任务落库的库/模式名)大小写不同也命中
        val dict = dictOf("qysglpt_wi_user_wi_user" to entry("真实库", "真实模式"))
        assertEquals(entry("真实库", "真实模式"),
            CompareService.applyLocationDict(dict, "", "qysglpt_WI_USER_WI_USER"))
        assertEquals(entry("真实库", "真实模式"),
            CompareService.applyLocationDict(dict, "", "QYSGLPT_wi_user_wi_user"))
    }

    @Test
    fun `键与值 trim 后匹配`() {
        val dict = dictOf("旧库.旧模式" to LocationDictEntry("  真实库  ", " 真实模式 ", " 真实系统 "))
        // 查询侧 db/schema 带空白也 trim 后匹配
        assertEquals(LocationDictEntry("真实库", "真实模式", "真实系统"),
            CompareService.applyLocationDict(dict, " 旧库 ", " 旧模式 "))
    }

    @Test
    fun `命中后空段省略 空串转 空或null`() {
        val dict = dictOf(
            "只有库" to entry("真实库", ""),
            "只有模式" to entry("", "真实模式"))
        assertEquals(entry("真实库", null),
            CompareService.applyLocationDict(dict, "只有库", "任意"))
        assertEquals(entry("", "真实模式"),
            CompareService.applyLocationDict(dict, "只有模式", "任意"))
    }

    @Test
    fun `命中带出真实系统名 空串转 null`() {
        val dict = dictOf(
            "带系统" to LocationDictEntry("真实库", "真实模式", "真实系统"),
            "空系统" to LocationDictEntry("真实库", "真实模式", ""))
        assertEquals(LocationDictEntry("真实库", "真实模式", "真实系统"),
            CompareService.applyLocationDict(dict, "带系统", "任意"))
        // 字典系统名为空:命中库/模式替换,系统名 null(导出侧走原回落链)
        assertEquals(LocationDictEntry("真实库", "真实模式", null),
            CompareService.applyLocationDict(dict, "空系统", "任意"))
    }

    @Test
    fun `未命中原样返回 系统名 null`() {
        val dict = dictOf("别的" to LocationDictEntry("真实库", "真实模式", "真实系统"))
        assertEquals(LocationDictEntry("旧库", "旧模式", null),
            CompareService.applyLocationDict(dict, "旧库", "旧模式"))
        assertEquals(LocationDictEntry("旧库", null, null),
            CompareService.applyLocationDict(dict, "旧库", null))
    }

    @Test
    fun `空字典原样返回`() {
        assertEquals(LocationDictEntry("旧库", "旧模式", null),
            CompareService.applyLocationDict(emptyMap(), "旧库", "旧模式"))
    }
}
