package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.CryptoUtil
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.h2.jdbcx.JdbcDataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * 比对报告导出件跟踪(V65):服务端直存 <数据目录>/compare(任务 ID 前缀命名,同名覆盖只留最后一次),
 * 落库导出状态 + SHA-256 checksum;「打开文件」口径 = 文件存在且 checksum 与库中一致,
 * 被篡改/删除则仅「打开文件夹」可点(退化为打开 compare 目录)。
 */
class CompareExportFileTest {

    private class Env(val compareDir: Path) {
        val repo: CompareRepository
        val service: CompareService
        val jdbc: Jdbc

        init {
            val ds = JdbcDataSource()
            ds.setURL("jdbc:h2:mem:compare-export-file-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
            SchemaInit.run(ds)
            jdbc = Jdbc(ds)
            repo = CompareRepository(jdbc)
            val metaCacheRepo = MetaCacheRepository(jdbc)
            val tableSystemRepo = TableSystemRepository(jdbc)
            val dsRepo = DataSourceRepository(jdbc)
            val config = AppConfig(dataDir = Files.createTempDirectory("compare-export-file"))
            val dataSourceService = DataSourceService(dsRepo, CryptoUtil(config), DialectFactory, config,
                SchemaStatRepository(jdbc), metaCacheRepo)
            dataSourceService.create(DataSourceRequest(
                "基准库", "jdbc:mysql://localhost:3306/reservoir_base", "root", "p", null, null))
            dataSourceService.create(DataSourceRequest(
                "厂商库", "jdbc:mysql://localhost:3306/reservoir_vendor", "root", "p", null, null))
            val metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
                SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
            service = CompareService(repo, dataSourceService, DialectFactory,
                metadataService, mockk(relaxed = true), tableSystemRepo, compareDir = compareDir)
        }

        /** 造一个已完成任务 + 一个目标(无差异行,导出只出总览/行级/字段级空表) */
        fun seedJob(name: String = "水库比对"): Long {
            val jobId = repo.insertJob(name, 1L, "reservoir_base", null, "reservoir_base_info",
                "id", """["id","name"]""", 1)
            repo.insertTarget(jobId, 2L, "厂商库", "reservoir_vendor", null, "t_reservoir_info")
            repo.finishJob(jobId)
            return jobId
        }
    }

    @Test
    fun `导出直存compare目录 ID前缀命名 落库checksum 打开可点`() {
        val env = Env(Files.createTempDirectory("compare-out"))
        val jobId = env.seedJob()
        val result = env.service.exportToFile(jobId)

        assertEquals("$jobId-水库比对.xlsx", result.name)
        assertTrue(Files.isRegularFile(Path.of(result.path)), result.path)
        val row = env.repo.getJob(jobId)!!
        assertEquals("DONE", row.exportStatus)
        assertEquals(result.name, row.exportFile)
        assertEquals(result.checksum, row.exportChecksum)
        assertNotNull(row.exportAt)
        // 打开口径:文件存在且 checksum 一致 → 打开文件/打开文件夹均可点
        assertTrue(env.service.exportFileOk(row.exportStatus, row.exportFile, row.exportChecksum))
        assertEquals(Path.of(result.path), env.service.resolveExportPath(jobId))
        // 通知与「打开文件夹」定位到的就是导出件本身
        assertEquals(Path.of(result.path), env.service.revealExportPath(jobId))
    }

    @Test
    fun `任务改名后重导出 旧件清除只留最后一次`() {
        val env = Env(Files.createTempDirectory("compare-out"))
        val jobId = env.seedJob()
        env.service.exportToFile(jobId)
        env.jdbc.update("UPDATE compare_job SET name=? WHERE id=?", "改名后比对", jobId)

        val again = env.service.exportToFile(jobId)
        assertEquals("$jobId-改名后比对.xlsx", again.name)
        // compare 目录只剩最后一次的导出件(旧名文件被清除)
        try {
            val left = Files.list(env.compareDir).use { s -> s.toList() }
            assertEquals(1, left.size)
            assertEquals(again.name, left.single().fileName.toString())
        } catch (e: Exception) {
            throw e
        }
        val row = env.repo.getJob(jobId)!!
        assertEquals(again.checksum, row.exportChecksum)
    }

    @Test
    fun `文件被篡改或删除 打开文件不可用 打开文件夹退化为compare目录`() {
        val env = Env(Files.createTempDirectory("compare-out"))
        val jobId = env.seedJob()
        val result = env.service.exportToFile(jobId)
        val file = Path.of(result.path)
        val row = env.repo.getJob(jobId)!!

        // 篡改:追加字节 → checksum 失配 → 打开文件 409;打开文件夹仍可点(文件还在,定位到它便于人工处理)
        Files.write(file, byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)
        assertFalse(env.service.exportFileOk(row.exportStatus, row.exportFile, row.exportChecksum))
        assertThrows(IllegalStateException::class.java) { env.service.resolveExportPath(jobId) }
        assertEquals(file.toRealPath(), env.service.revealExportPath(jobId).toRealPath())

        // 删除:文件不存在 → 打开文件 409,打开文件夹退化为 compare 目录本身
        Files.delete(file)
        assertFalse(env.service.exportFileOk(row.exportStatus, row.exportFile, row.exportChecksum))
        assertThrows(IllegalStateException::class.java) { env.service.resolveExportPath(jobId) }
        assertEquals(env.compareDir.toRealPath(), env.service.revealExportPath(jobId).toRealPath())
    }

    @Test
    fun `从未导出 打开口径全关 打开文件夹退化为compare目录`() {
        val env = Env(Files.createTempDirectory("compare-out"))
        val jobId = env.seedJob()
        val row = env.repo.getJob(jobId)!!
        assertFalse(env.service.exportFileOk(row.exportStatus, row.exportFile, row.exportChecksum))
        assertThrows(IllegalStateException::class.java) { env.service.resolveExportPath(jobId) }
        assertEquals(env.compareDir.toRealPath(), env.service.revealExportPath(jobId).toRealPath())
    }

    @Test
    fun `重新比对后导出状态复位 打开文件关闭 旧件保留可定位`() {
        val env = Env(Files.createTempDirectory("compare-out"))
        val jobId = env.seedJob()
        val result = env.service.exportToFile(jobId)
        assertTrue(env.service.exportFileOk("DONE", result.name, result.checksum))

        env.repo.markRerun(jobId, 1)
        val row = env.repo.getJob(jobId)!!
        assertNull(row.exportStatus)
        // 旧件保留(「打开文件夹」仍可定位),但「打开文件」/「已导出」口径随重跑关闭
        assertTrue(Files.isRegularFile(Path.of(result.path)))
        assertFalse(env.service.exportFileOk(row.exportStatus, row.exportFile, row.exportChecksum))
        assertThrows(IllegalStateException::class.java) { env.service.resolveExportPath(jobId) }
        assertEquals(Path.of(result.path).toRealPath(), env.service.revealExportPath(jobId).toRealPath())
    }
}
