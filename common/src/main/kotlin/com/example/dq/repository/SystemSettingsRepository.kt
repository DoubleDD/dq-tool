package com.example.dq.repository

/** 系统全局设置(单行,id 固定 1):扫描全局参数 + 应用模式首选浏览器;扫描列 NULL 表示该项使用配置文件默认值 */
class SystemSettingsRepository(private val jdbc: Jdbc) {

    data class SystemSettingsRow(
        val workers: Int?,
        val chunksPerTable: Int?,
        val rowThreshold: Long?,
        val sizeThresholdBytes: Long?,
        val sampleRows: Long?,
        val statementTimeoutSeconds: Int?,
        /** 应用模式首选浏览器 id(null=自动按系统优先级选择);浏览器清单由 server 壳层探测 */
        val browserApp: String? = null,
    ) {
        /** 是否已保存过扫描参数自定义值(任一扫描字段非空;浏览器选择不算扫描自定义) */
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
                browserApp = rs.getString("browser_app"),
            )
        }

    fun upsert(row: SystemSettingsRow) {
        val n = jdbc.update(
            """UPDATE system_settings
               SET scan_workers=?, scan_chunks_per_table=?, scan_row_threshold=?,
                   scan_size_threshold_bytes=?, scan_sample_rows=?, scan_statement_timeout_seconds=?,
                   browser_app=?, updated_at=CURRENT_TIMESTAMP
               WHERE id=1""",
            row.workers, row.chunksPerTable, row.rowThreshold,
            row.sizeThresholdBytes, row.sampleRows, row.statementTimeoutSeconds, row.browserApp)
        if (n == 0) {
            jdbc.update(
                """INSERT INTO system_settings
                   (id, scan_workers, scan_chunks_per_table, scan_row_threshold,
                    scan_size_threshold_bytes, scan_sample_rows, scan_statement_timeout_seconds, browser_app)
                   VALUES (1,?,?,?,?,?,?,?)""",
                row.workers, row.chunksPerTable, row.rowThreshold,
                row.sizeThresholdBytes, row.sampleRows, row.statementTimeoutSeconds, row.browserApp)
        }
    }

    /** 仅清空扫描参数列(扫描设置「恢复默认」),保留浏览器等其他设置 */
    fun resetScan() {
        jdbc.update(
            """UPDATE system_settings
               SET scan_workers=NULL, scan_chunks_per_table=NULL, scan_row_threshold=NULL,
                   scan_size_threshold_bytes=NULL, scan_sample_rows=NULL, scan_statement_timeout_seconds=NULL,
                   updated_at=CURRENT_TIMESTAMP
               WHERE id=1""")
    }

    /** 删除自定义行,回到全部使用配置文件默认值 */
    fun delete() {
        jdbc.update("DELETE FROM system_settings WHERE id=1")
    }
}
