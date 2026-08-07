package com.example.dq.service

import com.example.dq.config.AiDefaults
import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * 扫描后 AI 自动打标:prompt 组装/回答解析(纯函数)+ submit 跳过与熔断逻辑(H2 内存库)。
 * LLM 调用点经构造器注入 fake,不调真实接口;DONE 表均带表注释,不触发抽样(抽样需真实业务库)。
 */
class AutoTagServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var scanRepo: ScanRepository
    private lateinit var tagRepo: TagRepository
    private lateinit var tagService: TagService
    private var dsId: Long = 0

    /** fake LLM:记录调用次数,按 chatAnswer 回答或按 chatError 抛出 */
    private val chatCalls = ArrayList<String>()
    private var chatAnswer: String = ""
    private var chatError: RuntimeException? = null

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:auto-tag-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        scanRepo = ScanRepository(jdbc)
        tagRepo = TagRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        tagService = TagService(tagRepo, dsRepo)
        dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "测试库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
        chatCalls.clear()
        chatAnswer = ""
        chatError = null
    }

    private val configuredAi = AiDefaults(apiKey = "k", baseUrl = "http://localhost:1/v1", model = "m")

    private fun newService(ai: AiDefaults): AutoTagService {
        val config = AppConfig(dataDir = Files.createTempDirectory("dq-autotag"), ai = ai)
        val crypto = CryptoUtil(config)
        val dsRepo = DataSourceRepository(jdbc)
        val dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, SchemaStatRepository(jdbc))
        return AutoTagService(
            AiConfigService(AiConfigRepository(jdbc), crypto, config),
            AiService(), tagService, tagRepo, scanRepo, TableDocRepository(jdbc),
            dataSourceService, DialectFactory
        ) { _, _, prompt ->
            chatCalls.add(prompt)
            chatError?.let { throw it }
            chatAnswer
        }
    }

    /** 建一个 job + 一张 DONE 表(表注释非空,打标不触发抽样) */
    private fun newDoneTable(table: String, autoTag: Boolean = true): Pair<Long, Long> {
        val jobId = scanRepo.insertJob(dsId, null, "s1", false, "[]", 1, autoTag)
        val tableId = scanRepo.insertScanTable(jobId, table, 100L, null, "订单表", null)
        scanRepo.finishTable(tableId, ScanStatus.DONE, 100L, null)
        return jobId to tableId
    }

    private fun awaitTrue(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                fail<Any>("等待条件超时")
            }
            Thread.sleep(20)
        }
    }

    // ---------- prompt 组装(纯函数) ----------

    @Test
    fun `分类prompt含候选标记与注释时不带抽样段`() {
        val cols = listOf(
            ScanColumnView.of("id", "bigint(20)", "主键", false, null, "PK", 100, 0, 0, 0),
            ScanColumnView.of("amount", "decimal(10,2)", null, true, null, "", 100, 0, 0, 0))

        val prompt = AutoTagService.buildClassifyPrompt(
            listOf("订单", "用户"), "t_order", "订单表", "存储订单主数据", cols, emptyList())

        assertTrue(prompt.contains("- 订单"))
        assertTrue(prompt.contains("- 用户"))
        assertTrue(prompt.contains("表名:t_order"))
        assertTrue(prompt.contains("表注释:订单表"))
        assertTrue(prompt.contains("表描述:存储订单主数据"))
        assertTrue(prompt.contains("字段(共 2 个)"))
        assertTrue(prompt.contains("- id bigint(20) — 主键"))
        assertTrue(prompt.contains("- amount decimal(10,2)"))
        assertFalse(prompt.contains("抽样数据"))
    }

    @Test
    fun `无注释时prompt带抽样数据段`() {
        val cols = listOf(
            ScanColumnView.of("id", "bigint(20)", null, false, null, "", 2, 0, 0, 0),
            ScanColumnView.of("status", "int", null, true, null, "", 2, 0, 0, 0))

        val prompt = AutoTagService.buildClassifyPrompt(
            listOf("订单"), "t1", null, null, cols,
            listOf(listOf("1", "0"), listOf("2", "-1")))

        assertTrue(prompt.contains("抽样数据(前 2 行)"))
        assertTrue(prompt.contains("| id | status |"))
        assertTrue(prompt.contains("| 1 | 0 |"))
        assertTrue(prompt.contains("| 2 | -1 |"))
    }

    // ---------- 回答解析(纯函数) ----------

    @Test
    fun `parseTag精确命中与去引号命中`() {
        val candidates = listOf("订单", "用户")
        assertEquals("订单", AutoTagService.parseTag("订单", candidates))
        assertEquals("订单", AutoTagService.parseTag(" \"订单\" ", candidates))
        assertEquals("用户", AutoTagService.parseTag("“用户”", candidates))
    }

    @Test
    fun `parseTag对NONE与幻觉标记返回null`() {
        val candidates = listOf("订单", "用户")
        assertNull(AutoTagService.parseTag("NONE", candidates))
        assertNull(AutoTagService.parseTag("none", candidates))
        assertNull(AutoTagService.parseTag("订单表", candidates)) // 不在候选列表的幻觉标记不打
    }

    // ---------- submit / 队列任务逻辑 ----------

    @Test
    fun `job未开autoTag时不入队不调LLM`() {
        val service = newService(configuredAi)
        tagService.create("订单", null)
        val (jobId, tableId) = newDoneTable("t_order", autoTag = false)

        service.submit(jobId, tableId) // submit 同步检查开关,关闭则直接 return

        assertTrue(chatCalls.isEmpty())
    }

    @Test
    fun `未配置大模型时跳过不调LLM`() {
        val service = newService(AiDefaults()) // 页面与默认配置都为空
        tagService.create("订单", null)
        val (jobId, tableId) = newDoneTable("t_order")

        service.runSafely(scanRepo.findJob(jobId)!!, tableId)

        assertTrue(chatCalls.isEmpty())
        assertFalse(tagRepo.hasUserTag(dsId, "", "s1", "t_order"))
    }

    @Test
    fun `无候选USER标记时跳过不调LLM`() {
        val service = newService(configuredAi)
        val (jobId, tableId) = newDoneTable("t_order")

        service.runSafely(scanRepo.findJob(jobId)!!, tableId)

        assertTrue(chatCalls.isEmpty())
    }

    @Test
    fun `表已有USER标记时跳过不覆盖`() {
        val service = newService(configuredAi)
        val manual = tagService.create("用户", null)
        val (jobId, tableId) = newDoneTable("t_order")
        tagRepo.ensureTableTag(manual.id, dsId, "", "s1", "t_order")

        service.runSafely(scanRepo.findJob(jobId)!!, tableId)

        assertTrue(chatCalls.isEmpty())
        // 原手动标记仍在,未被改动
        val tags = tagRepo.tableTagsBySchema(dsId, "", "s1")["t_order"]!!
        assertEquals(listOf("用户"), tags.map { it.name })
    }

    @Test
    fun `命中路径fake返回标记名则落库`() {
        val service = newService(configuredAi)
        tagService.create("订单", null)
        chatAnswer = "订单"
        val (jobId, tableId) = newDoneTable("t_order")

        service.submit(jobId, tableId) // 走真实队列,覆盖异步入队链路

        awaitTrue { tagRepo.hasUserTag(dsId, "", "s1", "t_order") }
        assertEquals(1, chatCalls.size)
        val tags = tagRepo.tableTagsBySchema(dsId, "", "s1")["t_order"]!!
        assertEquals(listOf("订单"), tags.map { it.name })
    }

    @Test
    fun `fake抛异常时不抛出且本job熔断`() {
        val service = newService(configuredAi)
        tagService.create("订单", null)
        chatError = RuntimeException("connection refused")
        val (jobId, tableId1) = newDoneTable("t1")
        val tableId2 = scanRepo.insertScanTable(jobId, "t2", 100L, null, "订单表", null)
        scanRepo.finishTable(tableId2, ScanStatus.DONE, 100L, null)
        val job = scanRepo.findJob(jobId)!!

        service.runSafely(job, tableId1) // LLM 失败:不抛出,熔断本 job
        service.runSafely(job, tableId2) // 第二张表直接跳过

        assertEquals(1, chatCalls.size)
        assertFalse(tagRepo.hasUserTag(dsId, "", "s1", "t1"))
        assertFalse(tagRepo.hasUserTag(dsId, "", "s1", "t2"))
    }
}
