package com.example.dq.scan

import com.example.dq.model.ScanStatus
import com.example.dq.repository.ScanRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 扫描收尾阶段 AI 后续(自动打标/生成表描述)的完成跟踪,把 AI 后续串入扫描流程:
 * 每个 AI 任务入队前 taskSubmitted 计数、执行结束(无论成功/跳过/失败)由任务体 finally taskDone 销记;
 * 只有「全部表到达终态 + AI 后续清零」才把任务收尾为 DONE/FAILED,在此之前任务保持 RUNNING(进度封顶 99%)。
 * 计数在内存、重启清零;中断任务续扫时 ScanService 会为 DONE 表重新补齐 AI 后续,收尾语义不受重启影响。
 */
class ScanAiTracker(private val repo: ScanRepository) {

    private val pending = ConcurrentHashMap<Long, AtomicInteger>()
    private val locks = ConcurrentHashMap<Long, Any>()

    /** AI 任务入队前计数;只有真正入队才允许调用(开关关闭/已熔断等提前返回不得计数) */
    fun taskSubmitted(jobId: Long) {
        counter(jobId).incrementAndGet()
    }

    /** AI 任务结束销记;清零后尝试收尾任务 */
    fun taskDone(jobId: Long) {
        if (counter(jobId).decrementAndGet() <= 0) {
            tryFinishJob(jobId)
        }
    }

    /** 当前未清零的 AI 后续数(续扫收尾与测试用) */
    fun pending(jobId: Long): Int = counter(jobId).get()

    /**
     * 收尾判定,幂等:表到达终态(ChunkRunner)与 AI 后续销记(taskDone)两条路径都会调用。
     * 任务仍在 RUNNING、AI 后续清零、全部表到达终态,三者同时满足才收尾;有失败表则任务 FAILED。
     */
    fun tryFinishJob(jobId: Long) {
        withJobLock(jobId) {
            val job = repo.findJob(jobId) ?: return@withJobLock
            if (job.status != ScanStatus.RUNNING) {
                return@withJobLock
            }
            if (counter(jobId).get() > 0) {
                return@withJobLock
            }
            val tables = repo.listScanTables(jobId)
            if (tables.any { it.status == ScanStatus.PENDING || it.status == ScanStatus.RUNNING }) {
                return@withJobLock
            }
            val failed = tables.count { it.status == ScanStatus.FAILED }
            if (failed > 0) {
                repo.finishJob(jobId, ScanStatus.FAILED, "$failed 张表统计失败")
            } else {
                repo.finishJob(jobId, ScanStatus.DONE, null)
            }
        }
    }

    /**
     * job 级互斥锁:ChunkRunner 把「表置 DONE + AI 入队计数」放在锁内执行,
     * 避免 tryFinishJob 在两者之间的窗口观察到「表全 DONE 但 AI 尚未计数」而过早收尾
     */
    fun <T> withJobLock(jobId: Long, block: () -> T): T = synchronized(lock(jobId)) { block() }

    private fun counter(jobId: Long) = pending.computeIfAbsent(jobId) { AtomicInteger() }
    private fun lock(jobId: Long) = locks.computeIfAbsent(jobId) { Any() }
}
