package com.example.dq.service

import com.example.dq.config.AiDefaults
import com.example.dq.config.AppConfig
import com.example.dq.model.AiTagBatchTask
import com.example.dq.model.ColumnMeta
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.TableStat
import com.example.dq.model.TagSource
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.AiTagBatchTaskRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import io.mockk.every
import io.mockk.mockk
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.sql.Types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * 批量 AI 打标后台任务:提交校验/任务体聚合计数/进度与终态汇总/重启恢复(H2 内存库)。
 * 元数据走 mockk 打桩 MetadataService,LLM 经构造器注入 fake(按调用顺序消费回答),不调真实接口;
 * 任务体经 submit 走真实 2 线程池(覆盖入队链路),轮询任务行等终态。
 */
class BatchAiTagTaskServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var tagRepo: TagRepository
    private lateinit var tagService: TagService
    private lateinit var metadataService: MetadataService
    private lateinit var taskRepo: AiTagBatchTaskRepository
    private lateinit var dsRepo: DataSourceRepository
    private var dsId: Long = 0

    /** fake LLM:记录 user prompt,按调用顺序消费 answerQueue(耗尽后回退 "") */
    private val chatCalls = ArrayList<String>()
    private lateinit var answerQueue: ArrayDeque<String>

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ai-tag-batch-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        tagRepo = TagRepository(jdbc)
        dsRepo = DataSourceRepository(jdbc)
        tagService = TagService(tagRepo, dsRepo)
        dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "测试库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
        metadataService = mockk()
        taskRepo = AiTagBatchTaskRepository(jdbc)
        chatCalls.clear()
        answerQueue = ArrayDeque()
    }

    private val configuredAi = AiDefaults(apiKey = "k", baseUrl = "http://localhost:1/v1", model = "m")

    private fun newService(ai: AiDefaults = configuredAi, answers: List<String> = emptyList()): BatchAiTagTaskService {
        val config = AppConfig(dataDir = Files.createTempDirectory("dq-aitagbatch"), ai = ai)
        val crypto = CryptoUtil(config)
        answerQueue = ArrayDeque(answers)
        val batch = BatchAiTagService(
            AiConfigService(AiConfigRepository(jdbc), crypto, config, AiService()),
            AiService(), tagService, tagRepo, metadataService
        ) { _, _, prompt, _ ->
            chatCalls.add(prompt)
            answerQueue.removeFirstOrNull() ?: ""
        }
        return BatchAiTagTaskService(batch, taskRepo, dsRepo)
    }

    private fun awaitTerminal(id: Long): AiTagBatchTask {
        val deadline = System.currentTimeMillis() + 10000
        while (true) {
            val row = taskRepo.findById(id)
            if (row != null && (row.status == "DONE" || row.status == "FAILED")) return row
            if (System.currentTimeMillis() > deadline) fail<Any>("等待任务终态超时")
            Thread.sleep(20)
        }
    }

    /** 打桩:库下若干带注释的表(每张两个字段) */
    private fun stubTables(vararg tables: String) {
        every { metadataService.listTables(dsId, null, "s1") } returns
                tables.map { TableStat(it, 100L, null, "${it}注释", null) }
        for (t in tables) {
            every { metadataService.listTableColumns(dsId, null, "s1", t) } returns listOf(
                ColumnMeta("id", "BIGINT", "bigint(20)", Types.BIGINT, false, null, "主键", true, 1, false),
                ColumnMeta("name", "VARCHAR", "varchar(64)", Types.VARCHAR, true, null, "名称", false, 0, false))
        }
    }

    // ---------- 提交校验(不建任务,快速失败) ----------

    @Test
    fun `数据源不存在时不建任务`() {
        val service = newService()
        val tag = tagService.create("订单", null)

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(999L, null, "s1", listOf("t1"), listOf(tag.id))
        }
        assertTrue(taskRepo.listActive().isEmpty())
    }

    @Test
    fun `表清单为空时不建任务`() {
        val service = newService()
        val tag = tagService.create("订单", null)

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(dsId, null, "s1", emptyList(), listOf(tag.id))
        }
        assertTrue(taskRepo.listActive().isEmpty())
    }

    @Test
    fun `未配置大模型时不建任务`() {
        val service = newService(ai = AiDefaults()) // 页面与默认配置都为空
        val tag = tagService.create("订单", null)

        assertThrows(IllegalStateException::class.java) {
            service.submit(dsId, null, "s1", listOf("t1"), listOf(tag.id))
        }
        assertTrue(taskRepo.listActive().isEmpty())
    }

    @Test
    fun `勾选里没有AI候选时不建任务`() {
        val service = newService()
        val manual = tagService.create("内部收藏", null, null, "MANUAL")

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(dsId, null, "s1", listOf("t1"), listOf(manual.id))
        }
        assertTrue(taskRepo.listActive().isEmpty())
    }

    // ---------- 任务体聚合 ----------

    @Test
    fun `任务体逐表执行并汇总三类计数`() {
        val order = tagService.create("订单", null)
        // t1 命中打标;t2 未匹配;t3 命中但表上已有同标(人工);t4 表不存在(单表失败)
        stubTables("t1", "t2", "t3")
        tagRepo.ensureTableTag(order.id, dsId, "", "s1", "t3", TagSource.MANUAL)
        val service = newService(answers = listOf("订单", "NONE", "订单"))

        val id = service.submit(dsId, null, "s1", listOf("t1", "t2", "t3", "t4"), listOf(order.id))
        val row = awaitTerminal(id)

        assertEquals("DONE", row.status)
        assertEquals(4, row.progressDone)
        assertEquals(4, row.progressTotal)
        assertEquals(1, row.taggedCount)     // t1
        assertEquals(1, row.unmatchedCount)  // t2
        assertEquals(2, row.skippedCount)    // t3 已存在 + t4 表不存在
        assertEquals(3, chatCalls.size)      // t4 表不存在在调 LLM 前抛异常,不消耗调用
        // t1 以 AI 来源落标;t3 原人工关系保留不改写
        assertEquals(TagSource.AI, tagRepo.tableTagsBySchema(dsId, "", "s1")["t1"]!![0].source)
        assertEquals(TagSource.MANUAL, tagRepo.tableTagsBySchema(dsId, "", "s1")["t3"]!![0].source)
    }

    @Test
    fun `备份表跳过且不调LLM`() {
        val order = tagService.create("订单", null)
        stubTables("t1_copy")
        val service = newService()

        val id = service.submit(dsId, null, "s1", listOf("t1_copy"), listOf(order.id))
        val row = awaitTerminal(id)

        assertEquals("DONE", row.status)
        assertEquals(0, row.taggedCount)
        assertEquals(1, row.skippedCount)
        assertTrue(chatCalls.isEmpty())
    }

    @Test
    fun `提交后任务立即返回且active可查`() {
        val order = tagService.create("订单", null)
        stubTables("t1", "t2")
        val service = newService(answers = listOf("订单", "订单"))

        val id = service.submit(dsId, null, "s1", listOf("t1", "t2"), listOf(order.id))
        val row = awaitTerminal(id)

        assertEquals("DONE", row.status)
        assertEquals(2, row.taggedCount)
        assertNull(row.error)
        // 终态后 active 清空(后台任务中心「消失=终态」口径)
        assertTrue(taskRepo.listActive().isEmpty())
        // 详情回源取汇总;不存在 400
        assertEquals(2, service.detail(id).taggedCount)
        assertThrows(IllegalArgumentException::class.java) { service.detail(999L) }
    }

    // ---------- 重启恢复 ----------

    @Test
    fun `重启恢复把未完成任务置失败`() {
        val service = newService()
        val id = taskRepo.insert(dsId, "", "s1", 3, listOf("t1", "t2", "t3"))
        taskRepo.markRunning(id)

        service.recoverUnfinished()

        val row = taskRepo.findById(id)!!
        assertEquals("FAILED", row.status)
        assertTrue(row.error!!.contains("重启"))
    }

    @Test
    fun `任务行携带表名清单供active视图带出`() {
        val service = newService()
        stubTables("t1", "t2")
        val tag = tagService.create("订单", null)

        val id = service.submit(dsId, null, "s1", listOf("t1", "t2"), listOf(tag.id))
        val active = taskRepo.listActive().first { it.id == id }
        assertEquals(listOf("t1", "t2"), active.tableNames)
        awaitTerminal(id)
    }
}
