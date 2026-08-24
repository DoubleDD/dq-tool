package com.example.dq.service

import com.example.dq.config.AiDefaults
import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ScanStatus
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * 扫描后 AI 生成表描述:submit 跳过与熔断逻辑(H2 内存库)。
 * 描述生成调用点经构造器注入 fake,不调真实大模型/业务库。
 */
class ScanDocServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var scanRepo: ScanRepository
    private lateinit var tableDocRepo: TableDocRepository
    private var dsId: Long = 0

    /** fake 生成调用:记录调用的表名,按 genError 抛出 */
    private val genCalls = ArrayList<String>()
    private var genError: Exception? = null

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:scan-doc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        scanRepo = ScanRepository(jdbc)
        tableDocRepo = TableDocRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "测试库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
        genCalls.clear()
        genError = null
    }

    private val configuredAi = AiDefaults(apiKey = "k", baseUrl = "http://localhost:1/v1", model = "m")

    private fun newService(ai: AiDefaults): ScanDocService {
        val config = AppConfig(dataDir = Files.createTempDirectory("dq-scandoc"), ai = ai)
        val crypto = CryptoUtil(config)
        val dsRepo = DataSourceRepository(jdbc)
        val dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
        val aiConfigService = AiConfigService(AiConfigRepository(jdbc), crypto, config, AiService())
        val tableDocService = TableDocService(tableDocRepo, aiConfigService, AiService(), dataSourceService, DialectFactory)
        return ScanDocService(aiConfigService, scanRepo, tableDocRepo, tableDocService) { _, _, _, table ->
            genCalls.add(table)
            genError?.let { throw it }
        }
    }

    /** 建一个 RUNNING 的 job + 一张 DONE 表 */
    private fun newDoneTable(table: String, genDoc: Boolean = true): Pair<Long, Long> {
        val jobId = scanRepo.insertJob(dsId, null, "s1", false, "[]", 1, false, null, genDoc)
        scanRepo.markJobRunning(jobId)
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

    @Test
    fun `job未开genDoc时不入队不生成`() {
        val service = newService(configuredAi)
        val (jobId, tableId) = newDoneTable("t_order", genDoc = false)

        service.submit(jobId, tableId) // submit 同步检查开关,关闭则直接 return

        assertTrue(genCalls.isEmpty())
    }

    @Test
    fun `未配置大模型时跳过不生成`() {
        val service = newService(AiDefaults()) // 页面与默认配置都为空
        val (jobId, tableId) = newDoneTable("t_order")

        service.runSafely(scanRepo.findJob(jobId)!!, tableId)

        assertTrue(genCalls.isEmpty())
    }

    @Test
    fun `表已有非空描述时跳过不浪费LLM调用`() {
        val service = newService(configuredAi)
        val (jobId, tableId) = newDoneTable("t_order")
        tableDocRepo.upsert(dsId, "", "s1", "t_order", "已有描述", "m")

        service.runSafely(scanRepo.findJob(jobId)!!, tableId)

        assertTrue(genCalls.isEmpty())
    }

    @Test
    fun `任务已取消时不再生成`() {
        val service = newService(configuredAi)
        val (jobId, tableId) = newDoneTable("t_order")
        scanRepo.updateJobStatus(jobId, ScanStatus.CANCELED)

        service.runSafely(scanRepo.findJob(jobId)!!, tableId)

        assertTrue(genCalls.isEmpty())
    }

    @Test
    fun `命中路径走真实队列生成成功`() {
        val service = newService(configuredAi)
        val (jobId, tableId) = newDoneTable("t_order")

        service.submit(jobId, tableId) // 走真实队列,覆盖异步入队链路

        awaitTrue { genCalls.isNotEmpty() }
        assertEquals(listOf("t_order"), genCalls)
    }

    @Test
    fun `fake抛异常时不抛出且本job熔断`() {
        val service = newService(configuredAi)
        genError = RuntimeException("connection refused")
        val (jobId, tableId1) = newDoneTable("t1")
        val tableId2 = scanRepo.insertScanTable(jobId, "t2", 100L, null, "订单表", null)
        scanRepo.finishTable(tableId2, ScanStatus.DONE, 100L, null)
        val job = scanRepo.findJob(jobId)!!

        service.runSafely(job, tableId1) // LLM 失败:不抛出,熔断本 job
        service.runSafely(job, tableId2) // 第二张表直接跳过

        assertEquals(1, genCalls.size)
    }

    @Test
    fun `表级参数错误只跳过本表不熔断`() {
        val service = newService(configuredAi)
        genError = IllegalArgumentException("表不存在:t1")
        val (jobId, tableId1) = newDoneTable("t1")
        val tableId2 = scanRepo.insertScanTable(jobId, "t2", 100L, null, "订单表", null)
        scanRepo.finishTable(tableId2, ScanStatus.DONE, 100L, null)
        val job = scanRepo.findJob(jobId)!!

        service.runSafely(job, tableId1) // 表级问题:不熔断
        genError = null
        service.runSafely(job, tableId2) // 后续表仍可生成

        assertEquals(listOf("t1", "t2"), genCalls)
    }
}
