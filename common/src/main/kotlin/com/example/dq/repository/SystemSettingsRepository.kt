package com.example.dq.repository

/** 系统全局设置(单行,id 固定 1):扫描全局参数;列为 NULL 表示该项使用配置文件默认值 */
class SystemSettingsRepository(private val jdbc: Jdbc) {

    data class SystemSettingsRow(
        val workers: Int?,
        val chunksPerTable: Int?,
        val rowThreshold: Long?,
        val sizeThresholdBytes: Long?,
        val sampleRows: Long?,
        val statementTimeoutSeconds: Int?,
    ) {
        /** 是否已保存过自定义值(任一字段非空) */
        val customized: Boolean
            get() = workers != null || chunksPerTable != null || rowThreshold != null
                || sizeThresholdBytes != null || sampleRows != null || statementTimeoutSeconds != null
    }

    fun get(): SystemSettingsRow? =
        jdbc.queryOne("SELECT * FROM system_settings WHERE id=1") { rs ->
            SystemSettingsRow(
                workers = (rs.getObject("scan_workers") as Number?)?.toInt(),
                chunksPerTable = (rs.getObject("scan_chunks_per_table") as Number?)?.toInt(),
                rowThreshold = (rs.getObject("scan_row_threshold") as Number?)?.toLong(),
                sizeThresholdBytes = (rs.getObject("scan_size_threshold_bytes") as Number?)?.toLong(),
                sampleRows = (rs.getObject("scan_sample_rows") as Number?)?.toLong(),
                statementTimeoutSeconds = (rs.getObject("scan_statement_timeout_seconds") as Number?)?.toInt(),
            )
        }

    fun upsert(row: SystemSettingsRow) {
        val n = jdbc.update(
            """UPDATE system_settings
               SET scan_workers=?, scan_chunks_per_table=?, scan_row_threshold=?,
                   scan_size_threshold_bytes=?, scan_sample_rows=?, scan_statement_timeout_seconds=?,
                   updated_at=CURRENT_TIMESTAMP
               WHERE id=1""",
            row.workers, row.chunksPerTable, row.rowThreshold,
            row.sizeThresholdBytes, row.sampleRows, row.statementTimeoutSeconds)
        if (n == 0) {
            jdbc.update(
                """INSERT INTO system_settings
                   (id, scan_workers, scan_chunks_per_table, scan_row_threshold,
                    scan_size_threshold_bytes, scan_sample_rows, scan_statement_timeout_seconds)
                   VALUES (1,?,?,?,?,?,?)""",
                row.workers, row.chunksPerTable, row.rowThreshold,
                row.sizeThresholdBytes, row.sampleRows, row.statementTimeoutSeconds)
        }
    }

    /** 删除自定义行,回到全部使用配置文件默认值 */
    fun delete() {
        jdbc.update("DELETE FROM system_settings WHERE id=1")
    }
}
