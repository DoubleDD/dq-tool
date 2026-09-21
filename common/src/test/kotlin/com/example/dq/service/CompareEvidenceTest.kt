package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 佐证字段:识别规则 / 取值比较三态 / 负证据分级 / LLM 兜底解析 */
class CompareEvidenceTest {

    private fun hint(name: String, comment: String? = null) = CompareEvidence.FieldHint(name, comment)

    // ---------- 识别规则 ----------

    @Test
    fun `规则识别 名称与注释命中 一类只取一个`() {
        val fields = listOf(
            hint("RES_CODE", "对象编码"),
            hint("AD_CODE", null),                       // 名称命中行政区划
            hint("RES_LOC", "水库所在位置"),               // 名称+注释命中位置
            hint("RES_RV", null),                        // 名称命中河流
            hint("AD_NAME", "行政区划名称"),               // 行政区划已取 AD_CODE,不再取
        )
        val result = CompareEvidence.detectByRule(fields)
        assertEquals("AD_CODE", result[EvidenceKind.REGION])
        assertEquals("RES_LOC", result[EvidenceKind.PLACE])
        assertEquals("RES_RV", result[EvidenceKind.RIVER])
    }

    @Test
    fun `规则识别 经纬度不入选 全不命中返回空`() {
        val fields = listOf(
            hint("LOW_LEFT_LONG", "左下角经度"),
            hint("LOW_LEFT_LAT", "左下角纬度"),
            hint("TOT_CAP", "总库容"),
        )
        assertTrue(CompareEvidence.detectByRule(fields).isEmpty())
    }

    @Test
    fun `规则识别 注释命中`() {
        val result = CompareEvidence.detectByRule(listOf(hint("COL_A", "行政区划代码")))
        assertEquals("COL_A", result[EvidenceKind.REGION])
    }

    // ---------- 行政区划比较 ----------

    @Test
    fun `区划比较 数字码按6位县级前缀`() {
        assertEquals(EvidenceVerdict.MATCH, CompareEvidence.regionVerdict("210403", "210403000000"))
        assertEquals(EvidenceVerdict.MATCH, CompareEvidence.regionVerdict("210403", "210403"))
        assertEquals(EvidenceVerdict.CONFLICT, CompareEvidence.regionVerdict("210403", "210105"))
        // 不足 6 位精确比
        assertEquals(EvidenceVerdict.CONFLICT, CompareEvidence.regionVerdict("2104", "2105"))
    }

    @Test
    fun `区划比较 名称归一化相等或包含`() {
        assertEquals(EvidenceVerdict.MATCH, CompareEvidence.regionVerdict("抚顺市望花区", "望花区"))
        assertEquals(EvidenceVerdict.CONFLICT, CompareEvidence.regionVerdict("抚顺市望花区", "沈阳市和平区"))
    }

    @Test
    fun `区划比较 一码一名与缺失均中性`() {
        assertEquals(EvidenceVerdict.NEUTRAL, CompareEvidence.regionVerdict("210403", "望花区"))
        assertEquals(EvidenceVerdict.NEUTRAL, CompareEvidence.regionVerdict(null, "210403"))
        assertEquals(EvidenceVerdict.NEUTRAL, CompareEvidence.regionVerdict("  ", "210403"))
    }

    // ---------- 位置/河流比较(自由文本) ----------

    @Test
    fun `文本佐证 相等包含一致 完全无关冲突 缺失中性`() {
        assertEquals(EvidenceVerdict.MATCH, CompareEvidence.textVerdict("抚顺市望花区", "望花区"))
        assertEquals(EvidenceVerdict.CONFLICT, CompareEvidence.textVerdict("抚顺市望花区", "沈阳市和平区"))
        assertEquals(EvidenceVerdict.NEUTRAL, CompareEvidence.textVerdict(null, "望花区"))
        // 全半角/空白归一
        assertEquals(EvidenceVerdict.MATCH, CompareEvidence.textVerdict("浑河(干流)", "浑河"))
    }

    // ---------- 负证据分级 ----------

    private fun verdicts(region: EvidenceVerdict, place: EvidenceVerdict, river: EvidenceVerdict) =
        CompareEvidence.Verdicts(region, place, river)

