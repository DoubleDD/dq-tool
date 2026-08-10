package com.example.dq.model

import java.time.LocalDateTime

/** Word 报告导出任务视图(导出任务列表页) */
data class ReportExportView(
    val id: Long,
    val datasourceId: Long,
    val datasourceName: String?,
    val dbName: String?,
    /** 勾选的库;空列表表示全部库 */
    val schemaNames: List<String>,
    /** PENDING/RUNNING/DONE/FAILED */
    val status: String,
    val stage: String?,
    val progressDone: Int,
    val progressTotal: Int,
    /** 报告文件名(导出文件的文件名部分,未完成时为 null) */
    val fileName: String?,
    val fileSize: Long?,
    val error: String?,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

/** 提交导出任务的入参;schemas 为 null 表示全部库 */
data class ReportExportRequest(val schemas: List<String>?)
