package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ScanColumnView
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * 扫描结果 Excel 导出(H2 内存库):
 * 最新快照导出(exportLatest,每表跨任务取最近一次表级 DONE)+ 按任务导出(export)回归。
 * 不连真实业务库,扫描快照直接落库构造。
 */
class ExportServiceTest {

    private lateinit var scanRepo: ScanRepository
    private lateinit var exportService: ExportService
    private var dsId: Long = 0

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:export-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        scanRepo = ScanRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        dsId = dsRepo.insert(DataSourceConfig().apply {
            name = "测试库"
            dbType = DbType.MYSQL
            jdbcUrl = "jdbc:mysql://localhost:3306/x"
        })
        val config = AppConfig(dataDir = Files.createTempDirectory("dq-export-test"))
        val crypto = CryptoUtil(config)
        val schemaStatRepo = SchemaStatRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        val tableDocRepo = TableDocRepository(jdbc)
        val tagRepo = TagRepository(jdbc)
        val dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, schemaStatRepo, metaCacheRepo)
        val executor = ScanExecutor(config)
        val aiTracker = ScanAiTracker(scanRepo)
        val aiConfigService = AiConfigService(AiConfigRepository(jdbc), crypto, config, AiService())
        val tagService = TagService(tagRepo, dsRepo)
        val autoTagService = AutoTagService(aiConfigService, AiService(), tagService, tagRepo, scanRepo,
            tableDocRepo, dataSourceService, DialectFactory, aiTracker)
        val tableDocService = TableDocService(tableDocRepo, aiConfigService, AiService(), dataSourceService, DialectFactory)
        val scanDocService = ScanDocService(aiConfigService, scanRepo, tableDocRepo, tableDocService, aiTracker)
        val systemSettingsService = SystemSettingsService(SystemSettingsRepository(jdbc), config)
        val chunkRunner = ChunkRunner(scanRepo, dataSourceService, DialectFactory, systemSettingsService, executor,
            tagService, autoTagService, scanDocService, aiTracker)
        val scanService = ScanService(scanRepo, dsRepo, schemaStatRepo, metaCacheRepo, dataSourceService,
            DialectFactory, systemSettingsService, executor, chunkRunner, autoTagService, scanDocService, aiTracker)
        exportService = ExportService(scanService, tableDocRepo)
    }

    /** 建一个 RUNNING 的 job(schema 固定 s1,无库概念口径 db=null) */
    private fun newJob(): Long {
        val jobId = scanRepo.insertJob(dsId, null, "s1", false, "[]", 0, false, null, true)
        scanRepo.markJobRunning(jobId)
        return jobId
    }

    /** 表置 DONE 并写入字段快照 */
    private fun finishDone(jobId: Long, table: String, totalRows: Long, vararg cols: ScanColumnView): Long {
        val tableId = scanRepo.insertScanTable(jobId, table, totalRows, null, table + "注释", null)
        scanRepo.finishTable(tableId, ScanStatus.DONE, totalRows, null)
        cols.forEach { scanRepo.insertScanColumn(tableId, it) }
        return tableId
    }

    private fun col(name: String, type: String, total: Long, nullCount: Long): ScanColumnView =
        ScanColumnView.of(name, type, null, null, null, null, total, nullCount, 0, 0)

    /** 导出到内存并读回 xlsx */
    private fun exportToWorkbook(export: (ByteArrayOutputStream) -> Unit): XSSFWorkbook {
        val out = ByteArrayOutputStream()
        export(out)
        return XSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `最新导出跨任务取每表最近一次DONE快照`() {
        // 任务1:表 A DONE,有字段数据
        val job1 = newJob()
        finishDone(job1, "table_a", 100L,
            col("id", "bigint", 100, 0),
            col("name", "varchar(50)", 100, 10))
        scanRepo.finishJob(job1, ScanStatus.DONE, null)
        // 任务2:表 A FAILED(即便残留字段数据也不应被取到),表 B DONE
        val job2 = newJob()
        val tableA2 = scanRepo.insertScanTable(job2, "table_a", 100L, null, "table_a注释", null)
        scanRepo.finishTable(tableA2, ScanStatus.FAILED, null, "模拟失败")
        scanRepo.insertScanColumn(tableA2, col("ghost_col", "int", 1, 0))
        finishDone(job2, "table_b", 200L, col("email", "varchar(64)", 200, 5))
        scanRepo.finishJob(job2, ScanStatus.DONE, null)

        val wb = exportToWorkbook { exportService.exportLatest(dsId, null, "s1", null, null, it) }

        // sheet 结构:概览/表列表/字段汇总 + 每张 DONE 表一个字段明细 sheet;无「异常表」
        assertNull(wb.getSheet("异常表"))
        assertNotNull(wb.getSheet("概览"))
        assertNotNull(wb.getSheet("表列表"))
        assertNotNull(wb.getSheet("table_a"))
        assertNotNull(wb.getSheet("table_b"))
        // 「字段汇总」:A 取任务1的字段(无任务2残留),B 取任务2的字段
        val sheet = wb.getSheet("字段汇总")
        val rows = (1..sheet.lastRowNum).map { r ->
            sheet.getRow(r).getCell(0).stringCellValue to sheet.getRow(r).getCell(2).stringCellValue
        }
        assertEquals(
            listOf("table_a" to "id", "table_a" to "name", "table_b" to "email"),
            rows)
        // 表 A 的字段明细 sheet 同样来自任务1(无 ghost_col)
        val sheetA = wb.getSheet("table_a")
        val colsA = (1..sheetA.lastRowNum).map { sheetA.getRow(it).getCell(2).stringCellValue }
        assertEquals(listOf("id", "name"), colsA)
        // 概览:最新口径文案 + 统计总结(2 表全 DONE,无失败);口径说明与统计总结之间有一个空行,getRow 可能为 null
        val overview = wb.getSheet("概览")
        val kvText = (0..overview.lastRowNum).joinToString("\n") { r ->
            val row = overview.getRow(r) ?: return@joinToString ""
            "${row.getCell(0)?.stringCellValue ?: ""}=${row.getCell(1)?.stringCellValue ?: ""}"
        }
        assertTrue(kvText.contains("数据口径=各表最近一次已完成扫描的快照,可能来自不同任务"), kvText)
        assertTrue(kvText.contains("最晚扫描完成时间="), kvText)
        assertTrue(kvText.contains("统计表数=2(完成 2,失败 0)"), kvText)
    }

    @Test
    fun `最新导出无任何DONE数据时抛IllegalStateException`() {
        // 只有一个全 FAILED 的任务,没有任何 DONE 表
        val jobId = newJob()
        val tableId = scanRepo.insertScanTable(jobId, "table_x", 10L, null, null, null)
        scanRepo.finishTable(tableId, ScanStatus.FAILED, null, "boom")
        scanRepo.finishJob(jobId, ScanStatus.FAILED, "boom")

        val e = assertThrows(IllegalStateException::class.java) {
            exportService.exportLatest(dsId, null, "s1", null, null, ByteArrayOutputStream())
        }
        assertTrue(e.message!!.contains("扫描"), e.message)
    }

    @Test
    fun `按任务导出的sheet结构与重构前一致`() {
        val jobId = newJob()
        finishDone(jobId, "t_done", 10L, col("c1", "int", 10, 1))
        val failedId = scanRepo.insertScanTable(jobId, "t_failed", 10L, null, null, null)
        scanRepo.finishTable(failedId, ScanStatus.FAILED, null, "boom")
        scanRepo.finishJob(jobId, ScanStatus.DONE, null)

        val wb = exportToWorkbook { exportService.export(jobId, it) }

        val names = (0 until wb.numberOfSheets).map { wb.getSheetName(it) }
        // 概览/表列表/字段汇总/字段明细(每张 DONE 表一个 sheet)/异常表
        assertEquals(listOf("概览", "表列表", "字段汇总", "t_done", "异常表"), names)
    }
}
