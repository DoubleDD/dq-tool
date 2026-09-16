package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.ErrorEvent
import com.example.dq.model.ErrorLevel
import com.example.dq.model.ErrorPage
import com.example.dq.model.ErrorQuery
import com.example.dq.model.ErrorRecord
import com.example.dq.model.ErrorSource
import com.example.dq.model.ErrorStats
import com.example.dq.repository.ErrorRepository
import com.example.dq.repository.Jdbc
import com.example.dq.util.ErrorFingerprint
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.createDirectories

/**
 * 错误中心(统一错误收集):前端 JS/接口错误、后端业务异常、数据库错误、后台任务与启动异常
 * 全部经此入库,按指纹聚合,供「错误中心」页面查询、筛选、标记与导出。
 *
 * 设计要点:
 * - **不阻塞调用线程**:[record] 只做入队;单守护写线程批量 upsert。内核迁移未就绪或队列满时
 *   落磁盘 spool(`logs/error-spool.jsonl`),就绪后回灌入库 —— 启动期/持续写库失败都不丢错误。
 * - **不上抛**:采集本身失败绝不能反噬业务与日志链路,内部异常走独立 logger
 *   `dq.error-center.internal`([INTERNAL_LOGGER]),该 logger 被 server 侧采集器显式跳过,防自噬递归。
 * - **保留策略**:默认保留 [AppConfig.errorRetentionDays] 天、单表上限 [AppConfig.errorMaxRecords] 条,
 *   就绪时执行一次;页面「清理」入口可手动调整。
 */
