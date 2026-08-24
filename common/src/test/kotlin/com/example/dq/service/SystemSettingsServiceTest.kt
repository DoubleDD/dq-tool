package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.config.ScanConfig
import com.example.dq.model.ScanSettingsRequest
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SystemSettingsRepository
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** 系统设置服务:DB 自定义值与配置文件默认值逐项合并、保存/恢复默认语义 */
class SystemSettingsServiceTest {

    private lateinit var service: SystemSettingsService

    /** 配置文件默认值:与 application.yml 的 dq.scan.* 默认一致(workers 单独调成 8) */
    private val config = AppConfig(
        dataDir = Files.createTempDirectory("dq-settings-test"),
        scan = ScanConfig(workers = 8, chunksPerTable = 100)
    )

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:system_settings_svc_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        service = SystemSettingsService(SystemSettingsRepository(Jdbc(ds)), config)
    }

    @Test
    fun `未保存时有效值全部回落到配置文件默认`() {
        val view = service.scanSettingsView()
        assertEquals(8, view.workers)
        assertEquals(100, view.chunksPerTable)
        assertEquals(1_000_000L, view.rowThreshold)
        assertEquals(10L * 1024 * 1024 * 1024, view.sizeThresholdBytes)
        assertEquals(100_000L, view.sampleRows)
        assertEquals(1800, view.statementTimeoutSeconds)
        assertFalse(view.customized)
    }

    @Test
    fun `保存后合并生效,未提交字段保留默认`() {
        service.saveScanSettings(ScanSettingsRequest(workers = 16, chunksPerTable = 200))
        val view = service.scanSettingsView()
        assertEquals(16, view.workers)
        assertEquals(200, view.chunksPerTable)
        // 其余仍回落默认
        assertEquals(1_000_000L, view.rowThreshold)
        assertEquals(1800, view.statementTimeoutSeconds)
        assertTrue(view.customized)
        // 扫描读取路径(ScanService/ChunkRunner 用)拿到同样的有效值
        val merged = service.scanSettings()
        assertEquals(16, merged.workers)
        assertEquals(200, merged.chunksPerTable)
    }

    @Test
    fun `取值范围钳制与恢复默认`() {
        service.saveScanSettings(ScanSettingsRequest(
            workers = 9999, chunksPerTable = 0, rowThreshold = -5,
            sizeThresholdBytes = -1, sampleRows = 0, statementTimeoutSeconds = -3
        ))
        val view = service.scanSettingsView()
        assertEquals(128, view.workers)          // 上限钳制
        assertEquals(1, view.chunksPerTable)     // 下限钳制
        assertEquals(0L, view.rowThreshold)
        assertEquals(0L, view.sizeThresholdBytes)
        assertEquals(1L, view.sampleRows)
        assertEquals(1, view.statementTimeoutSeconds)

        service.resetScanSettings()
        val reset = service.scanSettingsView()
        assertFalse(reset.customized)
        assertEquals(8, reset.workers)           // 回到配置文件默认
        assertEquals(100, reset.chunksPerTable)
    }
}
