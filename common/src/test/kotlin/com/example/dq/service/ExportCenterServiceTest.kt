package com.example.dq.service

import com.example.dq.model.ExportKind
import com.example.dq.repository.ExportRecordRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * 导出中心点击即登记的状态机:点击导出先落 RUNNING(导出中心立刻可见),
 * 落盘翻 SUCCESS(landed/finalize),失败/取消/重启各有 FAILED 终态。
 */
class ExportCenterServiceTest {

    private class Env {
        val jdbc: Jdbc
        val service: ExportCenterService
        val dataDir = Files.createTempDirectory("export-center-test")

        init {
            val ds = JdbcDataSource()
            ds.setURL("jdbc:h2:mem:export-center-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
            SchemaInit.run(ds)
            jdbc = Jdbc(ds)
            service = ExportCenterService(ExportRecordRepository(jdbc), dataDir)
        }
    }

    @Test
    fun `点击即登记RUNNING 落盘landed翻SUCCESS并回填路径大小`() {
        val env = Env()
        // 端点入口登记「生成中」(文件很大时导出中心立即可见)
        val id = env.service.recordStart(ExportKind.SCAN_EXCEL, "扫描任务 #1 结果", "dq-scan-1.xlsx",
            path = "/api/scans/1/export")
        assertNotNull(id)
        var row = env.service.list(null, null, null, null, 1, 20).items.single()
        assertEquals("RUNNING", row.status)
        assertEquals("dq-scan-1.xlsx", row.fileName)
        assertNull(row.relPath)

        // 直存成功:landed 按文件名翻 SUCCESS + 回填 rel_path;文件不存在大小留空不炸
        env.service.landed("dq-scan-1.xlsx")
        row = env.service.list(null, null, null, null, 1, 20).items.single()
        assertEquals("SUCCESS", row.status)
        assertEquals("exports/dq-scan-1.xlsx", row.relPath)
        assertNull(row.fileSize)

        // 文件真实落盘后,再来一次导出 → landed 回填实测大小 + SHA-256
        Files.createDirectories(env.dataDir.resolve("exports"))
        env.service.recordStart(ExportKind.SCAN_EXCEL, "扫描任务 #2 结果", "dq-scan-2.xlsx",
            path = "/api/scans/2/export")
        val bytes = ByteArray(50)
        Files.write(env.dataDir.resolve("exports/dq-scan-2.xlsx"), bytes)
        env.service.landed("dq-scan-2.xlsx")
        row = env.service.list(null, "dq-scan-2", null, null, 1, 20).items.single()
        assertEquals("SUCCESS", row.status)
        assertEquals(50L, row.fileSize)
        assertNotNull(row.checksum)
        assertEquals(64, row.checksum!!.length)
        // 完整性校验通过 → OK;篡改文件后 → TAMPERED(置灰禁点);文件删除 → MISSING(删除线禁点),目录仍可开
        assertEquals("OK", row.fileState)
        Files.write(env.dataDir.resolve("exports/dq-scan-2.xlsx"),
            byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)
        row = env.service.list(null, "dq-scan-2", null, null, 1, 20).items.single()
        assertEquals("TAMPERED", row.fileState)
        // 文件删除 → 同样 false
        Files.delete(env.dataDir.resolve("exports/dq-scan-2.xlsx"))
        row = env.service.list(null, "dq-scan-2", null, null, 1, 20).items.single()
        assertEquals("MISSING", row.fileState)

        // 无登记/非法文件名静默忽略
        env.service.landed("none.xlsx")
        env.service.landed("../evil")
        assertEquals(2, env.service.list(null, null, null, null, 1, 20).total)
    }

