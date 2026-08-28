package com.example.dq.model

import java.time.LocalDateTime

/** 抽样导出任务列表视图(任务列表页) */
data class SampleExportTaskView(
    val id: Long,
    /** 上传的 Excel 文件名 */
    val fileName: String,
    /** PENDING/RUNNING/DONE/FAILED */
    val status: String,
    val stage: String?,
    val totalItems: Int,
    val doneItems: Int,
    val dsTotal: Int,
    val dsAdded: Int,
    val dsSkipped: Int,
    val dsFixed: Int,
    val dsError: Int,
    /** zip 文件名(打包完成的文件名部分,未完成为 null) */
    val zipFileName: String?,
    val zipSize: Long?,
    val error: String?,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

/** 抽样导出任务详情:任务字段 + 数据源导入明细(ds_report JSON 反序列化)+ 逐表明细 */
data class SampleExportDetailView(
    val task: SampleExportTaskView,
    /** 数据源导入明细(action: ADDED/ADDED_ERROR/SKIPPED/FIXED/STILL_ERROR/ROW_SKIPPED) */
    val dsReport: List<Map<String, Any?>>,
    val items: List<SampleExportItemView>,
)

/** 抽样导出明细行视图(一张表);datasourceName 由数据源 id 附带 */
data class SampleExportItemView(
    val id: Long,
    val seq: Int,
    val category: String?,
    val sysNo: String?,
    val sysDesc: String?,
    val dbType: String?,
    val host: String?,
    val port: Int?,
    val username: String?,
    val databaseName: String?,
    val schemaName: String?,
    val tableName: String?,
    val tableCnName: String?,
    val dsKey: String?,
    val datasourceId: Long?,
    val datasourceName: String?,
    val sheetName: String?,
    /** PENDING/RUNNING/DONE/FAILED */
    val status: String,
    val rowCount: Int?,
    val error: String?,
    /** 生成的 xlsx 相对路径(类别目录/文件名) */
    val excelFile: String?,
)
