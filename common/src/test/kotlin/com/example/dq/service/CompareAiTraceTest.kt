package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.AiConfigRequest
import com.example.dq.model.CompareAiTrace
import com.example.dq.model.PendingReason
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.CompareAiTraceRepository
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.util.CryptoUtil
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Collections

/**
 * 数据比对 AI 判定留痕单测(compare_ai_trace,AI 用量库):
 * 补配/同名消歧批次留痕(成功与失败都落)、仓储读写与级联删除、listAiTraces 视图解析。
 * 批量导入映射推导留痕见 [CompareImportServiceTest]。
 */
class CompareAiTraceTest {

    private val mapper = jacksonObjectMapper()

    private fun rowOf(vararg pairs: Pair<String, String?>): Map<String, String?> = linkedMapOf(*pairs)
    private fun mapOf(vararg rows: Pair<String, Map<String, String?>>) = linkedMapOf(*rows)

    @Suppress("UNCHECKED_CAST")
    private fun parseResult(trace: CompareAiTrace): Map<String, Any?> =
        mapper.readValue(trace.resultJson, Map::class.java) as Map<String, Any?>

    // ---------- 匹配逻辑 3:补配留痕 ----------

    @Test
    fun `补配成功落 RESIDUE 留痕 配对与未配上基准逐条还原 code name`() {
        // 四条名称互为错别字(归一化也不相等)→ 编码/名称两路配不上,全量进模型裁决
        val base = mapOf(
            "1" to rowOf("code" to "B-1", "name" to "甲水库"),
            "2" to rowOf("code" to "B-2", "name" to "乙水库"))
        val target = mapOf(
            "T-1" to rowOf("code" to "V-1", "name" to "甲水厍"),
            "T-2" to rowOf("code" to "V-2", "name" to "乙水厍"))
        val traces = Collections.synchronizedList(ArrayList<CompareAiTrace>())
        // 模型只配上第 1 条基准,第 2 条留未配
        val env = env(aiConfigured = true, traceRecorder = { traces.add(it) }) { _, _, _ ->
            """[{"b":1,"t":1}]"""
        }
        val m = matchObjects(base, target, listOf("code"), listOf("name"), MatchMode.CODE_NAME_LLM)
        env.service.aiMatchResiduesForTest(m, base, target, listOf("code"), listOf("name"),
            jobId = 11, targetId = 22, targetLabel = "vendor_db.dbo.t_reservoir")
        val trace = traces.single()
        assertEquals(11, trace.jobId)
        assertEquals(22, trace.targetId)
        assertEquals("vendor_db.dbo.t_reservoir", trace.targetLabel)
        assertEquals("COMPARE_MATCH", trace.scene)
        assertEquals(CompareAiTrace.STAGE_RESIDUE, trace.stage)
        assertEquals(1, trace.batchNo)
        assertEquals("deepseek-chat", trace.model)
        assertTrue(trace.requestContent!!.startsWith("[system]") && trace.requestContent!!.contains("[user]"))
        assertTrue(trace.responseContent!!.contains("\"b\":1"))
        val result = parseResult(trace)
        val pairs = result["pairs"] as List<Map<String, Any?>>
        assertEquals(1, pairs.size)
        assertEquals("B-1", pairs[0]["baseCode"])
        assertEquals("甲水库", pairs[0]["baseName"])
        assertEquals("V-1", pairs[0]["targetCode"])
        assertEquals("甲水厍", pairs[0]["targetName"])
        val unmatched = result["unmatchedBase"] as List<Map<String, Any?>>
        assertEquals(1, unmatched.size)
        assertEquals("B-2", unmatched[0]["code"])
        assertEquals("乙水库", unmatched[0]["name"])
    }

