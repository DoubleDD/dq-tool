package com.example.dq.repository

import com.example.dq.model.CompareAiTrace
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * 比对 AI 判定留痕(独立 H2 库 dqaiusage 的 compare_ai_trace 表,与 [AiUsageRepository] 同一数据源):
 * 比对阶段每一次大模型调用记一行(完整 prompt/原始回答/逐条结构化判定结果),
 * 任务/目标 id 与 target_label 均为记录时快照,不跨库 join;
 * 随比对任务删除级联清理(跨库不进主库 tx,失败只记 warn)。
 *
 * 所有写操作(insert/deleteByJob)内部 try-catch 只记 warn 绝不上抛——**留痕失败绝不能炸比对主流程**。
 */
class CompareAiTraceRepository(private val jdbc: Jdbc) {

    /** 留痕表一行(查询用) */
    data class TraceRow(
        val id: Long,
        val jobId: Long,
        val targetId: Long?,
        val targetLabel: String?,
        val scene: String,
        val stage: String,
        val batchNo: Int,
        val model: String?,
        val requestContent: String?,
        val responseContent: String?,
        val resultJson: String?,
        val durationMs: Long?,
        val createdAt: LocalDateTime?,
    )

    /** 落一条留痕;请求/响应内容截断 5 万字符(与 AiUsageService 同口径);失败只记 warn 不上抛 */
    fun insert(trace: CompareAiTrace) {
        try {
            jdbc.update(
                """INSERT INTO compare_ai_trace(job_id, target_id, target_label, scene, stage, batch_no, model,
                       request_content, response_content, result_json, duration_ms, created_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                trace.jobId, trace.targetId, trace.targetLabel?.take(512), trace.scene, trace.stage,
                trace.batchNo, trace.model, truncate(trace.requestContent), truncate(trace.responseContent),
                trace.resultJson, trace.durationMs, LocalDateTime.now(),
            )
        } catch (e: Exception) {
            log.warn("比对 AI 判定留痕写入失败(忽略): jobId={}, stage={}: {}", trace.jobId, trace.stage, e.message)
        }
    }

    /** 按任务查全部留痕(按 目标/时间/id 升序,前端按目标分组展示) */
    fun listByJob(jobId: Long): List<TraceRow> =
        jdbc.query(
            """SELECT id, job_id, target_id, target_label, scene, stage, batch_no, model,
                      request_content, response_content, result_json, duration_ms, created_at
               FROM compare_ai_trace WHERE job_id = ?
               ORDER BY target_id, created_at, id""",
            jobId,
        ) { rs ->
            val targetId = rs.getLong("target_id")
            val targetIdOrNull = if (rs.wasNull()) null else targetId // wasNull 只对最近一次读取有效,先判再读后续列
            val durationMs = rs.getLong("duration_ms")
            TraceRow(
                rs.getLong("id"),
                rs.getLong("job_id"),
                targetIdOrNull,
                rs.getString("target_label"),
                rs.getString("scene"),
                rs.getString("stage"),
                rs.getInt("batch_no"),
                rs.getString("model"),
                rs.getString("request_content"),
                rs.getString("response_content"),
                rs.getString("result_json"),
                if (rs.wasNull()) null else durationMs, // wasNull 对应上一句读取的 duration_ms
                rs.getTimestamp("created_at")?.toLocalDateTime(),
            )
        }

    /** 随比对任务删除级联清理(跨库不进主库 tx;失败只记 warn,不影响任务删除) */
    fun deleteByJob(jobId: Long) {
        try {
            jdbc.update("DELETE FROM compare_ai_trace WHERE job_id = ?", jobId)
        } catch (e: Exception) {
            log.warn("比对 AI 判定留痕级联删除失败(忽略): jobId={}: {}", jobId, e.message)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CompareAiTraceRepository::class.java)

        /** 请求/响应内容落库长度上限(与 AiUsageService.MAX_CONTENT_LENGTH 同口径) */
        const val MAX_CONTENT_LENGTH = 50_000

        fun truncate(s: String?): String? =
            if (s == null || s.length <= MAX_CONTENT_LENGTH) s else s.substring(0, MAX_CONTENT_LENGTH) + "...(截断)"
    }
}
