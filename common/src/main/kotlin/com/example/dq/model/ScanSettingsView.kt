package com.example.dq.model

/**
 * 扫描全局参数回显:与配置文件默认值合并后的**有效值**(列值永不为空)。
 * customized=true 表示页面已保存过自定义值(DB 中存在覆盖行),此时「恢复默认」可回到配置文件值。
 */
data class ScanSettingsView(
    val workers: Int,
    val chunksPerTable: Int,
    val rowThreshold: Long,
    val sizeThresholdBytes: Long,
    val sampleRows: Long,
    val statementTimeoutSeconds: Int,
    val customized: Boolean,
)
