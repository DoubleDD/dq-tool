package com.example.dq.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/** 比对目标:数据源 + 库/模式 + 表(库可空,内核归一为空串,与 meta_* 缓存口径一致) */
data class CompareTargetSpec(
    @field:NotNull val datasourceId: Long?,
    val db: String?,
    val schema: String?,
    @field:NotBlank val table: String?,
)

/** 提交比对任务请求:基准表 + 比对主键 + 比对字段(含主键) + 多个比对目标 */
data class CreateCompareJobRequest(
    @field:NotBlank val name: String?,
    @field:NotNull val baseDatasourceId: Long?,
    val baseDb: String?,
    val baseSchema: String?,
    @field:NotBlank val baseTable: String?,
    @field:NotBlank val keyField: String?,
    @field:NotEmpty val fields: List<String>?,
    val targets: List<CompareTargetSpec>?,
)

/** 比对任务列表/详情视图 */
data class CompareJobView(
    val id: Long,
    val name: String,
    val baseDatasourceId: Long,
    val baseDatasourceName: String?,
    val baseDb: String,
    val baseSchema: String?,
    val baseTable: String,
    val keyField: String,
    val fields: List<String>,
    /** RUNNING/DONE/FAILED/CANCELED */
    val status: String,
    val stage: String?,
    val totalUnits: Int,
    val doneUnits: Int,
    /** 进度百分比 0-100(totalUnits 为 0 时记 0) */
    val progressPercent: Int,
    val error: String?,
    val archived: Boolean,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
    /** 耗时毫秒:未开始为 null;运行中=当前时刻-开始时刻 */
    val durationMillis: Long?,
)

/** 比对任务详情:任务字段 + 目标指标列表 */
data class CompareJobDetailView(
    val job: CompareJobView,
    val targets: List<CompareTargetView>,
)

/** 单个比对目标的指标视图 */
data class CompareTargetView(
    val id: Long,
    val datasourceId: Long,
    /** 数据源名称快照 */
    val dsName: String?,
    val db: String,
    val schema: String?,
    val table: String,
    /** PENDING/RUNNING/DONE/FAILED */
    val status: String,
    val baseCount: Int?,
    val matchedCount: Int?,
    val missingCount: Int?,
    val extraCount: Int?,
    val fieldMismatchCount: Int?,
    /** 对象覆盖率 = matched/base_count */
    val coverage: Double?,
    /** 字段一致率 = 1 - mismatch/(matched×比对字段数,分母 0 记 1.0) */
    val fieldConsistency: Double?,
    /** 数据完整率 = 目标侧已比对单元格中非空占比 */
    val completeness: Double?,
    /** 综合评分 = 覆盖率×0.4 + 字段一致率×0.4 + 完整率×0.2 */
    val score: Double?,
    val error: String?,
)

/** 单字段差异:field 字段名 / base 基准值 / value 目标值(目标缺列时 value 为「列缺失」标记) */
data class FieldDiff(
    val field: String,
    val base: String?,
    val value: String?,
)

/** 差异明细分页行;diffs 仅 DIFF 行有值 */
data class CompareDiffRow(
    val id: Long,
    val targetId: Long,
    val objectKey: String?,
    val objectName: String?,
    /** SAME/DIFF/MISSING/EXTRA */
    val diffType: String,
    val diffs: List<FieldDiff>?,
)

/** 差异明细分页结果 */
data class CompareDiffPage(
    val rows: List<CompareDiffRow>,
    val total: Long,
    val page: Int,
    val size: Int,
)

/** 问题字段排行项:按 DIFF 明细中字段出现次数降序 */
data class FieldIssueRank(
    val field: String,
    val count: Long,
)

/** 比对质量报告:目标指标 + 问题字段排行 + 汇总 */
data class CompareReportView(
    val targets: List<CompareTargetView>,
    /** 问题字段排行(前 10,按不一致次数降序) */
    val fieldIssues: List<FieldIssueRank>,
    /** 基准表行数(各目标 base_count 的最大值,无则 0) */
    val baseCount: Int,
    /** 全部目标完全一致(SAME)的对象行数合计 */
    val sameCount: Long,
    /** 全部目标字段不一致(DIFF)的对象行数合计 */
    val diffObjectCount: Long,
    /** 全部目标缺失(MISSING)行数合计 */
    val missingTotal: Long,
    /** 全部目标多余(EXTRA)行数合计 */
    val extraTotal: Long,
    /** 各目标字段一致率的平均值(无完成目标时为 1.0) */
    val avgFieldConsistency: Double,
)
