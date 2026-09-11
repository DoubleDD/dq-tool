package com.example.dq.model

import java.time.LocalDateTime

/** 元数据批量同步任务(V41 meta_sync_job);status: PENDING/RUNNING/DONE/FAILED/CANCELED */
data class MetaSyncJob(
    val id: Long,
    val status: String,
    val totalDs: Int,
    val doneDs: Int,
    val failedDs: Int,
    val error: String?,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

/** 同步任务明细(逐数据源一行,V41 meta_sync_item);datasourceName 为快照,数据源删除后仍可展示 */
data class MetaSyncItem(
    val id: Long,
    val jobId: Long,
    val datasourceId: Long,
    val datasourceName: String?,
    val status: String,
    val dbCount: Int,
    val schemaCount: Int,
    val tableCount: Int,
    val progress: String?,
    val error: String?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

/** 同步任务详情(任务 + 明细),供前端轮询 */
data class MetaSyncDetail(
    val job: MetaSyncJob,
    val items: List<MetaSyncItem>,
)
