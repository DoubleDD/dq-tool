package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.ErrorEvent
import com.example.dq.model.ErrorLevel
import com.example.dq.model.ErrorQuery
import com.example.dq.model.ErrorSource
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * 错误中心:指纹聚合、筛选分页、状态标记、保留期清理、启动期 spool 回灌。
 * 写库为异步单线程,断言前统一 flush 等待落库完成。
 */
class ErrorCenterServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: ErrorCenterService

    private fun config(retentionDays: Int = 30, maxRecords: Int = 20_000) =
        AppConfig(dataDir = tempDir, appVersion = "test", errorRetentionDays = retentionDays, errorMaxRecords = maxRecords)

    private fun newService(cfg: AppConfig = config()): ErrorCenterService {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:error_center_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        // spool 注入本测试独立的临时路径:默认路径是数据目录同级 logs/,多个测试会共用同一文件而互相干扰
        return ErrorCenterService(cfg, Jdbc(ds), tempDir.resolve("error-spool.jsonl"))
    }

    @BeforeEach
    fun setUp() {
        service = newService()
        service.markReady()
    }

    private fun event(
        source: ErrorSource = ErrorSource.BACKEND,
        kind: String = "java.lang.IllegalStateException",
        level: ErrorLevel = ErrorLevel.ERROR,
        message: String = "状态冲突",
        detail: String? = null,
    ) = ErrorEvent(source = source, kind = kind, level = level, message = message, detail = detail)

    @Test
    fun `同指纹聚合计数 不同指纹分行`() {
        service.record(event(message = "任务 12 失败"))
        service.record(event(message = "任务 34 失败"))
        service.record(event(message = "另一个错误"))
        assertTrue(service.flush())

        val page = service.query(ErrorQuery())
        assertEquals(2, page.total)
        val aggregated = page.items.first { it.occurrences == 2 }
        assertTrue(aggregated.message!!.startsWith("任务"), "同一指纹应保留最近一次消息")
    }

    @Test
    fun `来源与级别筛选`() {
        service.record(event(source = ErrorSource.FRONTEND, kind = "TypeError", message = "前端错误"))
        service.record(event(source = ErrorSource.DATABASE, kind = "java.sql.SQLException", message = "数据库错误"))
        service.record(event(source = ErrorSource.BACKEND, level = ErrorLevel.WARN, message = "后端告警"))
        assertTrue(service.flush())

        assertEquals(1, service.query(ErrorQuery(sources = listOf("FRONTEND"))).total)
        assertEquals(1, service.query(ErrorQuery(sources = listOf("DATABASE"))).total)
        assertEquals(1, service.query(ErrorQuery(levels = listOf("WARN"))).total)
        assertEquals(3, service.query(ErrorQuery()).total)
    }

    @Test
    fun `关键词与分页`() {
        repeat(5) { service.record(event(kind = "kind-$it", message = "分页测试 $it")) }
        service.record(event(message = "独特的月亮错误"))
        assertTrue(service.flush())

        val first = service.query(ErrorQuery(page = 1, size = 2))
        assertEquals(6, first.total)
        assertEquals(2, first.items.size)
        // 6 条按每页 2 条共 3 页,第 3 页同样是满页
        assertEquals(2, service.query(ErrorQuery(page = 3, size = 2)).items.size)
        assertEquals(0, service.query(ErrorQuery(page = 4, size = 2)).items.size)
        assertEquals(1, service.query(ErrorQuery(keyword = "月亮")).total)
        assertEquals(5, service.query(ErrorQuery(keyword = "分页测试")).total)
    }

    @Test
    fun `统计按来源与级别分组`() {
        service.record(event(source = ErrorSource.FRONTEND, kind = "TypeError", message = "a"))
        service.record(event(source = ErrorSource.FRONTEND, kind = "RangeError", message = "b"))
        service.record(event(source = ErrorSource.DATABASE, kind = "SQLException", message = "c"))
        assertTrue(service.flush())

        val stats = service.stats()
        assertEquals(3, stats.total)
        assertEquals(3, stats.open)
        assertEquals(2, stats.bySource["FRONTEND"])
        assertEquals(1, stats.bySource["DATABASE"])
        assertEquals(3, stats.byLevel["ERROR"])
        assertEquals(3, stats.todayNew)
    }

    @Test
    fun `状态标记与备注 不改变计数`() {
        service.record(event(message = "待处理错误"))
        service.record(event(message = "待处理错误"))
        assertTrue(service.flush())

        val id = service.query(ErrorQuery()).items.first().id
        assertEquals(1, service.updateStatus(listOf(id), "RESOLVED", "已修复"))
        val record = service.findById(id)!!
        assertEquals("RESOLVED", record.status)
        assertEquals("已修复", record.note)
        assertEquals(2, record.occurrences)

        // 新一次发生只累加计数与 last_seen,不改人工结论
        service.record(event(message = "待处理错误"))
        assertTrue(service.flush())
        val again = service.findById(id)!!
        assertEquals("RESOLVED", again.status)
        assertEquals(3, again.occurrences)
    }

    @Test
    fun `按筛选清空与删除`() {
        service.record(event(source = ErrorSource.FRONTEND, kind = "TypeError", message = "x"))
        service.record(event(source = ErrorSource.BACKEND, kind = "Boom", message = "y"))
        assertTrue(service.flush())

        assertEquals(1, service.clear(ErrorQuery(sources = listOf("FRONTEND"))))
        assertEquals(1, service.query(ErrorQuery()).total)

        val remaining = service.query(ErrorQuery()).items.first().id
        assertEquals(1, service.delete(listOf(remaining)))
        assertEquals(0, service.query(ErrorQuery()).total)
    }

    @Test
    fun `保留期清理删除过期记录`() {
        service.record(event(message = "很旧的错误").copy(occurredAt = LocalDateTime.now().minusDays(40)))
        service.record(event(message = "很新的错误"))
        assertTrue(service.flush())

        assertEquals(1, service.purge(30))
        val page = service.query(ErrorQuery())
        assertEquals(1, page.total)
        assertEquals("很新的错误", page.items.first().message)
    }

    @Test
    fun `单表上限清理删除最旧记录`() {
        val small = newService(config(maxRecords = 100))
        small.markReady()
        repeat(120) { small.record(event(kind = "kind-$it", message = "上限测试 $it")) }
        assertTrue(small.flush())
        assertEquals(120, small.query(ErrorQuery(size = 200)).total)

        small.applyRetentionPolicy()
        assertEquals(100, small.query(ErrorQuery(size = 200)).total)
    }

    @Test
    fun `未就绪时落盘 spool 就绪后回灌入库`() {
        val cfg = config()
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:error_spool_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        // 故意不执行 SchemaInit:模拟内核未就绪
        val pending = ErrorCenterService(cfg, Jdbc(ds), tempDir.resolve("spool-pending.jsonl"))
        pending.record(event(source = ErrorSource.STARTUP, kind = "StartupFailure", message = "启动期错误"))

        assertTrue(pending.spoolPending(), "未就绪的 error 必须落 spool 兜底")

        // 建表后就绪:回灌 spool 并 flush 入库
        SchemaInit.run(ds)
        pending.markReady()
        assertTrue(pending.flush())
        assertFalse(pending.spoolPending(), "回灌后 spool 文件应被清空")

        val page = pending.query(ErrorQuery())
        assertEquals(1, page.total)
        assertEquals("启动期错误", page.items.first().message)
        assertEquals("STARTUP", page.items.first().source)
    }

    @Test
    fun `reportThrowable 按 SQLException 归类为数据库错误`() {
        service.reportThrowable(java.sql.SQLException("连接被拒绝"))
        service.reportThrowable(IllegalStateException("业务状态冲突"))
        assertTrue(service.flush())

        assertEquals(1, service.query(ErrorQuery(sources = listOf("DATABASE"))).total)
        assertEquals(1, service.query(ErrorQuery(sources = listOf("BACKEND"))).total)
        val db = service.query(ErrorQuery(sources = listOf("DATABASE"))).items.first()
        assertEquals("java.sql.SQLException", db.kind)
        assertNotNull(db.detail)
        assertTrue(db.detail!!.contains("连接被拒绝"))
    }
}
