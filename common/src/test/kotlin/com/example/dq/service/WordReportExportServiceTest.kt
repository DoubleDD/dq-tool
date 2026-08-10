package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanStatus
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ReportExportRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/** Word 报告异步导出任务:提交 → 后台执行 → 进度落库 → DONE/FAILED 终态;重启恢复 */
class WordReportExportServiceTest {

    private lateinit var config: AppConfig
    private lateinit var scanRepo: ScanRepository
    private lateinit var schemaStatRepo: SchemaStatRepository
    private lateinit var exportRepo: ReportExportRepository
    private lateinit var dataSourceService: DataSourceService
    private lateinit var service: WordReportExportService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:report-export-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        val dsRepo = DataSourceRepository(jdbc)
        scanRepo = ScanRepository(jdbc)
        schemaStatRepo = SchemaStatRepository(jdbc)
        exportRepo = ReportExportRepository(jdbc)
        val schemaDocRepo = SchemaDocRepository(jdbc)
        config = AppConfig(dataDir = Files.createTempDirectory("report-export-test"))
        val crypto = CryptoUtil(config)
        dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, schemaStatRepo)
        val metadataService = MetadataService(dataSourceService, DialectFactory, scanRepo, schemaStatRepo, schemaDocRepo)
        val reportService = WordReportService(dataSourceService, metadataService, scanRepo, schemaDocRepo,
            DialectFactory, TagRepository(jdbc), TableDocRepository(jdbc),
            AiConfigService(AiConfigRepository(jdbc), crypto, config), AiService())
        service = WordReportExportService(reportService, exportRepo, dsRepo, config)
    }

    private fun createDataSource(): Long =
        dataSourceService.create(DataSourceRequest("报告源", "jdbc:mysql://localhost:1/db", "root", "p", null, null))

    private fun seedDoneSchema(datasourceId: Long, schema: String) {
        schemaStatRepo.replaceAll(datasourceId, null, listOf(SchemaStatRepository.CachedStat(schema, 1, 1024L)))
        val jobId = scanRepo.insertJob(datasourceId, null, schema, false, null, 1)
        val stId = scanRepo.insertScanTable(jobId, "t1", null, 1024L, null, null)
        scanRepo.finishTable(stId, ScanStatus.DONE, 5L, null)
        scanRepo.insertScanColumn(stId, ScanColumnView.of("c", "int", null, null, null, null, 5, 1, 0, 0))
        scanRepo.finishJob(jobId, ScanStatus.DONE, null)
    }

    /** 轮询任务到终态(DONE/FAILED),超时 60 秒 */
    private fun waitFinished(id: Long): ReportExportRepository.Row {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val row = exportRepo.findById(id)!!
            if (row.status == "DONE" || row.status == "FAILED") {
                return row
            }
            Thread.sleep(100)
        }
        throw AssertionError("任务 $id 未在 60 秒内完成")
    }

    @Test
    fun `提交后后台生成文件,进度与产物落库`() {
        val dsId = createDataSource()
        seedDoneSchema(dsId, "db_a")
        val taskId = service.submit(dsId, null, listOf("db_a"))

        val row = waitFinished(taskId)
        assertEquals("DONE", row.status, row.error)
        assertNotNull(row.filePath)
        val file = Path.of(row.filePath)
        assertTrue(Files.exists(file), "产物文件应存在")
        assertEquals(Files.size(file), row.fileSize)
        // 进度走满:1 库聚合 + 8 固定分析 + 1 渲染(无标记节)
        assertEquals(row.progressTotal, row.progressDone, "进度应走满")
        assertTrue(row.progressTotal >= 10)

        // 产物可打开且无标签残留
        XWPFDocument(ByteArrayInputStream(Files.readAllBytes(file))).use { doc ->
            val text = doc.paragraphs.joinToString("\n") { it.text }
            assertTrue(text.contains("报告源数据调研报告"), text)
            assertTrue(!text.contains("{{"), text)
        }
        // 列表视图回读
        val view = service.list(dsId).single()
        assertEquals(listOf("db_a"), view.schemaNames)
        assertEquals("报告源", view.datasourceName)
    }

    @Test
    fun `库未完成全表扫描时任务置 FAILED 并带原因`() {
        val dsId = createDataSource()
        schemaStatRepo.replaceAll(dsId, null, listOf(SchemaStatRepository.CachedStat("db_c", 1, 1024L)))
        val taskId = service.submit(dsId, null, listOf("db_c"))

        val row = waitFinished(taskId)
        assertEquals("FAILED", row.status)
        assertTrue(row.error!!.contains("db_c"), row.error)
        // FAILED 任务不能下载/打开
        try {
            service.downloadFile(taskId)
            throw AssertionError("FAILED 任务不应可下载")
        } catch (e: IllegalStateException) {
            // 预期
        }
    }

    @Test
    fun `重启恢复把未完成任务置 FAILED`() {
        val dsId = createDataSource()
        val id = exportRepo.insert(dsId, "", "db_a") // PENDING,永不执行,模拟重启遗留
        service.recoverUnfinished()
        val row = exportRepo.findById(id)!!
        assertEquals("FAILED", row.status)
        assertTrue(row.error!!.contains("服务重启"), row.error)
    }
}
