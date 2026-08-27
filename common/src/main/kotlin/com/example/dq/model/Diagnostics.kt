package com.example.dq.model

import java.time.LocalDateTime

/**
 * 系统诊断报告(诊断页聚合视图):运行环境 + 授权 + AI 配置 + 最近失败记录 + 最近错误日志。
 * 秘密字段(密码/SSH 秘密)一律不出现;数据源连通实测结果由单独接口返回([DatasourceCheckResult])。
 */
data class DiagnosticsReport(
    val env: EnvInfo,
    /** 授权状态;读取异常时为 null */
    val license: LicenseStatusView?,
    /** AI 配置是否完整(不实测连通,实测会记一次用量;实测入口在设置页) */
    val aiConfigured: Boolean,
    /** 最近失败的扫描任务(最多 10 个,各含前 20 张失败表) */
    val scanFailures: List<ScanFailureItem>,
    /** 最近失败的 Word 报告导出任务(最多 10 个) */
    val exportFailures: List<ExportFailureItem>,
    /** 最近错误/警告日志(内存环形缓冲摘录);授权码未包含 logs 功能时为 null */
    val recentLogErrors: List<LogErrorItem>?,
    val generatedAt: LocalDateTime,
    val durationMs: Long,
)

/** 运行环境信息(版本/JVM/数据目录/磁盘),排错时定位"跑的是什么、在哪跑、资源够不够" */
data class EnvInfo(
    val appVersion: String,
    val javaVersion: String,
    val os: String,
    val processors: Int,
    val jvmHeapMaxBytes: Long,
    val jvmHeapUsedBytes: Long,
    val uptimeMs: Long,
    val dataDir: String,
    val diskFreeBytes: Long,
    /** 数据目录下 H2 库文件(*.mv.db)总大小 */
    val h2SizeBytes: Long,
)

/** 单个数据源连通性实测结果(诊断页「开始检测」按钮触发,与概览分接口返回) */
data class DatasourceCheckResult(
    val id: Long,
    val name: String?,
    val dbType: String?,
    val jdbcUrl: String?,
    val sshEnabled: Boolean,
    val success: Boolean,
    /** 探测到的数据库兼容模式(成功时,可为空) */
    val dbMode: String?,
    val error: String?,
    val durationMs: Long,
)

/** 一个失败的扫描任务及其失败表(表级失败原因排错主入口) */
data class ScanFailureItem(
    val jobId: Long,
    val datasourceId: Long,
    val datasourceName: String?,
    val dbName: String?,
    val schemaName: String,
    /** 任务级失败原因 */
    val error: String?,
    val finishedAt: LocalDateTime?,
    val failedTables: List<TableError>,
)

/** 一张失败的表及其失败原因 */
data class TableError(
    val tableName: String,
    val error: String?,
)

/** 一个失败的 Word 数据调研报告导出任务(report_export 表;与扫描 Excel 导出/列表导出等其他导出无关) */
data class ExportFailureItem(
    val id: Long,
    val datasourceId: Long,
    val datasourceName: String?,
    /** 目标数据库(仅 SQL Server 等多库方言,其余为空串) */
    val dbName: String,
    /** 选中的库(逗号分隔);null 表示该数据源全部库 */
    val schemaNames: String?,
    val error: String?,
    val finishedAt: LocalDateTime?,
)

/** 一条错误/警告日志摘录(server 侧内存环形缓冲映射而来,common 不依赖 logback) */
data class LogErrorItem(
    val ts: String?,
    val level: String,
    val logger: String?,
    val message: String?,
    val stackTrace: String?,
)
