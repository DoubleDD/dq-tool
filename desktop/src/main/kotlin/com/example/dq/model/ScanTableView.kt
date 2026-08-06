package com.example.dq.model

import java.time.LocalDateTime

/** 表级扫描状态视图 */
data class ScanTableView(
    val id: Long,
    val jobId: Long,
    val tableName: String,
    val status: ScanStatus?,
    val sampled: Boolean,
    val sampleRows: Long?,
    val estRows: Long?,
    val sizeBytes: Long?,
    val comment: String?,     // 表注释
    val storageInfo: String?, // 引擎或表空间
    val chunkKey: String?,
    val totalChunks: Int,
    val doneChunks: Int,
    val scannedRows: Long,
    val totalRows: Long?,
    val error: String?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?
) {

    /** 表级进度 0-100;无分段表运行中返回 null */
    fun progressPercent(): Double? {
        if (status == ScanStatus.DONE) return 100.0
        if (totalChunks > 0) return doneChunks * 100.0 / totalChunks
        return null
    }
}
