package com.example.dq.model

/**
 * 扫描记录导出文件格式(Jackson 2 序列化):把扫描任务连同事件/表/分段/字段明细打包成 JSON,
 * 在另一台机器的部署中导入,扫描记录列表即可看到导入的历史记录。
 * 跨实例对齐键:数据源按 数据源名(导入时由用户映射到本机数据源),任务去重按
 * 数据源 + db + schema + 创建时间;不导出任何内部 id,导入时全部重新生成。
 * 时间字段统一为 ISO_LOCAL_DATE_TIME 字符串(与 H2 TIMESTAMP 列的 LocalDateTime 读写口径一致,直接透传)。
 */
data class ScanExportFile(
    val app: String,
    val version: Int,
    val exportedAt: String,
    val jobs: List<ScanJobExport> = emptyList(),
)

/** 单个扫描任务及其全部明细 */
data class ScanJobExport(
    val datasourceName: String,
    val dbName: String? = null,
    val schemaName: String,
    val status: ScanStatus,
    val forceFull: Boolean = false,
    val autoTag: Boolean = false,
    val workers: Int? = null,
    val genDoc: Boolean = true,
    val nullRules: String? = null,   // null_rules CLOB JSON 原文透传
    val totalTables: Int = 0,
    val doneTables: Int = 0,
    val error: String? = null,
    val createdAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val events: List<ScanEventExport> = emptyList(),
    val tables: List<ScanTableExport> = emptyList(),
)

/** 任务状态变更事件(时间线) */
data class ScanEventExport(
    val status: ScanStatus,
    val createdAt: String? = null,
)

/** 表级扫描结果及其分段/字段明细 */
data class ScanTableExport(
    val tableName: String,
    val status: ScanStatus,
    val sampled: Boolean = false,
    val sampleRows: Long? = null,
    val estRows: Long? = null,
    val sizeBytes: Long? = null,
    val chunkKey: String? = null,
    val comment: String? = null,
    val storageInfo: String? = null,
    val totalChunks: Int = 0,
    val doneChunks: Int = 0,
    val scannedRows: Long = 0,
    val totalRows: Long? = null,
    val error: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val chunks: List<ScanChunkExport> = emptyList(),
    val columns: List<ScanColumnExport> = emptyList(),
)

/** 分段扫描结果(col_stats CLOB JSON 原文透传) */
data class ScanChunkExport(
    val seq: Int,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val nullChunk: Boolean = false,
    val status: ScanStatus,
    val rowCount: Long? = null,
    val colStats: String? = null,
    val attempts: Int = 0,
    val error: String? = null,
)

/** 字段级统计结果(不导出 valueCount/fillRate 等展示层派生值,导入后由视图模型重算) */
data class ScanColumnExport(
    val columnName: String,
    val columnType: String? = null,
    val columnComment: String? = null,
    val nullable: Boolean? = null,
    val defaultValue: String? = null,
    val keyLabel: String? = null,
    val totalRows: Long? = null,
    val nullCount: Long? = null,
    val emptyCount: Long? = null,
    val ruleHitCount: Long? = null,
)

/** 导入摘要:总数/导入/跳过(重复或未映射)/失败;单条失败不中断整批,跳过与失败均逐条记 warnings 说明原因 */
data class ScanImportResult(
    var total: Int = 0,
    var imported: Int = 0,
    var skipped: Int = 0,
    var failed: Int = 0,
    val warnings: MutableList<String> = mutableListOf(),
)

/** 导入预检:文件内各数据源的 job 数与本机同名数据源 id,附本机全部数据源供前端映射下拉 */
data class ScanTransferPreview(
    val totalJobs: Int,
    val datasources: List<ScanPreviewDs>,
    val localDatasources: List<ScanPreviewLocalDs>,
)

/** 文件里单个数据源名下的 job 数;matchedDatasourceId 为本机同名数据源 id(无则 null) */
data class ScanPreviewDs(
    val datasourceName: String,
    val jobs: Int,
    val matchedDatasourceId: Long? = null,
)

/** 本机数据源(预检下拉选项) */
data class ScanPreviewLocalDs(
    val id: Long,
    val name: String,
)
