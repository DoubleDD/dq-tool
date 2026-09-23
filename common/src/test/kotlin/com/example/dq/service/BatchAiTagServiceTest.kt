package com.example.dq.service

import com.example.dq.config.AiDefaults
import com.example.dq.config.AppConfig
import com.example.dq.model.ColumnMeta
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.TableStat
import com.example.dq.model.TagSource
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.sql.Types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 批量 AI 打标(批量打标弹窗 AI 页签):关键分支单测(H2 内存库 + mockk 打桩 MetadataService)。
 * LLM 调用点经构造器注入 fake,不调真实接口;表/字段元数据走 MetadataService 缓存优先路径,打桩返回。
 */
class BatchAiTagServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var tagRepo: TagRepository
    private lateinit var tagService: TagService
    private lateinit var metadataService: MetadataService
    private var dsId: Long = 0

    /** fake LLM:记录 user prompt,按 chatAnswer 回答或按 chatError 抛出 */
    private val chatCalls = ArrayList<String>()
    private var chatAnswer: String = ""
    private var chatError: RuntimeException? = null

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:batch-ai-tag-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        tagRepo = TagRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        tagService = TagService(tagRepo, dsRepo)
        dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "测试库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
        metadataService = mockk()
        chatCalls.clear()
        chatAnswer = ""
        chatError = null
    }

    private val configuredAi = AiDefaults(apiKey = "k", baseUrl = "http://localhost:1/v1", model = "m")

    private fun newService(ai: AiDefaults): BatchAiTagService {
        val config = AppConfig(dataDir = Files.createTempDirectory("dq-batchaitag"), ai = ai)
        val crypto = CryptoUtil(config)
        return BatchAiTagService(
            AiConfigService(AiConfigRepository(jdbc), crypto, config, AiService()),
            AiService(), tagService, tagRepo, metadataService
        ) { _, _, prompt, _ ->
            chatCalls.add(prompt)
            chatError?.let { throw it }
            chatAnswer
        }
    }

    /** 打桩:库下一张带注释的表 + 两个带注释的字段 */
    private fun stubTable(table: String = "t_order", comment: String = "订单表") {
        every { metadataService.listTables(dsId, null, "s1") } returns
                listOf(TableStat(table, 100L, null, comment, null))
        every { metadataService.listTableColumns(dsId, null, "s1", table) } returns listOf(
            ColumnMeta("id", "BIGINT", "bigint(20)", Types.BIGINT, false, null, "主键", true, 1, false),
            ColumnMeta("amount", "DECIMAL", "decimal(10,2)", Types.DECIMAL, true, null, "订单金额", false, 0, false))
    }

    // ---------- 备份表 ----------

    @Test
    fun `备份表跳过不调LLM`() {
        val service = newService(configuredAi)
        val tag = tagService.create("订单", null)
        stubTable("t_order_copy")

        val result = service.tagTable(dsId, null, "s1", "t_order_copy", listOf(tag.id))

        assertFalse(result.applied)
        assertTrue(result.skipped)
        assertTrue(result.reason!!.contains("备份表"))
        assertTrue(chatCalls.isEmpty())
        verify { metadataService wasNot Called } // 先于元数据/配置检查直接返回
    }

    // ---------- 参数与配置校验 ----------

    @Test
    fun `未配置大模型时抛异常`() {
        val service = newService(AiDefaults()) // 页面与默认配置都为空
        val tag = tagService.create("订单", null)
        stubTable()

        assertThrows(IllegalStateException::class.java) {
            service.tagTable(dsId, null, "s1", "t_order", listOf(tag.id))
        }
        assertTrue(chatCalls.isEmpty())
    }

    @Test
    fun `勾选标记里没有AI类型候选时抛异常`() {
        val service = newService(configuredAi)
        val manual = tagService.create("内部收藏", null, null, "MANUAL")
        stubTable()

        val e = assertThrows(IllegalArgumentException::class.java) {
            service.tagTable(dsId, null, "s1", "t_order", listOf(manual.id))
        }
        assertEquals("没有可用的候选标记", e.message)
        assertTrue(chatCalls.isEmpty())
    }

    @Test
    fun `表不存在时抛异常`() {
        val service = newService(configuredAi)
        val tag = tagService.create("订单", null)
        every { metadataService.listTables(dsId, null, "s1") } returns emptyList()

        val e = assertThrows(IllegalArgumentException::class.java) {
            service.tagTable(dsId, null, "s1", "t_order", listOf(tag.id))
        }
        assertTrue(e.message!!.contains("表不存在"))
        assertTrue(chatCalls.isEmpty())
    }

    // ---------- 模型回答分支 ----------

    @Test
    fun `模型返回NONE时不打标`() {
        val service = newService(configuredAi)
        tagService.create("订单", null)
        stubTable()
        chatAnswer = "NONE"

        val result = service.tagTable(dsId, null, "s1", "t_order", tagService.list().map { it.id })

        assertFalse(result.applied)
        assertFalse(result.skipped)
        assertNull(result.tagName)
        assertTrue(result.reason!!.contains("未匹配"))
        assertTrue(tagRepo.tableTagsBySchema(dsId, "", "s1").isEmpty())
    }

    @Test
    fun `表已有同标记时跳过且保留原关系来源`() {
        val service = newService(configuredAi)
        val tag = tagService.create("订单", null)
        stubTable()
        // 表上已有同一标记(人工来源)
        tagRepo.ensureTableTag(tag.id, dsId, "", "s1", "t_order", TagSource.MANUAL)
        chatAnswer = "订单"

        val result = service.tagTable(dsId, null, "s1", "t_order", listOf(tag.id))

        assertFalse(result.applied)
        assertTrue(result.skipped)
        assertEquals("订单", result.tagName)
        val tags = tagRepo.tableTagsBySchema(dsId, "", "s1")["t_order"]!!
        assertEquals(1, tags.size) // 不重复打,仍只有一条关系
        assertEquals(TagSource.MANUAL, tags[0].source) // 原关系来源不被改写
    }

    @Test
    fun `命中路径fake返回标记名则以AI来源落库`() {
        val service = newService(configuredAi)
        val tag = tagService.create("订单", null, "订单相关主数据表")
        stubTable()
        chatAnswer = "\"订单\""

        val result = service.tagTable(dsId, null, "s1", "t_order", listOf(tag.id))

        assertTrue(result.applied)
        assertEquals("订单", result.tagName)
        assertFalse(result.skipped)
        val tags = tagRepo.tableTagsBySchema(dsId, "", "s1")["t_order"]!!
        assertEquals(listOf("订单"), tags.map { it.name })
        assertEquals(TagSource.AI, tags[0].source)
        // prompt:候选带描述 + 表注释 + 字段注释;不抽样业务数据
        assertEquals(1, chatCalls.size)
        val prompt = chatCalls[0]
        assertTrue(prompt.contains("- 订单:订单相关主数据表"))
        assertTrue(prompt.contains("表名:t_order"))
        assertTrue(prompt.contains("表注释:订单表"))
        assertTrue(prompt.contains("- id bigint(20) — 主键"))
        assertTrue(prompt.contains("- amount decimal(10,2) — 订单金额"))
        assertFalse(prompt.contains("抽样数据"))
        assertFalse(prompt.contains("表描述:")) // 非扫描场景不传 AI 表描述
    }

    @Test
    fun `未勾选的AI标记不进候选prompt`() {
        val service = newService(configuredAi)
        val order = tagService.create("订单", null)
        tagService.create("用户", null)
        stubTable()
        chatAnswer = "订单"

        service.tagTable(dsId, null, "s1", "t_order", listOf(order.id))

        assertEquals(1, chatCalls.size)
        assertTrue(chatCalls[0].contains("- 订单\n"))
        assertFalse(chatCalls[0].contains("- 用户"))
    }
}