    @Test
    fun `补配调用失败批次也落留痕 错误摘要进 response 结果为空`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水厍"))
        val traces = Collections.synchronizedList(ArrayList<CompareAiTrace>())
        val env = env(aiConfigured = true, traceRecorder = { traces.add(it) }) { _, _, _ ->
            throw IllegalStateException("大模型接口调用失败:HTTP 500")
        }
        val m = matchObjects(base, target, listOf("code"), listOf("name"), MatchMode.CODE_NAME_LLM)
        val view = env.service.aiMatchResiduesForTest(m, base, target, listOf("code"), listOf("name"),
            jobId = 11, targetId = 22, targetLabel = "db.t")
        assertTrue(view.failed) // 主流程口径不变:失败批次跳过不炸任务
        val trace = traces.single()
        assertEquals(CompareAiTrace.STAGE_RESIDUE, trace.stage)
        assertNull(trace.resultJson)
        assertTrue(trace.responseContent!!.contains("调用失败") && trace.responseContent!!.contains("HTTP 500"),
            trace.responseContent)
    }

    @Test
    fun `补配无 jobId 不落留痕(交互式预生成等无任务场景)`() {
        val base = mapOf("1" to rowOf("code" to "B-1", "name" to "甲水库"))
        val target = mapOf("T-1" to rowOf("code" to "V-1", "name" to "甲水厍"))
        val traces = Collections.synchronizedList(ArrayList<CompareAiTrace>())
        val env = env(aiConfigured = true, traceRecorder = { traces.add(it) }) { _, _, _ -> "[]" }
        val m = matchObjects(base, target, listOf("code"), listOf("name"), MatchMode.CODE_NAME_LLM)
        env.service.aiMatchResiduesForTest(m, base, target, listOf("code"), listOf("name"))
        assertTrue(traces.isEmpty())
    }

    // ---------- 匹配逻辑 3:同名二轮消歧留痕 ----------

    @Test
    fun `同名消歧落 SAME_NAME 留痕 逐组判定还原`() {
        val base = mapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "葫芦岛"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "朝阳"))
        val target = mapOf(
            "T1" to rowOf("code" to null, "name" to "石门", "loc" to "朝阳"),
            "T2" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"))
        val traces = Collections.synchronizedList(ArrayList<CompareAiTrace>())
        val env = env(aiConfigured = true, traceRecorder = { traces.add(it) }) { _, _, u ->
            if (u.contains("石门")) "[{\"g\":1,\"b\":1,\"t\":2},{\"g\":1,\"b\":2,\"t\":1}]" else "[]"
        }
        val m = matchObjects(base, target, listOf("code"), listOf("name"), MatchMode.CODE_NAME_LLM)
        val view = env.service.aiRefineSameNameGroupsForTest(m, base, target,
            listOf("code"), listOf("name"), listOf("code", "name", "loc"),
            jobId = 11, targetId = 22, targetLabel = "db.t")
        assertEquals(setOf("B1" to "T2", "B2" to "T1"), view.pairs.map { it.baseKey to it.targetKey }.toSet())
        val trace = traces.single()
        assertEquals(CompareAiTrace.STAGE_SAME_NAME, trace.stage)
        assertEquals("COMPARE_MATCH", trace.scene)
        assertEquals(1, trace.batchNo)
        val result = parseResult(trace)
        val pairs = result["pairs"] as List<Map<String, Any?>>
        assertEquals(2, pairs.size)
        // 每条判定:组名 + 基准名称 → 目标编码/名称
        assertTrue(pairs.all { it["group"] == "石门" && it["baseName"] == "石门" && it["targetName"] == "石门" })
        assertTrue(pairs.all { it.containsKey("targetCode") })
    }

    @Test
    fun `同名消歧调用失败批次也落留痕`() {
        val base = mapOf(
            "B1" to rowOf("code" to "B1", "name" to "石门", "loc" to "葫芦岛"),
            "B2" to rowOf("code" to "B2", "name" to "石门", "loc" to "朝阳"))
        val target = mapOf(
            "T1" to rowOf("code" to null, "name" to "石门", "loc" to "朝阳"),
            "T2" to rowOf("code" to null, "name" to "石门", "loc" to "葫芦岛"))
        val traces = Collections.synchronizedList(ArrayList<CompareAiTrace>())
        val env = env(aiConfigured = true, traceRecorder = { traces.add(it) }) { _, _, u ->
            if (u.contains("石门")) throw RuntimeException("超时") else "[]"
        }
        val m = matchObjects(base, target, listOf("code"), listOf("name"), MatchMode.CODE_NAME_LLM)
        val view = env.service.aiRefineSameNameGroupsForTest(m, base, target,
            listOf("code"), listOf("name"), listOf("code", "name", "loc"), jobId = 11)
        assertTrue(view.failed)
        val trace = traces.single()
        assertEquals(CompareAiTrace.STAGE_SAME_NAME, trace.stage)
        assertNull(trace.resultJson)
        assertTrue(trace.responseContent!!.contains("超时"), trace.responseContent)
    }

    // ---------- 仓储:读写/排序/级联删除/截断 ----------

    @Test
    fun `仓储读写按目标时间升序 级联删除只清本任务 超长截断`() {
        val repo = CompareAiTraceRepository(newAiUsageJdbc())
        val longContent = "x".repeat(60_000)
        repo.insert(CompareAiTrace(jobId = 1, targetId = 5, targetLabel = "db.t1",
            scene = "COMPARE_MATCH", stage = CompareAiTrace.STAGE_RESIDUE, batchNo = 1,
            model = "m", requestContent = longContent, responseContent = "resp", resultJson = "{\"a\":1}"))
        repo.insert(CompareAiTrace(jobId = 1, targetId = null, targetLabel = "db.base",
            scene = "COMPARE_EVIDENCE", stage = CompareAiTrace.STAGE_EVIDENCE, batchNo = 1))
        repo.insert(CompareAiTrace(jobId = 2, targetId = 9, targetLabel = "db.t2",
            scene = "COMPARE_MAPPING", stage = CompareAiTrace.STAGE_MAPPING, batchNo = 1))
        val rows = repo.listByJob(1)
        assertEquals(2, rows.size)
        // 任务级(target_id NULL)排在前,目标级在后(ORDER BY target_id, created_at, id)
        assertNull(rows[0].targetId)
        assertEquals(CompareAiTrace.STAGE_EVIDENCE, rows[0].stage)
        assertEquals(5, rows[1].targetId)
        assertEquals("{\"a\":1}", rows[1].resultJson)
        assertNotNull(rows[1].createdAt)
        // 5 万字符截断口径(同 ai_usage_log)
        assertEquals(50_000 + "...(截断)".length, rows[1].requestContent!!.length)
        // 级联删除只清本任务
        repo.deleteByJob(1)
        assertTrue(repo.listByJob(1).isEmpty())
        assertEquals(1, repo.listByJob(2).size)
    }

    // ---------- listAiTraces 视图 ----------

    @Test
    fun `listAiTraces 解析 result_json 为对象 失败批次为 null 任务不存在报错`() {
        val env = env(aiConfigured = false, traceRepo = CompareAiTraceRepository(newAiUsageJdbc())) { _, _, _ -> "[]" }
        val jobId = env.service.createPending("留痕任务", 1, "base_db", null, "reservoir_base",
            "code", listOf("code", "name"), null, emptyList(), PendingReason.MAPPING_REVIEW, null, null)
        env.traceRepo!!.insert(CompareAiTrace(jobId = jobId, targetId = 1, targetLabel = "db.t",
            scene = "COMPARE_TIME", stage = CompareAiTrace.STAGE_TIME, batchNo = 1, model = "m",
            requestContent = "req", responseContent = "resp", resultJson = "{\"field\":\"update_time\"}"))
        env.traceRepo.insert(CompareAiTrace(jobId = jobId, targetId = 1, targetLabel = "db.t",
            scene = "COMPARE_MATCH", stage = CompareAiTrace.STAGE_RESIDUE, batchNo = 2,
            responseContent = "调用失败: 超时", resultJson = null))
        val views = env.service.listAiTraces(jobId)
        assertEquals(2, views.size)
        val time = views[0]
        assertEquals(jobId, time.jobId)
        assertEquals(1, time.targetId)
        assertEquals("db.t", time.targetLabel)
        assertEquals(mapOf("field" to "update_time"), time.resultJson)
        assertNotNull(time.createdAt)
        val failed = views[1]
        assertNull(failed.resultJson)
        assertTrue(failed.responseContent!!.contains("调用失败"))
        assertThrows(IllegalArgumentException::class.java) { env.service.listAiTraces(999999) }
    }

    // ---------- 测试环境 ----------

    /** AI 用量库(dqaiusage)内存 H2:跑 migration-aiusage 目录迁移(含 V2 compare_ai_trace) */
    private fun newAiUsageJdbc(): Jdbc {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:compare-ai-usage-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds, "db/migration-aiusage")
        return Jdbc(ds)
    }

    private class Env(val service: CompareService, val traceRepo: CompareAiTraceRepository?)

    /**
     * 只为调用补配/消歧/留痕逻辑的最小 CompareService(照 CompareMatchTest.env 口径):
     * 真实 H2 上的 AiConfigService + 注入的 fake LLM 调用点;traceRepo 给定时走真实仓储(默认记录器写库),
     * 否则用 traceRecorder 捕获器
     */
    private fun env(aiConfigured: Boolean,
                    traceRepo: CompareAiTraceRepository? = null,
                    traceRecorder: (CompareAiTrace) -> Unit = {},
                    chat: (AiConfigService.Config, String, String) -> String): Env {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:compare-trace-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        val config = AppConfig(dataDir = Files.createTempDirectory("compare-trace-test"))
        val aiConfigService = AiConfigService(AiConfigRepository(jdbc), CryptoUtil(config),
            config, mockk(relaxed = true))
        if (aiConfigured) {
            aiConfigService.save(AiConfigRequest(
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
            aiChat = { c, s, u -> chat(c, s, u) },
            aiTraceRepo = traceRepo,
            aiTraceRecorder = traceRecorder)
        return Env(service, traceRepo)
    }
}
