package com.example.dq.model

import java.time.LocalDateTime

/**
 * 批量 AI 打标后台任务(ai_tag_batch_task,V75):状态机 PENDING → RUNNING → DONE/FAILED。
 * active 清单(后台任务中心 1s 轮询)与详情同一模型;终态汇总 tagged/unmatched/skipped 计数,
 * skipped 语义 = 跳过(备份表/已有同标)+ 单表失败。
 */
data class AiTagBatchTask(
    val id: Long,
    val datasourceId: Long,
    /** 空串兜底为 null 展示(无库概念方言) */
    val dbName: String,
    val schemaName: String,
    val tableCount: Int,
    /** 打标表名清单(V76 落库,active 视图带出让表列表页显示打标中 loading;老行缺省空列表) */
    val tableNames: List<String>,
    val status: String,
    /** 当前正在打标的表名 */
    val stage: String?,
    val progressDone: Int,
    val progressTotal: Int,
    val taggedCount: Int?,
    val unmatchedCount: Int?,
    val skippedCount: Int?,
    val error: String?,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)
