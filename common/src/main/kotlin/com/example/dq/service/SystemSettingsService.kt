package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.config.ScanConfig
import com.example.dq.model.ScanSettingsRequest
import com.example.dq.model.ScanSettingsView
import com.example.dq.repository.SystemSettingsRepository

/**
 * 系统全局设置:扫描全局参数等。页面「系统设置」可视化维护,落 H2(system_settings 单行);
 * 页面未设置的项逐项回落到 AppConfig 的 scan.* 默认值(配置文件兜底),与 AI 配置的回落语义一致。
 * 读取为实时合并(每次调用查一次单行,本地 H2 开销可忽略),修改对之后新建/续扫的扫描生效。
 */
class SystemSettingsService(
    private val repo: SystemSettingsRepository,
    private val config: AppConfig,
) {

    /** 合并 DB 自定义值与配置文件默认值后的有效扫描参数(供扫描/规划/分块执行实时读取) */
    fun scanSettings(): ScanConfig {
        val row = repo.get()
        val d = config.scan
        return ScanConfig(
            workers = row?.workers ?: d.workers,
            chunksPerTable = row?.chunksPerTable ?: d.chunksPerTable,
            rowThreshold = row?.rowThreshold ?: d.rowThreshold,
            sizeThresholdBytes = row?.sizeThresholdBytes ?: d.sizeThresholdBytes,
            sampleRows = row?.sampleRows ?: d.sampleRows,
            statementTimeoutSeconds = row?.statementTimeoutSeconds ?: d.statementTimeoutSeconds,
        )
    }

    /** 回显:有效值 + 是否已自定义(前端据此展示「恢复默认」) */
    fun scanSettingsView(): ScanSettingsView {
        val row = repo.get()
        val d = config.scan
        return ScanSettingsView(
            workers = row?.workers ?: d.workers,
            chunksPerTable = row?.chunksPerTable ?: d.chunksPerTable,
            rowThreshold = row?.rowThreshold ?: d.rowThreshold,
            sizeThresholdBytes = row?.sizeThresholdBytes ?: d.sizeThresholdBytes,
            sampleRows = row?.sampleRows ?: d.sampleRows,
            statementTimeoutSeconds = row?.statementTimeoutSeconds ?: d.statementTimeoutSeconds,
            customized = row?.customized == true,
        )
    }

    /** 保存扫描参数:null 字段保留已存值;取值范围与 ScanService.createScan 的 worker 口径一致 */
    fun saveScanSettings(req: ScanSettingsRequest) {
        val prev = repo.get()
        repo.upsert(
            SystemSettingsRepository.SystemSettingsRow(
                workers = req.workers?.coerceIn(1, 128) ?: prev?.workers,
                chunksPerTable = req.chunksPerTable?.coerceAtLeast(1) ?: prev?.chunksPerTable,
                rowThreshold = req.rowThreshold?.coerceAtLeast(0) ?: prev?.rowThreshold,
                sizeThresholdBytes = req.sizeThresholdBytes?.coerceAtLeast(0) ?: prev?.sizeThresholdBytes,
                sampleRows = req.sampleRows?.coerceAtLeast(1) ?: prev?.sampleRows,
                statementTimeoutSeconds = req.statementTimeoutSeconds?.coerceAtLeast(1) ?: prev?.statementTimeoutSeconds,
            )
        )
    }

    /** 恢复默认:删除自定义行,全部回落到配置文件默认值 */
    fun resetScanSettings() {
        repo.delete()
    }
}
