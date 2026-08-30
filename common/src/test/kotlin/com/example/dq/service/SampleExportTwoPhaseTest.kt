package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.DbType
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.SampleExportRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * 抽样导出两步流程的状态机与落库语义(H2 内存库):
 * 检测完成(DETECTED)仅在 RUNNING 时生效、继续导出(DETECTED→RUNNING)仅一次;
 * 重新导入全量替换明细并重置统计;编辑数据源后连接状态标记清零
 */
class SampleExportTwoPhaseTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: SampleExportRepository
    private lateinit var dsRepo: DataSourceRepository
    private lateinit var dsService: DataSourceService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:sample-export-2p-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = SampleExportRepository(jdbc)
        dsRepo = DataSourceRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("sample-export-2p"))
        dsService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
            SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
    }

    private fun tableRow(seq: Int, table: String) = SampleTableExcelParser.SampleTableRow(
        seq, "类别", null, "描述", DbType.MYSQL, "10.0.0.1", 3306, "root", "pw", "appdb", null, table, "表$seq")

    @Test
    fun `检测完成与继续导出的状态流转`() {
        val id = repo.insert("a.xlsx", 2)
        repo.insertItems(id, listOf(tableRow(1, "t1"), tableRow(2, "t2")))

        // PENDING(未开始)→ DETECTED 不生效
        assertEquals(0, repo.markDetected(id))
        assertEquals("PENDING", repo.findById(id)!!.status)

        repo.markRunning(id)
        assertEquals(1, repo.markDetected(id))
        val detected = repo.findById(id)!!
        assertEquals("DETECTED", detected.status)
        assertEquals("检测完成,待导出", detected.stage)

        // 继续导出:DETECTED → RUNNING,且只能转一次
        assertEquals(1, repo.markExporting(id))
        assertEquals("RUNNING", repo.findById(id)!!.status)
        assertEquals(0, repo.markExporting(id))
    }

    @Test
    fun `重新导入全量替换明细并重置统计`() {
        val id = repo.insert("old.xlsx", 2)
        repo.insertItems(id, listOf(tableRow(1, "t1"), tableRow(2, "t2")))
        repo.updateDsCounts(id, 1, 1, 0, 0, 0, "[{\"action\":\"ADDED\"}]")
        repo.updateProgress(id, 2, 2, "导出 t2")
        repo.markRunning(id)
        repo.markDetected(id)

        repo.replaceItems(id, "new.xlsx", 1)
        repo.insertItems(id, listOf(tableRow(1, "t_new")))

        val row = repo.findById(id)!!
        assertEquals("new.xlsx", row.fileName)
        assertEquals(1, row.totalItems)
        assertEquals(0, row.doneItems)
        assertEquals(0, row.dsTotal)
        assertEquals(0, row.dsAdded)
        assertNull(row.dsReport)
        assertNull(row.startedAt)
        assertNull(row.finishedAt)

        val items = repo.listItems(id)
        assertEquals(1, items.size)
        assertEquals("t_new", items[0].tableName)
    }

    @Test
    fun `编辑数据源后连接状态标记清零`() {
        val id = dsService.create(DataSourceRequest(
            "批量导入库", "jdbc:mysql://10.0.0.1:3306/appdb", "root", "pw", null, null))
        dsRepo.updateConnStatus(id, "ERROR", "连接超时")
        assertEquals("ERROR", dsRepo.findById(id)!!.connStatus)

        // 用户就地编辑修复连接信息后,标记回到「未检测」,由下次检测/导出前复测
        dsService.update(id, DataSourceRequest(
            "批量导入库", "jdbc:mysql://10.0.0.2:3306/appdb", "root", "pw2", null, null))
        val c = dsRepo.findById(id)!!
        assertNull(c.connStatus)
        assertNull(c.connError)
        assertNull(c.connCheckedAt)
    }
}
