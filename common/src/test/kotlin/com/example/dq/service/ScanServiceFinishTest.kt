package com.example.dq.service

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
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.scan.ChunkRunner
import com.example.dq.scan.ScanAiTracker
import com.example.dq.scan.ScanExecutor
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * ScanService.finish 手动结束(H2 内存库):AI 后续挂起导致进度卡 99% 时,
 * 把任务直接落到终态(DONE/FAILED),放弃挂起的 AI 计数,未完成的表置 CANCELED。
 */
class ScanServiceFinishTest {

    private lateinit var scanRepo: ScanRepository
    private lateinit var dsRepo: DataSourceRepository
    private lateinit var scanService: ScanService
    private lateinit var aiTracker: ScanAiTracker
    private var dsId: Long = 0

    @BeforeEach
    fun setUp() {
        val config = AppConfig(dataDir = java.nio.file.Files.createTempDirectory("dq-finish-it"))
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:scan-finish-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        scanRepo = ScanRepository(jdbc)
        dsRepo = DataSourceRepository(jdbc)
        val schemaStatRepo = SchemaStatRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        val tableDocRepo = TableDocRepository(jdbc)
        val crypto = CryptoUtil(config)
        val dialectFactory = DialectFactory
        val dataSourceService = DataSourceService(dsRepo, crypto, dialectFactory, config, schemaStatRepo, metaCacheRepo)
        val executor = ScanExecutor(config)
        val tagRepo = TagRepository(jdbc)
        aiTracker = ScanAiTracker(scanRepo)
        val aiConfigService = AiConfigService(AiConfigRepository(jdbc), crypto, config, AiService())
        val tagService = TagService(tagRepo, dsRepo)
        val autoTagService = AutoTagService(
            aiConfigService, AiService(), tagService, tagRepo, scanRepo, tableDocRepo,
            dataSourceService, dialectFactory, aiTracker)
        val tableDocService = TableDocService(tableDocRepo, aiConfigService, AiService(), dataSourceService, dialectFactory)
        val scanDocService = ScanDocService(aiConfigService, scanRepo, tableDocRepo, tableDocService, aiTracker)
        val systemSettingsService = SystemSettingsService(SystemSettingsRepository(jdbc), config)
        val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, systemSettingsService, executor,
            tagService, autoTagService, scanDocService, aiTracker)
        scanService = ScanService(scanRepo, dsRepo, schemaStatRepo, metaCacheRepo, dataSourceService,
            dialectFactory, systemSettingsService, executor, chunkRunner, autoTagService, scanDocService, aiTracker)
        dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "测试库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
    }

    /** 建一个 RUNNING 的 job(totalTables 张 PENDING 表),返回 jobId 与表 id 列表 */
    private fun newRunningJob(totalTables: Int): Pair<Long, List<Long>> {
        val jobId = scanRepo.insertJob(dsId, null, "s1", false, "[]", totalTables, false, null, false)
        scanRepo.markJobRunning(jobId)
        val tableIds = (1..totalTables).map {
            scanRepo.insertScanTable(jobId, "t$it", 100L, null, null, null)
        }
        return jobId to tableIds
    }

    @Test
    fun `表全部完成但AI后续挂起时手动结束收尾DONE且进度100`() {
        val (jobId, tables) = newRunningJob(2)
        tables.forEach { scanRepo.finishTable(it, ScanStatus.DONE, 100L, null) }
        // 模拟卡死:AI 任务已入队计数,但销记永远不来(LLM 调用挂死/worker 异常)
        aiTracker.taskSubmitted(jobId, ScanAiTracker.AiKind.DOC)
        aiTracker.tryFinishJob(jobId) // 自动收尾不生效,任务保持 RUNNING
        assertEquals(ScanStatus.RUNNING, scanRepo.findJob(jobId)!!.status)

        scanService.finish(jobId)

        val job = scanService.getJob(jobId)
        assertEquals(ScanStatus.DONE, job.status)
        assertNotNull(job.finishedAt)
        assertEquals(100.0, job.progressPercent, 0.01)
        assertEquals(0, aiTracker.pending(jobId))
    }

    @Test
    fun `有失败表时手动结束收尾FAILED`() {
        val (jobId, tables) = newRunningJob(2)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        scanRepo.finishTable(tables[1], ScanStatus.FAILED, null, "规划失败")
        aiTracker.taskSubmitted(jobId, ScanAiTracker.AiKind.TAG)

        scanService.finish(jobId)

        val job = scanRepo.findJob(jobId)!!
        assertEquals(ScanStatus.FAILED, job.status)
        assertEquals("手动结束,1 张表统计失败", job.error)
    }

    @Test
    fun `未完成的表置为已取消后收尾DONE`() {
        val (jobId, tables) = newRunningJob(2)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        // tables[1] 保持 PENDING

        scanService.finish(jobId)

        val job = scanRepo.findJob(jobId)!!
        assertEquals(ScanStatus.DONE, job.status)
        assertEquals(ScanStatus.CANCELED, scanRepo.listScanTables(jobId).first { it.tableName == "t2" }.status)
    }

    @Test
    fun `终态任务手动结束是幂等空操作`() {
        val (jobId, tables) = newRunningJob(1)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        scanRepo.finishJob(jobId, ScanStatus.DONE, null)

        scanService.finish(jobId) // 不应抛异常,也不改变状态

        assertEquals(ScanStatus.DONE, scanRepo.findJob(jobId)!!.status)
    }

    @Test
    fun `续扫时数据源连接失败还原为进入前状态而不置FAILED`() {
        // 离线数据源(127.0.0.1:1 立即 ECONNREFUSED,不真等超时;同 MetadataOfflineFallbackTest 手法)
        val offDsId = dsRepo.insert(DataSourceConfig().apply {
            name = "离线库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://127.0.0.1:1/nodb"
        })
        val jobId = scanRepo.insertJob(offDsId, null, "s1", false, "[]", 1, false, null, false)
        scanRepo.markJobRunning(jobId)
        scanRepo.insertScanTable(jobId, "t1", 100L, null, null, null)
        scanRepo.finishJob(jobId, ScanStatus.CANCELED, null) // 模拟断网前任务已被取消

        val e = try {
            scanService.resume(jobId)
            throw AssertionError("断网续扫应抛异常")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertNotNull(e.message)
        org.junit.jupiter.api.Assertions.assertTrue(e.message!!.contains("数据源连接失败"), e.message)
        // 关键断言:任务状态还原为进入前的 CANCELED(可再次续扫),而不是被翻成 FAILED
        assertEquals(ScanStatus.CANCELED, scanRepo.findJob(jobId)!!.status)
    }

    @Test
    fun `结束后不可续扫`() {
        val (jobId, tables) = newRunningJob(1)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        scanService.finish(jobId)

        try {
            scanService.resume(jobId)
            throw AssertionError("手动结束的任务续扫应抛异常")
        } catch (expected: IllegalStateException) {
            // 预期:只有已取消/已中断/失败的任务才能续扫
        }
    }
}