    @Test
    fun `多属性独立冲突才强冲突 单属性冲突可仲裁`() {
        // 区划冲突 + 位置冲突 = 强冲突(确定性拆)
        assertTrue(verdicts(EvidenceVerdict.CONFLICT, EvidenceVerdict.CONFLICT, EvidenceVerdict.NEUTRAL).strongConflict())
        assertTrue(verdicts(EvidenceVerdict.CONFLICT, EvidenceVerdict.NEUTRAL, EvidenceVerdict.CONFLICT).strongConflict())
        // 仅区划冲突(位置一致/中性)= 单属性冲突,交模型仲裁
        assertTrue(verdicts(EvidenceVerdict.CONFLICT, EvidenceVerdict.MATCH, EvidenceVerdict.NEUTRAL).arbitrableConflict())
        assertFalse(verdicts(EvidenceVerdict.CONFLICT, EvidenceVerdict.MATCH, EvidenceVerdict.NEUTRAL).strongConflict())
        // 区划一致 + 位置冲突:不是强冲突(区划没冲突),也不进仲裁
        assertFalse(verdicts(EvidenceVerdict.MATCH, EvidenceVerdict.CONFLICT, EvidenceVerdict.NEUTRAL).strongConflict())
        assertFalse(verdicts(EvidenceVerdict.MATCH, EvidenceVerdict.CONFLICT, EvidenceVerdict.NEUTRAL).arbitrableConflict())
    }

    @Test
    fun `正证据加分 区划1分 位置河流各0点5`() {
        assertEquals(2.0, verdicts(EvidenceVerdict.MATCH, EvidenceVerdict.MATCH, EvidenceVerdict.MATCH).bonus())
        assertEquals(1.0, verdicts(EvidenceVerdict.MATCH, EvidenceVerdict.NEUTRAL, EvidenceVerdict.NEUTRAL).bonus())
        assertEquals(0.0, verdicts(EvidenceVerdict.NEUTRAL, EvidenceVerdict.NEUTRAL, EvidenceVerdict.NEUTRAL).bonus())
        assertEquals(0.5, verdicts(EvidenceVerdict.CONFLICT, EvidenceVerdict.MATCH, EvidenceVerdict.NEUTRAL).bonus())
    }

    // ---------- 名称路配对拦截(纯函数) ----------

    @Test
    fun `名称路配对多属性冲突被拆 编码路与单属性冲突不动`() {
        val evidence = mapOf(EvidenceKind.REGION to "ad", EvidenceKind.PLACE to "loc")
        val base = mapOf(
            "B1" to mapOf("name" to "石门水库", "ad" to "210403", "loc" to "抚顺"),
            "B2" to mapOf("name" to "红旗水库", "ad" to "210403", "loc" to "抚顺"),
            "B3" to mapOf("name" to "甲水库", "ad" to "210403", "loc" to "抚顺"))
        val target = mapOf(
            "T1" to mapOf("name" to "石门水库", "ad" to "210105", "loc" to "沈阳"),  // 多属性冲突 → 拆
            "T2" to mapOf("name" to "红旗水库", "ad" to "210105", "loc" to "抚顺"),  // 仅区划冲突 → 保留
            "T3" to mapOf("name" to "甲水库", "ad" to "999999", "loc" to "火星"))     // 编码路 → 不动
        val match = MatchResult(
            listOf(MatchedPair("B1", "T1", "NAME"), MatchedPair("B2", "T2", "NAME"),
                MatchedPair("B3", "T3", "CODE")),
            codeMatched = 1, nameMatched = 2)
        val (result, count) = stripStrongConflictNamePairs(match, base, target, evidence)
        assertEquals(1, count)
        assertEquals(2, result.pairs.size)
        assertEquals(1, result.nameMatched)
        assertTrue(result.pairs.none { it.baseKey == "B1" })
        assertTrue(result.pairs.any { it.baseKey == "B2" && it.by == "NAME" })
        assertTrue(result.pairs.any { it.baseKey == "B3" && it.by == "CODE" })
    }

    // ---------- LLM 兜底识别 ----------

    @Test
    fun `兜底识别prompt带字段清单 解析只认三类且在清单内`() {
        val fields = listOf(hint("AD_CODE"), hint("RES_LOC"), hint("TOT_CAP"))
        val prompt = CompareEvidence.buildDetectPrompt("LNS_RES", fields)
        assertTrue(prompt.contains("AD_CODE"))
        val result = CompareEvidence.parseDetectAnswer(
            """好的{"region":"ad_code","place":"RES_LOC","river":"NO_SUCH"}""", fields)
        assertEquals("AD_CODE", result[EvidenceKind.REGION])  // 忽略大小写,归一实际字段名
        assertEquals("RES_LOC", result[EvidenceKind.PLACE])
        assertNull(result[EvidenceKind.RIVER])                 // 不在清单内丢弃
        assertTrue(CompareEvidence.parseDetectAnswer("没有合适字段", fields).isEmpty())
        assertTrue(CompareEvidence.parseDetectAnswer("{bad json", fields).isEmpty())
    }
}
