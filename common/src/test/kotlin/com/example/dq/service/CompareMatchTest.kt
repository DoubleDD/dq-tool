package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.ColumnMeta
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.service.CompareMatchPrompts.MatchItem
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Types
import io.mockk.mockk

/**
 * 数据比对:对象对齐(匹配逻辑)与匹配逻辑 3 的大模型归一化补配单测。
 *
 * 覆盖:三种匹配逻辑的配对口径、老任务(仅编码)兼容、去重与空值处理、残余计算、
 * prompt 组装与响应解析容错,以及实例侧补配的限额/未配置/失败兜底(绝不炸任务)。
 */
class CompareMatchTest {

    private fun rowOf(vararg pairs: Pair<String, String?>): Map<String, String?> = linkedMapOf(*pairs)

    /** 基准/目标行 map 的键就是各自的「对象编码」列值(与 loadRows 口径一致) */
    private fun mapOf(vararg rows: Pair<String, Map<String, String?>>) = linkedMapOf(*rows)

    // ---------- 对象对齐:三种匹配逻辑 ----------

    @Test
    fun `老任务只按编码对齐`() {
        val base = mapOf(
            "R001" to rowOf("code" to "R001", "name" to "甲水库"),
            "R002" to rowOf("code" to "R002", "name" to "乙水库"))
        val target = mapOf(
            "R001" to rowOf("code" to "R001", "name" to "甲水库(改)"),
            "R002" to rowOf("code" to "R002", "name" to "乙水库"))
        val result = matchObjects(base, target, listOf("code"), null, MatchMode.LEGACY)
        assertEquals(2, result.codeMatched)
        assertEquals(0, result.nameMatched)
        assertTrue(result.pairs.all { it.by == "CODE" })
    }

    @Test
    fun `编码首尾空白归一 但区分大小写(历史口径)`() {
        val base = mapOf("r001" to rowOf("code" to " r001 ", "name" to "甲"))
        val result = matchObjects(base, mapOf("r001" to rowOf("code" to "r001", "name" to "甲")),
            listOf("code"), null, MatchMode.LEGACY)
        assertEquals(1, result.codeMatched)
        assertEquals("r001", result.pairs[0].targetKey)
        // 大小写不同视为不同对象(与老实现「拿编码值当行 map 的键」完全一致,历史结果不回归)
        val upper = matchObjects(base, mapOf("R001" to rowOf("code" to "R001", "name" to "甲")),
            listOf("code"), null, MatchMode.LEGACY)
        assertEquals(0, upper.codeMatched)
    }

    @Test
    fun `匹配逻辑1 编码与名称都相等才配对`() {
        val base = mapOf(
            "1" to rowOf("code" to "C1", "name" to "甲水库"),
            "2" to rowOf("code" to "C2", "name" to "乙水库"))
        val target = mapOf(
            "9" to rowOf("code" to "C1", "name" to "甲水库"),
            "8" to rowOf("code" to "C2", "name" to "乙水库(改)"))
        val result = matchObjects(base, target, listOf("code"), "name", MatchMode.EXACT)
        // 编码都对上;第二条名称不同仍算命中,名称差异在字段比较里体现为 DIFF
        assertEquals(2, result.codeMatched)
        assertEquals(0, result.nameMatched)
    }

    @Test
    fun `匹配逻辑2 编码配不上的残余再按名称配`() {
        val base = mapOf(
            "1" to rowOf("code" to "B-001", "name" to "甲水库"),
            "2" to rowOf("code" to "B-002", "name" to "乙水库"))
        val target = mapOf(
            "T-A" to rowOf("code" to "V-9001", "name" to "甲水库"),
            "T-B" to rowOf("code" to "B-002", "name" to "乙水库"))
        val result = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertEquals(1, result.codeMatched)
        assertEquals(1, result.nameMatched)
        assertEquals(listOf("CODE", "NAME"), result.pairs.map { it.by })
        assertEquals("T-A", result.pairs[1].targetKey)
    }

