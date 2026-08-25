package com.example.dq.model

/**
 * 扫描全局参数保存入参;字段为 null 表示保留已存值(前端始终全量提交)。
 * 取值经服务层校验后落库(worker 1~128、分段数/采样行数/超时 ≥1、阈值 ≥0)。
 */
data class ScanSettingsRequest(
    val workers: Int? = null,
    val chunksPerTable: Int? = null,
    val rowThreshold: Long? = null,
    val sizeThresholdBytes: Long? = null,
    val sampleRows: Long? = null,
    val statementTimeoutSeconds: Int? = null,
)
