package com.example.dq.model

import java.time.LocalDateTime

/** 任务进度视图 */
data class ScanJobView(
    val id: Long,
    val datasourceId: Long,
    val datasourceName: String?,
    val dbType: DbType?,
    val dbName: String?,
    val schemaName: String?,
    val status: ScanStatus?,
    val forceFull: Boolean,
    val nullRules: List<NullRule>?,
    val totalTables: Int,
    val doneTables: Int,
    val progressPercent: Double,
    val error: String?,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
    val events: List<ScanJobEvent>?,      // 状态变更时间线(创建/开始/继续/完成等)
    val tables: List<ScanTableView>?,     // 仅详情接口填充
    val workers: Int? = null,             // 并发 worker 线程数;null 表示使用配置默认
)
