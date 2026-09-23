package com.example.dq.service

import com.example.dq.model.AiTagBatchTask
import com.example.dq.repository.AiTagBatchTaskRepository
import com.example.dq.repository.DataSourceRepository
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * 批量 AI 打标后台任务(表列表批量打标弹窗 AI 页签,模板同 WordReportExportService):
 * 提交校验后立即返回任务 id,后台固定 2 线程池逐表执行(与 CompareService 同并发口径,
 * 一任务占一 worker、任务内顺序执行,不嵌套线程池);任务落 H2(ai_tag_batch_task,V75),
 * 前端经后台任务中心 `/ai-tag-batch/active` 1s 轮询跟踪进度,终态弹通知(汇总计数落行)。
 * 单表异常(表不存在/候选被删等)计入 skipped 不中断其余表;服务重启残留 PENDING/RUNNING 置 FAILED。
 */
class BatchAiTagTaskService(
    private val batchAiTagService: BatchAiTagService,
    private val repo: AiTagBatchTaskRepository,
    private val dataSourceRepo: DataSourceRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val executor = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread(r, "dq-ai-tag-batch").apply { isDaemon = true }
    }

    /** 提交批量 AI 打标后台任务,返回任务 id;数据源/表清单/AI 可用性校验失败抛异常(不建任务) */
    fun submit(datasourceId: Long, database: String?, schema: String,
               tableNames: List<String>, tagIds: List<Long>): Long {
        dataSourceRepo.findById(datasourceId) ?: throw IllegalArgumentException("数据源不存在: $datasourceId")
        val names = tableNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) throw IllegalArgumentException("请选择要打标的表")
        // 提交时快速失败:未配置大模型/勾选 ∩ AI 候选为空,避免建一个必然全败的任务
        batchAiTagService.requireUsable(tagIds)
        val ids = tagIds.distinct()
        val id = repo.insert(datasourceId, database ?: "", schema, names.size, names)
        executor.execute { run(id, datasourceId, database, schema, names, ids) }
        log.info("批量 AI 打标任务已提交: id={}, datasourceId={}, db={}, schema={}, 表数={}",
            id, datasourceId, database, schema, names.size)
        return id
    }

    /** 任务体:逐表调 BatchAiTagService.tagTable 并分类计数;internal 以便单测同步驱动 */
    internal fun run(id: Long, datasourceId: Long, database: String?, schema: String,
                     tableNames: List<String>, tagIds: List<Long>) {
        repo.markRunning(id)
        var tagged = 0
        var unmatched = 0
        var skipped = 0
        try {
            for ((i, table) in tableNames.withIndex()) {
                repo.updateProgress(id, i, tableNames.size, table)
                try {
                    val r = batchAiTagService.tagTable(datasourceId, database, schema, table, tagIds)
                    when {
                        r.applied -> tagged++
                        r.skipped -> skipped++
                        else -> unmatched++
                    }
                } catch (e: Exception) {
                    // 单表失败(表不存在/候选标记被删/元数据回源失败等)不中断其余表
                    skipped++
                    log.warn("批量 AI 打标单表失败 taskId={} table={}: {}", id, table, e.message)
                }
            }
            repo.finish(id, tagged, unmatched, skipped)
            log.info("批量 AI 打标任务完成: id={}, 打标={}, 未匹配={}, 跳过/失败={}", id, tagged, unmatched, skipped)
        } catch (e: Exception) {
            log.error("批量 AI 打标任务失败: id={}", id, e)
            repo.fail(id, (e.message ?: "打标失败").take(2000))
        }
    }

    /** 后台任务中心轮询:全部未完成任务(跨库) */
    fun listActive(): List<AiTagBatchTask> = repo.listActive()

    /** 任务详情(终态回源:完成通知取汇总计数) */
    fun detail(id: Long): AiTagBatchTask = repo.findById(id) ?: throw IllegalArgumentException("任务不存在: $id")

    /** 服务重启恢复:PENDING/RUNNING 任务置 FAILED(initDatabase 时调用一次) */
    fun recoverUnfinished() {
        val n = repo.failUnfinished()
        if (n > 0) {
            log.warn("服务重启,{} 个未完成的批量 AI 打标任务已置为失败", n)
        }
    }

    private companion object {
        /** 打标 worker 数:与 CompareService 同口径,一任务占一 worker、任务内顺序执行 */
        const val WORKERS = 2
    }
}
