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
    /**
     * 字段映射(新建向导第四步人工连线确定):键 = 基准表字段名,值 = 目标表列名;
     * 留空/null = 按「字段名忽略大小写」自动匹配(旧行为)。一旦提供必须包含比对主键,
     * 否则无法按主键对齐行;目标侧取值只认映射,未映射到的基准字段记「列缺失」。
     */
    val mapping: Map<String, String>? = null,
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
    /** 对象名称(显示名)字段:可选,必须属于 fields;留空 = 自动取比对字段中第一个文本型非主键字段 */
    val displayField: String? = null,
    /**
     * 对象对齐(匹配)逻辑,一任务一套:EXACT / CODE_THEN_NAME / CODE_NAME_LLM。
     * 留空 = EXACT(编码 + 名称都相等才算同一对象);匹配逻辑 2/3 要求显式给出对象名称字段。
     */
    val matchMode: String? = null,
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
    /** 对象名称(显示名)字段(基准表实际列名);null = 该任务无可用显示字段,object_name 落空串 */
    val displayField: String?,
    /**
     * 对象对齐(匹配)逻辑:EXACT(code+name 都相等)/ CODE_THEN_NAME(先 code 后 name)/
     * CODE_NAME_LLM(1、2 没配上的残余再交大模型归一化配)。老任务为 null,等价于「只按 code 对齐」。
     */
    val matchMode: String?,
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
    /** 目标侧实际读到的总行数(含多余行);老任务(NULL)表示未采集 */
    val targetCount: Int?,
    val matchedCount: Int?,
    /** 编码(code)对齐上的对象数;老任务 NULL = 未采集(此时等于 matchedCount) */
    val codeMatchedCount: Int?,
    /** 编码没配上、靠对象名称补配上的对象数(匹配逻辑 2/3);老任务 NULL = 0 */
    val nameMatchedCount: Int?,
    /** 名称也没配上、由大模型归一化补配上的对象数(匹配逻辑 3);老任务 NULL = 0 */
    val aiMatchedCount: Int?,
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
    /** 人工字段映射(基准表实际列名 → 目标表实际列名);null = 未指定,按字段名自动匹配 */
    val mapping: Map<String, String>? = null,
)

/**
 * 单字段差异:field 字段名 / base 基准值 / value 目标值(目标缺列时 value 为「列缺失」标记)。
 *
 * [matched] 为该字段是否一致:**新数据显式记录 true/false**;为 null 表示旧数据/中间版本
 * (未记录该标志,按「value 非空即不一致」的旧契约解读)。有了它之后,一致字段也能保存
 * 目标真实值(value 不为 null),界面/导出才能「每格显示真实值」而不丢失「是否一致」的信息。
 */
data class FieldDiff(
    val field: String,
    val base: String?,
    val value: String?,
    val matched: Boolean? = null,
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
    /** 该差异行的对齐来源:CODE / NAME / LLM(老数据/未记录为 null);仅展示用,不影响指标口径 */
    val matchBy: String? = null,
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
