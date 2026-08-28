package com.example.dq.scan

import com.example.dq.model.ScanAiProgress
import com.example.dq.model.ScanStatus
import com.example.dq.repository.ScanRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 扫描收尾阶段 AI 后续(自动打标/生成表描述)的完成跟踪,把 AI 后续串入扫描流程:
 * 每个 AI 任务入队前 taskSubmitted 计数、执行结束(无论成功/跳过/失败)由任务体 finally taskDone 销记;
 * 只有「全部表到达终态 + AI 后续清零」才把任务收尾为 DONE/FAILED,在此之前任务保持 RUNNING(进度封顶 99%)。
 * 打标/表描述按类别分别计数,经 progress() 暴露给扫描任务视图,供前端分段展示三部分进度。
 * 计数在内存、重启清零;中断任务续扫时 ScanService 会为 DONE 表重新补齐 AI 后续,收尾语义不受重启影响。
 */
class ScanAiTracker(private val repo: ScanRepository) {

    /** AI 后续类别:打标 / 表描述,分开计数供前端分段展示 */
    enum class AiKind { TAG, DOC }

    /** 单 job 的 AI 计数:入队数与完成数按类别分开;未清零数 = 入队 - 完成 */
    private class Counters {
        val tagSubmitted = AtomicInteger()
        val tagDone = AtomicInteger()
        val docSubmitted = AtomicInteger()
        val docDone = AtomicInteger()

        fun submitted(kind: AiKind) = if (kind == AiKind.TAG) tagSubmitted else docSubmitted
        fun done(kind: AiKind) = if (kind == AiKind.TAG) tagDone else docDone
        fun pending() = tagSubmitted.get() - tagDone.get() + docSubmitted.get() - docDone.get()
    }

    private val counts = ConcurrentHashMap<Long, Counters>()
    private val locks = ConcurrentHashMap<Long, Any>()

    /** AI 任务入队前计数;只有真正入队才允许调用(开关关闭/已熔断等提前返回不得计数) */
    fun taskSubmitted(jobId: Long, kind: AiKind) {
        counts(jobId).submitted(kind).incrementAndGet()
    }

    /** AI 任务结束销记;清零后尝试收尾任务 */
    fun taskDone(jobId: Long, kind: AiKind) {
        counts(jobId).done(kind).incrementAndGet()
        if (counts(jobId).pending() <= 0) {
            tryFinishJob(jobId)
        }
    }

    /** 当前未清零的 AI 后续数(续扫收尾与测试用) */
    fun pending(jobId: Long): Int = counts(jobId).pending()

    /**
     * 手动结束任务时清零并移除该 job 的 AI 计数:放弃仍在排队/挂起的 AI 后续,
     * 解除因计数残留(taskSubmitted 后 taskDone 永远不来)导致的 99% 卡死。
     * 此后若有迟到的 taskDone 销记会重建空计数,但任务已落终态,tryFinishJob 不再生效,无实际影响
     */
    fun reset(jobId: Long) {
        counts.remove(jobId)
    }

    /** AI 后续分类进度(扫描任务视图用;先计数后执行,同一类别入队数恒不小于完成数) */
    fun progress(jobId: Long): ScanAiProgress {
        val c = counts(jobId)
        return ScanAiProgress(c.tagSubmitted.get(), c.tagDone.get(), c.docSubmitted.get(), c.docDone.get())
    }

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
            if (counts(jobId).pending() > 0) {
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

    private fun counts(jobId: Long) = counts.computeIfAbsent(jobId) { Counters() }
    private fun lock(jobId: Long) = locks.computeIfAbsent(jobId) { Any() }
}