class ErrorCenterService(
    private val config: AppConfig,
    jdbc: Jdbc,
    /** spool 文件路径;默认数据目录同级 logs/error-spool.jsonl(与运行日志同目录,测试可注入临时路径) */
    spoolFile: Path = defaultSpoolFile(config),
) {

    private val repo = ErrorRepository(jdbc)
    private val json = jacksonObjectMapper()

    /** 内部日志:不被 server 侧采集器收集(否则采集失败会自我循环) */
    private val internalLog = LoggerFactory.getLogger(INTERNAL_LOGGER)

    private val ready = AtomicBoolean(false)
    private val dropped = AtomicLong(0)

    /** 正在写库的批次数(flush 等待用:队列空 ≠ 已写完) */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 待入库事件(有界);满则落 spool,避免内存无限增长 */
    private val queue = LinkedBlockingQueue<ErrorEvent>(QUEUE_CAP)

    /** 启动期/写库失败的事件落盘文件(JSON Lines);就绪后回灌入库并清空 */
    private val spoolFile: Path = spoolFile

    @Volatile
    private var worker: Thread? = null

    // ---------- 采集入口 ----------

    /**
     * 记录一条错误事件(异步,永不抛异常)。采集侧唯一入口。
     */
    fun record(event: ErrorEvent) {
        try {
            if (!ready.get()) {
                spool(event)
                return
            }
            if (!queue.offer(event)) {
                // 队列积压(数据库持续写不动):落盘兜底,避免丢错误
                spool(event)
            }
        } catch (t: Throwable) {
            internalLog.debug("错误事件入队失败(忽略): {}", t.message)
        }
    }

    /**
     * 便捷入口:后端代码显式上报异常(未捕获线程异常、静默 catch 兜底等)。
     * 有异常时自动拼堆栈与线程名。
     */
    fun report(
        source: ErrorSource,
        kind: String,
        level: ErrorLevel,
        message: String,
        throwable: Throwable? = null,
        route: String? = null,
        context: String? = null,
    ) {
        record(
            ErrorEvent(
                source = source,
                kind = kind,
                level = level,
                message = message,
                detail = throwable?.let { stackTraceOf(it) },
                context = context,
                logger = null,
                thread = Thread.currentThread().name,
                route = route,
            ),
        )
    }

    /** 上报异常(来源按类型自动判定:含 SQLException 记为数据库错误) */
    fun reportThrowable(throwable: Throwable, route: String? = null, context: String? = null) {
        val database = generateSequence(throwable) { it.cause }.any { it is java.sql.SQLException }
        report(
            source = if (database) ErrorSource.DATABASE else ErrorSource.BACKEND,
            kind = throwable.javaClass.name,
            level = ErrorLevel.ERROR,
            message = throwable.message ?: throwable.javaClass.simpleName,
            throwable = throwable,
            route = route,
            context = context,
        )
    }

    /** 内核建表/迁移完成后调用:开始异步落库、回灌 spool、执行保留策略 */
    fun markReady() {
        if (!ready.compareAndSet(false, true)) return
        startWorker()
        drainSpool()
        applyRetentionPolicy()
    }

    /**
     * 等待队列排空且当前批次写库完成(测试与退出用);最多等待 [timeoutMs]。
     * @return true = 已全部落库
     */
    fun flush(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while ((queue.isNotEmpty() || inFlight.get() > 0) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        return queue.isEmpty() && inFlight.get() == 0
    }

    // ---------- 查询/管理(错误中心接口用) ----------

    fun query(q: ErrorQuery): ErrorPage = repo.query(q)

    fun stats(): ErrorStats = repo.stats()

    fun findById(id: Long): ErrorRecord? = repo.findById(id)

    /** 按 id 批量取(错误中心「导出所选行」用) */
    fun findByIds(ids: List<Long>): List<ErrorRecord> = repo.findByIds(ids)

    fun updateStatus(ids: List<Long>, status: String, note: String? = null): Int =
        repo.updateStatus(ids, status, note)

    fun delete(ids: List<Long>): Int = repo.delete(ids)

    fun clear(q: ErrorQuery): Int = repo.clear(q)

    /** 手动清理:删除 last_seen 早于 days 天的记录 */
    fun purge(days: Int): Int = repo.purgeBefore(LocalDateTime.now().minusDays(days.coerceAtLeast(1).toLong()))

    /** 当前被丢弃/落盘的错误事件数(队列满或写库失败时为诊断线索) */
    fun droppedCount(): Long = dropped.get()

    /** spool 文件是否还有未回灌的内容(排错用) */
    fun spoolPending(): Boolean = spoolFile.exists() && spoolFile.fileSize() > 0

    // ---------- 内部实现 ----------

    private fun startWorker() {
        if (worker != null) return
        synchronized(this) {
            if (worker != null) return
            val t = Thread({ workerLoop() }, "dq-error-writer")
            t.isDaemon = true
            worker = t
            t.start()
        }
    }

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val first = queue.poll(2, TimeUnit.SECONDS) ?: continue
                val batch = ArrayList<ErrorEvent>(BATCH_MAX)
                batch += first
                queue.drainTo(batch, BATCH_MAX - 1)
                inFlight.incrementAndGet()
                try {
                    persist(batch)
                } finally {
                    inFlight.decrementAndGet()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (t: Throwable) {
                // 兜底:worker 绝不能因单批失败而退出
                internalLog.debug("错误写入线程异常(继续): {}", t.message)
            }
        }
    }

    private fun persist(batch: List<ErrorEvent>) {
        var failed = false
        for (event in batch) {
            try {
                val fp = ErrorFingerprint.of(event.source.name, event.kind, event.message, event.detail)
                repo.upsert(event, fp, config.appVersion.ifBlank { null })
            } catch (t: Throwable) {
                failed = true
                spool(event)
            }
        }
        if (failed) dropped.addAndGet(1)
    }

    /** 落盘 spool(JSON Lines);超过 [SPOOL_MAX_BYTES] 后丢弃并计数,避免写满磁盘 */
    private fun spool(event: ErrorEvent) {
        try {
            if (spoolFile.exists() && spoolFile.fileSize() > SPOOL_MAX_BYTES) {
                dropped.incrementAndGet()
                return
            }
            spoolFile.parent?.createDirectories()
            val line = json.writeValueAsString(SpoolLine.from(event))
            Files.writeString(
                spoolFile, line + "\n", Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (t: Throwable) {
            dropped.incrementAndGet()
            internalLog.debug("错误事件落盘失败(忽略): {}", t.message)
        }
    }

    /** 启动就绪后把 spool 里的错误回灌进队列并清空文件 */
    private fun drainSpool() {
        if (!spoolFile.exists()) return
        try {
            val lines = Files.readAllLines(spoolFile, Charsets.UTF_8)
            Files.deleteIfExists(spoolFile)
            var restored = 0
            for (line in lines) {
                if (line.isBlank()) continue
                runCatching { json.readValue<SpoolLine>(line).toEvent() }
                    .onSuccess { queue.offer(it); restored++ }
                    .onFailure { internalLog.debug("spool 行解析失败(忽略): {}", it.message) }
            }
            if (restored > 0) internalLog.info("回灌启动期错误 {} 条", restored)
        } catch (t: Throwable) {
            internalLog.debug("spool 回灌失败(忽略): {}", t.message)
        }
    }

    /**
     * 执行保留策略:删除超过 [AppConfig.errorRetentionDays] 天的记录,并把总量裁到
     * [AppConfig.errorMaxRecords] 条以内(删最旧)。markReady 时自动执行一次,也可手动触发。
     * @return 本次清理的条数
     */
    fun applyRetentionPolicy(): Int {
        return try {
            val days = config.errorRetentionDays.coerceAtLeast(1)
            val purged = repo.purgeBefore(LocalDateTime.now().minusDays(days.toLong()))
            val trimmed = repo.trimExcess(config.errorMaxRecords.coerceAtLeast(100))
            if (purged > 0 || trimmed > 0) {
                internalLog.info("错误记录保留策略清理:过期 {} 条,超上限 {} 条", purged, trimmed)
            }
            purged + trimmed
        } catch (t: Throwable) {
            internalLog.debug("错误记录保留策略执行失败(忽略): {}", t.message)
            0
        }
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** spool 行(时间戳用字符串,避免依赖 Jackson 的 JavaTime 模块) */
    private data class SpoolLine(
        val source: String,
        val kind: String,
        val level: String,
        val message: String,
        val detail: String?,
        val context: String?,
        val logger: String?,
        val thread: String?,
        val route: String?,
        val occurredAt: String,
    ) {
        fun toEvent() = ErrorEvent(
            source = ErrorSource.valueOf(source),
            kind = kind,
            level = ErrorLevel.valueOf(level),
            message = message,
            detail = detail,
            context = context,
            logger = logger,
            thread = thread,
            route = route,
            occurredAt = LocalDateTime.parse(occurredAt),
        )

        companion object {
            fun from(e: ErrorEvent) = SpoolLine(
                source = e.source.name,
                kind = e.kind,
                level = e.level.name,
                message = e.message,
                detail = e.detail,
                context = e.context,
                logger = e.logger,
                thread = e.thread,
                route = e.route,
                occurredAt = e.occurredAt.toString(),
            )
        }
    }

    companion object {
        /** 内部 logger:server 侧采集器显式跳过,防自噬递归 */
        const val INTERNAL_LOGGER = "dq.error-center.internal"

        /** 默认 spool 路径:数据目录同级 logs/error-spool.jsonl(与 startup.log/运行日志同目录) */
        fun defaultSpoolFile(config: AppConfig): Path =
            Path.of(config.dataDir.toString()).resolveSibling("logs").resolve("error-spool.jsonl")

        private const val QUEUE_CAP = 2_000
        private const val BATCH_MAX = 200
        private const val SPOOL_MAX_BYTES = 20L * 1024 * 1024
    }
}
