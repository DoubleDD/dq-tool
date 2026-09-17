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
    /** 表级同步:该明细要同步的表清单(V57 tables_json);空 = 非表级 */
    val tables: List<MetaSyncTableRef> = emptyList(),
    /** 库/schema 级同步:该明细要同步的库/schema 清单(V58 schemas_json);空 = 非 schema 级 */
    val schemas: List<MetaSyncSchemaRef> = emptyList(),
)

/** 表级同步的表定位(明细内,数据源由明细行承载) */
data class MetaSyncTableRef(
    val db: String?,
    val schema: String,
    val table: String,
)

/** 库/schema 级同步的 schema 定位(明细内,数据源由明细行承载;db 仅多库方言非空) */
data class MetaSyncSchemaRef(
    val db: String?,
    val schema: String,
)

/** 表级同步的提交入参(带数据源 id) */
data class MetaSyncTableSelector(
    val datasourceId: Long,
    val db: String?,
    val schema: String,
    val table: String,
)

/** 库/schema 级同步的提交入参(带数据源 id) */
data class MetaSyncSchemaSelector(
    val datasourceId: Long,
    val db: String?,
    val schema: String,
)

/** 同步任务详情(任务 + 明细),供前端轮询 */
data class MetaSyncDetail(
    val job: MetaSyncJob,
    val items: List<MetaSyncItem>,
)
