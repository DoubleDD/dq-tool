package com.example.dq.repository

import com.example.dq.model.ScanColumnView
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals

/** 任务删除的级联清理:分段/字段/表/任务四级全部删掉,且不影响其他任务 */
class ScanJobDeleteTest {

    private lateinit var jdbc: Jdbc
    private lateinit var repo: ScanRepository

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:delete-test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = ScanRepository(jdbc)
    }

    @Test
    fun `deleteJobCascadesAllDetails`() {
        val jobId = repo.insertJob(1L, null, "public", false, "[]", 1)
        val tableId = repo.insertScanTable(jobId, "t_user", 100L, 1024L, null, null)
        repo.insertChunk(tableId, 0, null, null, false)
        repo.insertScanColumn(tableId, ScanColumnView.of("name", "varchar(64)", null, true, null, "", 100, 5, 3, 0))

        repo.deleteJob(jobId)

        assertEquals(0, count("scan_chunk"))
        assertEquals(0, count("scan_column"))
        assertEquals(0, count("scan_table"))
        assertEquals(0, count("scan_job"))
    }

    @Test
    fun `deleteJobKeepsOtherJobs`() {
        val jobId = repo.insertJob(1L, null, "public", false, "[]", 1)
        val tableId = repo.insertScanTable(jobId, "t_user", 100L, 1024L, null, null)
        repo.insertChunk(tableId, 0, null, null, false)

        val otherJobId = repo.insertJob(1L, null, "public", false, "[]", 1)
        val otherTableId = repo.insertScanTable(otherJobId, "t_order", 200L, 2048L, null, null)
        repo.insertChunk(otherTableId, 0, null, null, false)

        repo.deleteJob(jobId)

        assertEquals(1, count("scan_job"))
        assertEquals(1, count("scan_table"))
        assertEquals(1, count("scan_chunk"))
    }

    private fun count(table: String): Int {
        return jdbc.queryOne("SELECT COUNT(*) FROM $table") { rs -> rs.getInt(1) }!!
    }
}
