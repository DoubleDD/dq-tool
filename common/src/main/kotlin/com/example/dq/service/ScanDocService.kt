package com.example.dq.service

import com.example.dq.model.ScanStatus
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.scan.ScanAiTracker
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 扫描后 AI 生成表描述:表 DONE 后由 ChunkRunner 提交,独立守护线程池(2 worker)异步执行,绝不阻塞扫描 worker。
 * 入队/完成经 ScanAiTracker 计数:全部表终态且 AI 后续清零前,扫描任务保持 RUNNING 不收尾(进度封顶 99%)。
 * 生成逻辑直接复用 TableDocService.generate(实时查元数据 → 大模型 → 落 table_doc);
 * 表已有非空描述、未配置大模型时静默跳过(不浪费 LLM 调用);任务被取消/失败/中断后不再生成;
 * 同一 job 内首次 LLM 调用失败后熔断,该 job 剩余表不再尝试(内存 Set,不持久化)。
 */
class ScanDocService(
    private val aiConfigService: AiConfigService,
    private val scanRepo: ScanRepository,
    private val tableDocRepo: TableDocRepository,
    tableDocService: TableDocService,
    private val aiTracker: ScanAiTracker,
    /** 描述生成调用点(TableDocService 是 final class,测试经此注入 fake);末参为扫描任务 id,用于用量统计关联 */
    private val generate: (Long, String?, String, String, Long?) -> Unit =
        { dsId, db, schema, table, jobId -> tableDocService.generate(dsId, db, schema, table, jobId) },
) {

    private val executor = Executors.newFixedThreadPool(DOC_WORKERS) { r ->
        Thread(r, "dq-scan-doc-worker").apply { isDaemon = true }
    }

    /** 已熔断的任务(LLM 首次调用失败后,该 job 剩余表直接跳过) */
    private val disabledJobs = ConcurrentHashMap.newKeySet<Long>()

    /** 表 DONE 后由 ChunkRunner 调用;开关关闭或本 job 已熔断时直接忽略(不计数) */
    fun submit(jobId: Long, scanTableId: Long) {
        val job = scanRepo.findJob(jobId) ?: return
        if (!job.genDoc || disabledJobs.contains(jobId)) {
            return
        }
        aiTracker.taskSubmitted(jobId)
        executor.execute {
            try {
                runSafely(job, scanTableId)
            } finally {
                aiTracker.taskDone(jobId)
            }
        }
    }

    /** 队列任务体;internal 以便单测绕过队列同步驱动 */
    internal fun runSafely(job: ScanRepository.JobRow, scanTableId: Long) {
        if (disabledJobs.contains(job.id)) {
            return
        }
        try {
            genDoc(job, scanTableId)
        } catch (e: Exception) {
            log.warn("扫描后生成表描述失败 jobId={} scanTableId={}: {}", job.id, scanTableId, e.message)
        }
    }

    private fun genDoc(job: ScanRepository.JobRow, scanTableId: Long) {
        val config = aiConfigService.findConfig()
        if (config == null) {
            log.debug("生成表描述跳过:未配置大模型 jobId={}", job.id)
            return
        }
        // 入队后任务可能被取消/失败/中断:只对仍在运行或已正常完成的任务生成
        val current = scanRepo.findJob(job.id) ?: return
        if (current.status != ScanStatus.RUNNING && current.status != ScanStatus.DONE) {
            log.debug("生成表描述跳过:任务已结束 jobId={} status={}", job.id, current.status)
            return
        }
        val table = scanRepo.findScanTable(scanTableId) ?: return
        val tableName = table.tableName ?: return
        // table_doc 的 db_name 空串兜底口径与 TableDocService.normalizeDb 一致
        val dbName = job.dbName ?: ""
        // 已有非空描述(历史生成或人工编写)的表跳过,不覆盖也不浪费 LLM 调用
        val existing = tableDocRepo.findBySchema(job.datasourceId, dbName, job.schemaName)[tableName]
        if (!existing.isNullOrBlank()) {
            log.debug("生成表描述跳过:表已有描述 jobId={} table={}", job.id, tableName)
            return
        }
        try {
            generate(job.datasourceId, job.dbName, job.schemaName, tableName, job.id)
            log.info("扫描后生成表描述 jobId={} table={}", job.id, tableName)
        } catch (e: IllegalArgumentException) {
            // 表级问题(如表已不存在):只跳过本表,不熔断整个任务
            log.warn("生成表描述跳过本表 jobId={} table={}: {}", job.id, tableName, e.message)
        } catch (e: Exception) {
            disabledJobs.add(job.id)
            log.warn("生成表描述调用大模型失败,本任务剩余表跳过 jobId={} table={}: {}", job.id, tableName, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScanDocService::class.java)

        /** 描述生成 worker 数:LLM 调用为长阻塞请求,与 AutoTagService 同口径 */
        const val DOC_WORKERS = 2
    }
}
