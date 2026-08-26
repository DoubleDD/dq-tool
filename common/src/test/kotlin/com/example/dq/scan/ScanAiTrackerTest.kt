package com.example.dq.scan

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ScanStatus
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

/**
 * ScanAiTracker 收尾判定(H2 内存库):AI 后续串入扫描流程 ——
 * 全部表到达终态且 AI 后续清零前任务保持 RUNNING,清零后才收尾 DONE/FAILED。
 */
class ScanAiTrackerTest {

    private lateinit var scanRepo: ScanRepository
    private lateinit var tracker: ScanAiTracker
    private var dsId: Long = 0

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ai-tracker-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        scanRepo = ScanRepository(jdbc)
        tracker = ScanAiTracker(scanRepo)
        dsId = DataSourceRepository(jdbc).insert(DataSourceConfig().apply {
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
    fun `全部表DONE且无AI后续则收尾DONE`() {
        val (jobId, tables) = newRunningJob(2)
        tables.forEach { scanRepo.finishTable(it, ScanStatus.DONE, 100L, null) }

        tracker.tryFinishJob(jobId)

        val job = scanRepo.findJob(jobId)!!
        assertEquals(ScanStatus.DONE, job.status)
        assertNotNull(job.finishedAt)
    }

    @Test
    fun `AI后续未清零时不收尾,清零后收尾`() {
        val (jobId, tables) = newRunningJob(1)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        tracker.taskSubmitted(jobId)

        tracker.tryFinishJob(jobId) // 表全 DONE 但 AI 未清零:不收尾
        assertEquals(ScanStatus.RUNNING, scanRepo.findJob(jobId)!!.status)
        assertNull(scanRepo.findJob(jobId)!!.finishedAt)

        tracker.taskDone(jobId) // AI 清零,随之收尾
        assertEquals(ScanStatus.DONE, scanRepo.findJob(jobId)!!.status)
        assertEquals(0, tracker.pending(jobId))
    }

    @Test
    fun `有失败表时收尾为FAILED并带失败数`() {
        val (jobId, tables) = newRunningJob(2)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        scanRepo.finishTable(tables[1], ScanStatus.FAILED, null, "规划失败")

        tracker.tryFinishJob(jobId)

        val job = scanRepo.findJob(jobId)!!
        assertEquals(ScanStatus.FAILED, job.status)
        assertEquals("1 张表统计失败", job.error)
    }

    @Test
    fun `仍有表未完成时不收尾`() {
        val (jobId, tables) = newRunningJob(2)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        // tables[1] 保持 PENDING

        tracker.tryFinishJob(jobId)

        assertEquals(ScanStatus.RUNNING, scanRepo.findJob(jobId)!!.status)
    }

    @Test
    fun `非RUNNING任务不收尾(取消后AI销记不复活任务)`() {
        val (jobId, tables) = newRunningJob(1)
        scanRepo.finishTable(tables[0], ScanStatus.DONE, 100L, null)
        tracker.taskSubmitted(jobId)
        scanRepo.updateJobStatus(jobId, ScanStatus.CANCELED)

        tracker.taskDone(jobId)

        assertEquals(ScanStatus.CANCELED, scanRepo.findJob(jobId)!!.status)
    }
}
