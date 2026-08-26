package com.example.dq.repository

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 系统全局设置(system_settings 单行)读写与删除语义 */
class SystemSettingsRepositoryTest {

    private lateinit var repo: SystemSettingsRepository
    private lateinit var jdbc: Jdbc

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:system_settings_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        repo = SystemSettingsRepository(jdbc)
    }

    private fun row() = SystemSettingsRepository.SystemSettingsRow(
        workers = 16, chunksPerTable = 200, rowThreshold = 2_000_000L,
        sizeThresholdBytes = 20L * 1024 * 1024 * 1024, sampleRows = 50_000L, statementTimeoutSeconds = 900
    )

    @Test
    fun `初始无行,get 返回 null`() {
        assertNull(repo.get())
    }

    @Test
    fun `upsert 后单行往返,重复 upsert 不产生新行`() {
        repo.upsert(row())
        val read = repo.get()
        assertEquals(16, read!!.workers)
        assertEquals(200, read.chunksPerTable)
        assertEquals(2_000_000L, read.rowThreshold)
        assertEquals(20L * 1024 * 1024 * 1024, read.sizeThresholdBytes)
        assertEquals(50_000L, read.sampleRows)
        assertEquals(900, read.statementTimeoutSeconds)
        assertTrue(read.customized)

        repo.upsert(row().copy(workers = 4, statementTimeoutSeconds = null))
        val after = repo.get()!!
        assertEquals(4, after.workers)
        // 仓储层按入参写入:null 即清空该列(「null 保留旧值」由服务层负责,见 SystemSettingsServiceTest)
        assertEquals(200, after.chunksPerTable)
        assertNull(after.statementTimeoutSeconds)
        assertEquals(1, jdbc.queryOne("SELECT COUNT(*) FROM system_settings") { it.getLong(1) })
    }

    @Test
    fun `全空行 customized 为 false,delete 后回到无行`() {
        repo.upsert(SystemSettingsRepository.SystemSettingsRow(null, null, null, null, null, null))
        assertFalse(repo.get()!!.customized)

        repo.upsert(row())
        assertTrue(repo.get()!!.customized)
        repo.delete()
        assertNull(repo.get())
    }

    @Test
    fun `browserApp 列往返,resetScan 只清扫描列保留浏览器选择`() {
        repo.upsert(row().copy(browserApp = "chrome"))
        assertEquals("chrome", repo.get()!!.browserApp)

        repo.resetScan()
        val after = repo.get()!!
        // 扫描列全部清空,浏览器选择保留;customized 只看扫描列
        assertNull(after.workers)
        assertNull(after.statementTimeoutSeconds)
        assertEquals("chrome", after.browserApp)
        assertFalse(after.customized)
    }
}