    @Test
    fun `名称忽略大小写与首尾空白`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "  甲水库 "))
        val result = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertEquals(1, result.nameMatched)
    }

    @Test
    fun `名称或编码为空都不参与配对`() {
        val base = mapOf(
            "1" to rowOf("code" to "B-1", "name" to null),
            "2" to rowOf("code" to null, "name" to "乙水库"),
            "3" to rowOf("code" to " ", "name" to "  "))
        val target = mapOf(
            "T-1" to rowOf("code" to "V-1", "name" to null),
            "T-2" to rowOf("code" to "V-2", "name" to "乙水库"))
        val result = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        // 只有基准 2 靠名称配上目标 T-2;名称为空的行不参与配对
        assertEquals(0, result.codeMatched)
        assertEquals(1, result.nameMatched)
        assertEquals("2", result.pairs[0].baseKey)
    }

    @Test
    fun `身份列为空的代理键行进名称配对 任意一边code空就用name`() {
        // 行 map 的键模拟 loadRows 给身份列为空行的行内代理键(NO_KEY_ROW_PREFIX+序号)
        val surrogateA = CompareService.NO_KEY_ROW_PREFIX + "1"
        val surrogateB = CompareService.NO_KEY_ROW_PREFIX + "2"
        val base = mapOf(
            "B-001" to rowOf("code" to "B-001", "name" to "甲水库"),
            surrogateB to rowOf("code" to null, "name" to "乙水库"))
        val target = mapOf(
            surrogateA to rowOf("code" to null, "name" to "甲水库"),
            "V-002" to rowOf("code" to "V-002", "name" to "乙水库"))
        val result = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        // 基准有码目标无码、目标有码基准无码,名称相同都配上(编码路对代理键无命中)
        assertEquals(0, result.codeMatched)
        assertEquals(2, result.nameMatched)
        assertEquals(listOf("NAME", "NAME"), result.pairs.map { it.by })
        assertEquals(surrogateA, result.pairs[0].targetKey)
        assertEquals(surrogateB, result.pairs[1].baseKey)
    }

    @Test
    fun `身份列为空的代理键行在仅编码口径下不成对`() {
        val surrogate = CompareService.NO_KEY_ROW_PREFIX + "1"
        val base = mapOf("B-001" to rowOf("code" to "B-001", "name" to "甲水库"))
        val target = mapOf(surrogate to rowOf("code" to null, "name" to "甲水库"))
        // EXACT/LEGACY 不做名称补配:代理键行留在残余(目标侧落多余),而不是静默消失
        val exact = matchObjects(base, target, listOf("code"), "name", MatchMode.EXACT)
        assertEquals(0, exact.pairs.size)
        val (baseResidue, targetResidue) = exact.residues(base, target)
        assertEquals(listOf("B-001"), baseResidue)
        assertEquals(listOf(surrogate), targetResidue)
    }

    @Test
    fun `一个键只配一次 不串行`() {
        val base = mapOf(
            "1" to rowOf("code" to "B-1", "name" to "同名"),
            "2" to rowOf("code" to "B-2", "name" to "同名"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "同名"))
        val result = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertEquals(1, result.nameMatched)
        assertEquals("1", result.pairs[0].baseKey)
    }

    @Test
    fun `匹配逻辑2 但没有名称字段时退化为仅编码`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水库"))
        val result = matchObjects(base, target, listOf("code"), null, MatchMode.CODE_THEN_NAME)
        assertEquals(0, result.codeMatched + result.nameMatched)
    }

    @Test
    fun `残余计算与配对结果互补`() {
        val base = mapOf(
            "1" to rowOf("code" to "B-1", "name" to "甲水库"),
            "2" to rowOf("code" to "B-2", "name" to "乙水库"))
        val target = mapOf(
            "T-1" to rowOf("code" to "B-1", "name" to "甲水库"),
            "T-9" to rowOf("code" to "V-9", "name" to "丙水库"))
        val match = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        val (baseResidue, targetResidue) = match.residues(base, target)
        assertEquals(listOf("2"), baseResidue)
        assertEquals(listOf("T-9"), targetResidue)
    }

    @Test
    fun `匹配逻辑归一 空按1 未知值报错`() {
        assertEquals(MatchMode.EXACT, CompareService.normalizeMatchMode(null))
        assertEquals(MatchMode.EXACT, CompareService.normalizeMatchMode("  "))
        assertEquals(MatchMode.CODE_THEN_NAME, CompareService.normalizeMatchMode("code_then_name"))
        assertEquals(MatchMode.CODE_NAME_LLM, CompareService.normalizeMatchMode("CODE_NAME_LLM"))
        assertThrows(IllegalArgumentException::class.java) { CompareService.normalizeMatchMode("FUZZY") }
    }

    @Test
    fun `匹配逻辑与名称字段要求`() {
        assertFalse(MatchMode.LEGACY.requiresName)
        assertTrue(MatchMode.EXACT.requiresName)
        assertTrue(MatchMode.CODE_THEN_NAME.requiresName)
        assertTrue(MatchMode.CODE_NAME_LLM.requiresName)
        // 老任务数据库值为空 → LEGACY;未知值同样回落 LEGACY(不炸历史数据)
        assertEquals(MatchMode.LEGACY, MatchMode.fromValue(null))
        assertEquals(MatchMode.LEGACY, MatchMode.fromValue(""))
        assertEquals(MatchMode.LEGACY, MatchMode.fromValue("啥也不是"))
        assertEquals(MatchMode.CODE_THEN_NAME, MatchMode.fromValue("CODE_THEN_NAME"))
    }

    // ---------- diffObjects 接受显式配对(名称/大模型配对两侧键不等) ----------

    @Test
    fun `显式配对两侧键不等也算命中 且 objectKey 取基准侧`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水库"))
        val match = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertEquals(1, match.pairs.size)
        val result = CompareService.diffObjects(base, target, listOf("code", "name"), listOf("code"),
            displayField = "name", appliedPairs = match.pairs)
        // 名称一致、编码不同 → 同一个对象(命中)但字段不一致(编码 DIFF)
        assertEquals(1, result.matchedCount)
        assertEquals(1, result.diff.size)
        assertEquals(1, result.nameMatched)
        assertEquals(0, result.missing.size)
        assertEquals(0, result.extra.size)
        assertEquals("1", result.diff[0].objectKey)
        assertEquals("NAME", result.diff[0].matchBy)
        assertEquals(1, result.fieldMismatchCount)
    }

    @Test
    fun `未配上的行按基准侧与目标侧各自成缺失与多余`() {
        val base = mapOf(
            "1" to rowOf("code" to "B-1", "name" to "甲水库"),
            "2" to rowOf("code" to "B-2", "name" to "乙水库"))
        val target = mapOf(
            "T-1" to rowOf("code" to "B-1", "name" to "甲水库"),
            "T-9" to rowOf("code" to "V-9", "name" to "丙水库"))
        val match = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        val result = CompareService.diffObjects(base, target, listOf("code", "name"), listOf("code"),
            displayField = "name", appliedPairs = match.pairs)
        assertEquals(1, result.same.size)
        assertEquals(1, result.missing.size)
        assertEquals("2", result.missing[0].objectKey)
        assertEquals(1, result.extra.size)
        assertEquals("T-9", result.extra[0].objectKey)
        assertEquals(1, result.codeMatched)
    }

    @Test
    fun `身份列为空的代理键行 objectKey 回落显示名 且编码差异体现为不一致`() {
        val surrogateT = CompareService.NO_KEY_ROW_PREFIX + "1"
        val surrogateB = CompareService.NO_KEY_ROW_PREFIX + "1"
        // 目标侧编码空的行靠名称配上基准行:DIFF(objectKey=基准侧行键),目标编码空 → 编码字段不一致
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf(surrogateT to rowOf("code" to null, "name" to "甲水库"))
        val matched = CompareService.diffObjects(base, target, listOf("code", "name"), listOf("code"),
            displayField = "name",
            appliedPairs = listOf(MatchedPair("1", surrogateT, "NAME")))
        assertEquals("1", matched.diff[0].objectKey)
        assertEquals("NAME", matched.diff[0].matchBy)
        assertEquals(1, matched.fieldMismatchCount) // 只有编码字段不一致

        // 目标侧编码空且配不上(名称不同)→ 多余,objectKey 回落显示名而不是代理键
        val base2 = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target2 = mapOf(surrogateT to rowOf("code" to null, "name" to "孤儿水库"))
        val extra = CompareService.diffObjects(base2, target2, listOf("code", "name"), listOf("code"),
            displayField = "name")
        assertEquals(1, extra.extra.size)
        assertEquals("孤儿水库", extra.extra[0].objectKey)

        // 基准侧编码空且配不上 → 缺失,objectKey 同样回落显示名
        val extra2 = CompareService.diffObjects(mapOf(surrogateB to rowOf("code" to null, "name" to "孤儿水库")),
            mapOf("T-1" to rowOf("code" to "V-1", "name" to "乙水库")),
            listOf("code", "name"), listOf("code"), displayField = "name")
        assertEquals(1, extra2.missing.size)
        assertEquals("孤儿水库", extra2.missing[0].objectKey)
    }

    @Test
    fun `同名歧义组识别 仅至少一侧多条同名才成组且编码已配行不参与`() {
        // 基准 2 个石门、目标 1 个(无码):名称首配 B1-T1,B2 缺失——一侧多条同名 → 歧义组
        val base = mapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "葫芦岛"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "朝阳"))
        val target = mapOf("T1" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"))
        val m = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        val groups = sameNameAmbiguousGroups(m.pairs, base, target, "name")
        assertEquals(setOf("石门"), groups.keys)
        assertEquals(listOf("B1", "B2"), groups.getValue("石门").baseKeys)
        assertEquals(listOf("T1"), groups.getValue("石门").targetKeys)

        // 双侧各 1 条同名:无歧义,不成组
        val base1 = mapOf("B1" to rowOf("code" to "B1", "name" to "石门"))
        val target1 = mapOf("T9" to rowOf("code" to null, "name" to "石门"))
        val m2 = matchObjects(base1, target1, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertTrue(sameNameAmbiguousGroups(m2.pairs, base1, target1, "name").isEmpty())

        // 目标侧同码行已按编码配上(不参与二轮):只剩基准侧 1 条游离 → 不成组
        val base3 = mapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门"),
            "B2" to rowOf("code" to "B2", "name" to "石门"))
        val target3 = mapOf("T1" to rowOf("code" to "B1", "name" to "石门"))
        val m3 = matchObjects(base3, target3, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertTrue(sameNameAmbiguousGroups(m3.pairs, base3, target3, "name").isEmpty())

        // 首轮大模型补配(只见过编码+名称)配错(朝阳↔葫芦岛)的同名行也进二轮:组内四行全参与
        val base4 = mapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "朝阳"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "葫芦岛"))
        val target4 = mapOf(
            "T1" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"),
            "T2" to rowOf("code" to null, "name" to "石门", "loc" to "朝阳"))
        val llmMispaired = MatchResult(listOf(MatchedPair("B1", "T1", "LLM")), 0, 0, 1)
        val groups4 = sameNameAmbiguousGroups(llmMispaired.pairs, base4, target4, "name")
        assertEquals(listOf("B1", "B2"), groups4.getValue("石门").baseKeys)
        assertEquals(listOf("T1", "T2"), groups4.getValue("石门").targetKeys)
        // 二轮按 loc 重画:错配的 LLM 对被摘除,B1 改配 T2;计数口径由 pairs 重算(LLM→NAME)
        val redrawn = applySameNameRefine(llmMispaired.pairs, groups4,
            mapOf("石门" to listOf("B1" to "T2", "B2" to "T1")))
        assertEquals(listOf("B1" to "T2", "B2" to "T1"),
            redrawn.filter { it.by == "NAME" }.map { it.baseKey to it.targetKey })
        assertTrue(redrawn.none { it.by == "LLM" })
    }

    @Test
    fun `二轮消歧按其余字段重配 摘除首轮名称首配`() {
        // 首轮名称首配是顺序配对(B1-T1),但按 loc 看正确配对是 B1-T2、B2-T1
        val base = mapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "葫芦岛"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "朝阳"))
        val target = mapOf(
            "T1" to rowOf("code" to null, "name" to "石门", "loc" to "朝阳"),
            "T2" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"))
        val m = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_THEN_NAME)
        assertEquals(listOf("B1" to "T1"), m.pairs.map { it.baseKey to it.targetKey })
        val groups = sameNameAmbiguousGroups(m.pairs, base, target, "name")
        val refined = applySameNameRefine(m.pairs, groups,
            mapOf("石门" to listOf("B1" to "T2", "B2" to "T1")))
        assertEquals(setOf("B1" to "T2", "B2" to "T1"),
            refined.filter { it.by == "NAME" }.map { it.baseKey to it.targetKey }.toSet())
        // 跨组乱配/重复配对被丢弃
        val bad = applySameNameRefine(m.pairs, groups,
            mapOf("石门" to listOf("B1" to "T2", "B1" to "T1", "B2" to "XX")))
        assertEquals(setOf("B1" to "T2"), bad.filter { it.by == "NAME" }.map { it.baseKey to it.targetKey }.toSet())
    }

    @Test
    fun `同名二轮消歧带全字段调用大模型并按结果重配`() {
        val base = linkedMapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "葫芦岛"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "朝阳"))
        val target = linkedMapOf(
            "T1" to rowOf("code" to null, "name" to "石门", "loc" to "朝阳"),
            "T2" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"))
        val prompts = ArrayList<String>()
        val env = env(aiConfigured = true) { _, _, u ->
            prompts.add(u)
            // 按 loc 交叉配对:B1(葫芦岛)→T2、B2(朝阳)→T1
            if (u.contains("石门")) "[{\"b\":1,\"t\":2},{\"b\":2,\"t\":1}]" else "[]"
        }
        val m = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM)
        val view = env.service.aiRefineSameNameGroupsForTest(m, base, target,
            listOf("code"), "name", listOf("code", "name", "loc"))
        assertEquals(setOf("B1" to "T2", "B2" to "T1"), view.pairs.map { it.baseKey to it.targetKey }.toSet())
        assertEquals(null, view.note)
        // prompt 带全字段取值(同名 + loc),供模型区分同名不同对象
        assertEquals(1, prompts.size)
        assertTrue(prompts[0].contains("均为「石门」"), prompts[0])
        assertTrue(prompts[0].contains("loc=「葫芦岛」"), prompts[0])
        assertTrue(prompts[0].contains("loc=「朝阳」"), prompts[0])
    }

    @Test
    fun `同名二轮消歧大模型失败保持首轮配对并记说明`() {
        val base = linkedMapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "葫芦岛"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "朝阳"))
        val target = linkedMapOf(
            "T1" to rowOf("code" to null, "name" to "石门", "loc" to "朝阳"),
            "T2" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"))
        val env = env(aiConfigured = true) { _, _, u -> if (u.contains("石门")) throw RuntimeException("超时") else "[]" }
        val m = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM)
        val view = env.service.aiRefineSameNameGroupsForTest(m, base, target,
            listOf("code"), "name", listOf("code", "name", "loc"))
        assertEquals(m.pairs, view.pairs)   // 首轮配对原样保留(引用相等)
        assertTrue(view.failed)
        assertTrue(view.note!!.contains("同名二轮消歧部分组失败"), view.note)
    }

    @Test
    fun `同名二轮消歧未配置大模型时不调用并给说明`() {
        val base = linkedMapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门"),
            "B2" to rowOf("code" to "B2", "name" to "石门"))
        val target = linkedMapOf("T1" to rowOf("code" to null, "name" to "石门"))
        var called = 0
        val env = env(aiConfigured = false) { _, _, _ -> called++; "[]" }
        val m = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM)
        val view = env.service.aiRefineSameNameGroupsForTest(m, base, target,
            listOf("code"), "name", listOf("code", "name"))
        assertEquals(0, called)
        assertEquals(m.pairs, view.pairs)
        assertTrue(view.note!!.contains("未配置大模型"), view.note)
    }

    @Test
    fun `同名消歧prompt带全字段取值与空值占位`() {
        val prompt = CompareMatchPrompts.buildSameNameRefinePrompt("石门", listOf("code", "name", "loc"),
            listOf(CompareMatchPrompts.SameNameItem(1, "B1", "石门",
                listOf("loc" to "葫芦岛", "grade" to null))),
            listOf(CompareMatchPrompts.SameNameItem(1, null, "石门",
                listOf("loc" to "朝阳", "grade" to "V"))))
        assertTrue(prompt.contains("均为「石门」"))
        assertTrue(prompt.contains("code=B1"))
        assertTrue(prompt.contains("loc=「葫芦岛」"))
        assertTrue(prompt.contains("grade=「(空)」"))
        assertTrue(prompt.contains("code=B1") && prompt.contains("输出同一个对象的配对数组"))
    }

    @Test
    fun `不传配对时退化为旧口径 两侧键相等才算命中`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水库"))
        val result = CompareService.diffObjects(base, target, listOf("code", "name"), listOf("code"),
            displayField = "name")
        assertEquals(0, result.matchedCount)
        assertEquals(1, result.missing.size)
        assertEquals(1, result.extra.size)
        assertNull(result.diff.firstOrNull()?.matchBy)
    }

    @Test
    fun `防御脏配对 配对里任一侧不存在的条目被忽略`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水库"))
        val bogus = listOf(
            MatchedPair("404", "T-1", "LLM"),
            MatchedPair("1", "404", "LLM"))
        val result = CompareService.diffObjects(base, target, listOf("code"), listOf("code"), appliedPairs = bogus)
        assertEquals(0, result.matchedCount)
        assertEquals(1, result.missing.size)
        assertEquals(1, result.extra.size)
    }

    @Test
    fun `字段名归一与文本型判定沿用旧口径`() {
        assertTrue(CompareService.isTextType(column("name", Types.VARCHAR)))
        assertTrue(CompareService.isTextType(column("remark", Types.CLOB)))
        assertFalse(CompareService.isTextType(column("amount", Types.DECIMAL)))
    }

    // ---------- 匹配逻辑 3:prompt 组装与响应解析 ----------

    @Test
    fun `prompt 带上双侧字段名与序号`() {
        val prompt = CompareMatchPrompts.buildMatchPrompt("code", "name", "厂商编码", "厂商名称",
            listOf(MatchItem(1, "B-001", "甲水库"), MatchItem(2, null, "乙水库")),
            listOf(MatchItem(1, "V-9", "甲水库(改)")))
        assertTrue(prompt.contains("基准清单(共 2 条)"))
        assertTrue(prompt.contains("目标清单(共 1 条)"))
        assertTrue(prompt.contains("1. code=B-001 | name=甲水库"))
        // 编码为空时只渲染名称
        assertTrue(prompt.contains("2. name=乙水库"))
        assertTrue(prompt.contains("厂商编码=V-9 | 厂商名称=甲水库(改)"))
        assertTrue(prompt.contains("只输出 JSON 数组本身"))
    }

    @Test
    fun `prompt 空清单也标注 并对超长标识截断`() {
        val long = "甲".repeat(200)
        val prompt = CompareMatchPrompts.buildMatchPrompt("code", "name", "code", "name",
            listOf(MatchItem(1, "x", long)), emptyList())
        assertTrue(prompt.contains("目标清单(共 0 条)"))
        assertTrue(prompt.contains("甲".repeat(CompareMatchPrompts.MAX_IDENTIFIER_CHARS)))
        assertFalse(prompt.contains("甲".repeat(CompareMatchPrompts.MAX_IDENTIFIER_CHARS + 1)))
    }

    @Test
    fun `两侧都为空的行给出可读占位`() {
        val prompt = CompareMatchPrompts.buildMatchPrompt("code", "name", "code", "name",
            listOf(MatchItem(1, null, "  ")), emptyList())
        assertTrue(prompt.contains("1. (编号与名称均为空)"))
    }

    @Test
    fun `解析正常配对数组`() {
        val pairs = CompareMatchPrompts.parsePairs("""[{"b":1,"t":3},{"b":2,"t":5}]""", listOf(1, 2), listOf(3, 5))
        assertEquals(listOf(CompareMatchPrompts.Pair(1, 3), CompareMatchPrompts.Pair(2, 5)), pairs)
    }

    @Test
    fun `解析容忍前后多余文字`() {
        val answer = "好的,配对结果如下:\n[{\"b\":1,\"t\":2}]\n以上。"
        assertEquals(listOf(CompareMatchPrompts.Pair(1, 2)),
            CompareMatchPrompts.parsePairs(answer, listOf(1), listOf(2)))
    }

    @Test
    fun `序号兼容字符串 越界与重复被丢弃`() {
        val answer = """[{"b":"1","t":"2"},{"b":99,"t":1},{"b":1,"t":2},{"b":2,"t":2}]"""
        assertEquals(listOf(CompareMatchPrompts.Pair(1, 2)),
            CompareMatchPrompts.parsePairs(answer, listOf(1, 2), listOf(2)))
    }

    @Test
    fun `坏 JSON 与空数组返回空配对 不抛异常`() {
        assertEquals(emptyList<CompareMatchPrompts.Pair>(),
            CompareMatchPrompts.parsePairs("我不确定", listOf(1), listOf(1)))
        assertEquals(emptyList<CompareMatchPrompts.Pair>(),
            CompareMatchPrompts.parsePairs("[{b:1,t:2", listOf(1), listOf(2)))
        assertEquals(emptyList<CompareMatchPrompts.Pair>(),
            CompareMatchPrompts.parsePairs("[]", listOf(1), listOf(2)))
        assertEquals(emptyList<CompareMatchPrompts.Pair>(),
            CompareMatchPrompts.parsePairs("""[{"b":null,"t":1}]""", listOf(1), listOf(1)))
    }

    // ---------- 匹配逻辑 3:实例侧补配(未配置/限额/落地/失败兜底) ----------

    @Test
    fun `一对都没有时不做任何调用`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val match = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM)
        var called = false
        val service = env(aiConfigured = true) { _, _, _ -> called = true; "[]" }
        val result = service.service.aiMatchResiduesForTest(match, base, target, listOf("code"), "name")
        assertTrue(result.pairs.isEmpty())
        assertFalse(called)
    }

    @Test
    fun `未配置大模型时给出说明且不调用`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水库(改)"))
        var called = false
        val service = env(aiConfigured = false) { _, _, _ -> called = true; "[]" }
        val result = service.service.aiMatchResiduesForTest(
            matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM),
            base, target, listOf("code"), "name")
        assertFalse(called)
        assertTrue(result.note!!.contains("未配置大模型"))
    }

    @Test
    fun `配对结果落地成 LLM 来源`() {
        // 四条名称都不同 → 编码/名称两路一条都配不上,全量进模型
        val base = mapOf(
            "1" to rowOf("code" to "B-1", "name" to "甲水库"),
            "2" to rowOf("code" to "B-2", "name" to "乙水库"))
        val target = mapOf(
            "T-1" to rowOf("code" to "V-1", "name" to "甲水库(改)"),
            "T-2" to rowOf("code" to "V-2", "name" to "乙水库(改)"))
        val base0 = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM)
        assertEquals(0, base0.codeMatched + base0.nameMatched)
        val service = env(aiConfigured = true) { _, _, prompt ->
            assertTrue(prompt.contains("甲水库"))
            """[{"b":1,"t":1},{"b":2,"t":2}]"""
        }
        val result = service.service.aiMatchResiduesForTest(base0, base, target, listOf("code"), "name")
        assertEquals(2, result.pairs.size)
        assertTrue(result.pairs.all { it.by == "LLM" })
        assertEquals(listOf("1", "2"), result.pairs.map { it.baseKey })
        assertNull(result.note)
    }

    @Test
    fun `调用失败只跳过该批 不抛异常`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水库(改)"))
        val base0 = matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM)
        val service = env(aiConfigured = true) { _, _, _ -> throw IllegalStateException("HTTP 500") }
        val result = service.service.aiMatchResiduesForTest(base0, base, target, listOf("code"), "name")
        assertTrue(result.pairs.isEmpty())
        assertTrue(result.failed)
        assertTrue(result.note!!.contains("部分批次失败"))
    }

    @Test
    fun `残余超过上限时不做补配并给出说明`() {
        val base = linkedMapOf<String, Map<String, String?>>()
        val target = linkedMapOf<String, Map<String, String?>>()
        for (i in 1..(CompareService.LLM_RESIDUE_LIMIT + 1)) {
            base["B$i"] = rowOf("code" to "B$i", "name" to "基准$i")
            target["T$i"] = rowOf("code" to "T$i", "name" to "目标$i")
        }
        var called = false
        val service = env(aiConfigured = true) { _, _, _ -> called = true; "[]" }
        val result = service.service.aiMatchResiduesForTest(
            matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM),
            base, target, listOf("code"), "name")
        assertTrue(result.pairs.isEmpty())
        assertTrue(result.note!!.contains("残余对象过多"))
        assertFalse(called)
    }

    @Test
    fun `大批量残余按上限分批调用`() {
        // 基准 7 条 / 目标 1200 条 → 单批上限 = MAX_PAIRS_PER_REQUEST / 1200 = 4,取下限 5 → 分 2 批
        val base = linkedMapOf<String, Map<String, String?>>()
        val target = linkedMapOf<String, Map<String, String?>>()
        for (i in 1..7) base["B$i"] = rowOf("code" to "B$i", "name" to "基准$i")
        for (i in 1..1200) target["T$i"] = rowOf("code" to "T$i", "name" to "目标$i")
        var calls = 0
        val service = env(aiConfigured = true) { _, _, _ -> calls++; "[]" }
        service.service.aiMatchResiduesForTest(
            matchObjects(base, target, listOf("code"), "name", MatchMode.CODE_NAME_LLM),
            base, target, listOf("code"), "name")
        assertEquals(2, calls)
    }

    // ---------- 测试环境 ----------

    /** 只为调用补配逻辑的最小 CompareService:真实 H2 上的 AiConfigService + 注入的 fake LLM 调用点 */
    private class Env(val service: CompareService, val aiConfigService: AiConfigService)

    /**
     * 造一个可用配置的 CompareService:
     * aiConfigured=true 时在 H2 里写入 baseUrl/apiKey/model,让 [AiConfigService.findConfig] 返回配置
     */
    private fun env(aiConfigured: Boolean,
                    chat: (AiConfigService.Config, String, String) -> String): Env {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:compare-match-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        val config = AppConfig(dataDir = Files.createTempDirectory("compare-match-test"))
        val aiConfigService = AiConfigService(AiConfigRepository(jdbc), CryptoUtil(config),
            config, mockk(relaxed = true))
        if (aiConfigured) {
            aiConfigService.save(com.example.dq.model.AiConfigRequest(
                baseUrl = "http://localhost:9/v1", apiKey = "sk-test", model = "deepseek-chat",
                peakValleyEnabled = null, peakInputPrice = null, peakOutputPrice = null,
                valleyInputPrice = null, valleyOutputPrice = null, workPeriods = null,
                weekendValley = null))
        }
        val service = CompareService(
            repo = CompareRepository(jdbc),
            dataSourceService = mockk(relaxed = true),
            dialectFactory = com.example.dq.dialect.DialectFactory,
            metadataService = mockk(relaxed = true),
            systemSettingsService = mockk(relaxed = true),
            tableSystemRepo = mockk(relaxed = true),
            aiConfigService = aiConfigService,
            aiChat = { c, s, u -> chat(c, s, u) })
        return Env(service, aiConfigService)
    }

    private fun column(name: String, jdbcType: Int) =
        ColumnMeta(name, "t", jdbcType, primaryKey = false, pkSeq = 0, uniqueIndexFirst = false)
}