    @Test
    fun `异步任务按key终态 成功补全信息 失败带error`() {
        val env = Env()
        // 报告提交即登记(key 关联)
        env.service.recordStart(ExportKind.REPORT_DOCX, "数据源 local · 数据调研报告", key = "report-export:9")
        var row = env.service.list(null, null, null, null, 1, 20).items.single()
        assertEquals("RUNNING", row.status)

        // 完成:finalize 补 文件名/相对路径/实测大小与 SHA-256(artifact 自动计算)
        val artifact = Files.createTempFile("report-", ".docx")
        Files.write(artifact, ByteArray(128))
        env.service.finalize(ExportKind.REPORT_DOCX, key = "report-export:9",
            fileName = "local-数据调研报告-9.docx", relPath = "reports/local-数据调研报告-9.docx",
            artifact = artifact)
        row = env.service.list(null, null, null, null, 1, 20).items.single()
        assertEquals("SUCCESS", row.status)
        assertEquals("local-数据调研报告-9.docx", row.fileName)
        assertEquals("reports/local-数据调研报告-9.docx", row.relPath)
        assertEquals(128L, row.fileSize)
        assertEquals(64, row.checksum!!.length)

        // 抽样任务失败/取消:FAILED 带 error
        env.service.recordStart(ExportKind.SAMPLE_ZIP, "抽样导出 · 模版.xlsx", key = "sample-export:3")
        env.service.finalize(ExportKind.SAMPLE_ZIP, key = "sample-export:3", error = "数据源检测失败")
        row = env.service.list(null, "模版", null, null, 1, 20).items.single()
        assertEquals("FAILED", row.status)
        assertEquals("数据源检测失败", row.error)

        // 未知 key 静默忽略
        env.service.finalize(ExportKind.SAMPLE_ZIP, key = "sample-export:404", error = "x")
        assertEquals(2, env.service.list(null, null, null, null, 1, 20).total)
    }

    @Test
    fun `失败按路径标记 重启恢复清RUNNING`() {
        val env = Env()
        env.service.recordStart(ExportKind.TRANSFER_ANNOTATION, "标注与描述导出", "dq-annotations-x.json",
            path = "/api/annotations/export")
        env.service.failByPath("/api/annotations/export", "导出失败:HTTP 500 boom")
        var row = env.service.list(null, null, null, null, 1, 20).items.single()
        assertEquals("FAILED", row.status)
        assertTrue(row.error!!.contains("boom"))

        // 服务重启:残留 RUNNING 一律 FAILED
        env.service.recordStart(ExportKind.SCAN_EXCEL, "扫描任务 #5 结果", "dq-scan-5.xlsx",
            path = "/api/scans/5/export")
        env.service.recoverInterrupted()
        row = env.service.list(null, "dq-scan-5", null, null, 1, 20).items.single()
        assertEquals("FAILED", row.status)
        assertTrue(row.error!!.contains("服务重启"))
    }

    @Test
    fun `kind与关键字与时间筛选与删除`() {
        val env = Env()
        env.service.recordStart(ExportKind.SCAN_EXCEL, "扫描任务 #1 结果", "dq-scan-1.xlsx",
            path = "/api/scans/1/export")
        env.service.recordStart(ExportKind.TRANSFER_ANNOTATION, "标注与描述导出", "dq-annotations-x.json",
            path = "/api/annotations/export")

        assertEquals(1, env.service.list(ExportKind.SCAN_EXCEL, null, null, null, 1, 20).total)
        assertEquals(1, env.service.list(null, "标注", null, null, 1, 20).total)
        assertEquals(1, env.service.list(null, "dq-scan-1", null, null, 1, 20).total)
        assertEquals(0, env.service.list(null, "不存在的关键字", null, null, 1, 20).total)
        val today = java.time.LocalDate.now()
        assertEquals(0, env.service.list(null, null, today.plusDays(1).atStartOfDay(), null, 1, 20).total)
        assertEquals(2, env.service.list(null, null, null, today.atStartOfDay(), 1, 20).total)
        assertEquals(0, env.service.list(null, null, null, today.minusDays(1).atStartOfDay(), 1, 20).total)

        // 删除:push 模型下所有类型都是登记记录;未知 kind 400、不存在 id 400
        env.service.recordStart(ExportKind.REPORT_DOCX, "数据源 local · 数据调研报告", key = "report-export:1")
        val id = env.service.list(ExportKind.REPORT_DOCX, null, null, null, 1, 20).items.single()
            .id.split(':')[1].toLong()
        env.service.delete("REPORT_DOCX", id)
        assertThrows(IllegalArgumentException::class.java) { env.service.delete("NOPE", 1) }
        assertThrows(IllegalArgumentException::class.java) { env.service.delete("SCAN_EXCEL", 999999) }
    }
}
