package com.example.dq.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/** 目标级身份字段人工覆盖:keys = 参与判同的基准字段(必须是任务级 keyFields 的子集);null = 按映射推导 */
data class CompareTargetIdentity(
    val keys: List<String>? = null,
)

/** 比对目标:数据源 + 库/模式 + 表(库可空,内核归一为空串,与 meta_* 缓存口径一致) */
data class CompareTargetSpec(
    @field:NotNull val datasourceId: Long?,
    val db: String?,
    val schema: String?,
    @field:NotBlank val table: String?,
    /**
     * 字段映射(新建向导第四步人工连线确定):键 = 基准表字段名,值 = 目标表列名;
     * 留空/null = 按「字段名忽略大小写」自动匹配(旧行为)。一旦提供必须包含该目标的有效身份字段,
     * 否则无法按身份对齐行;目标侧取值只认映射,未映射到的基准字段记「列缺失」。
     */
    val mapping: Map<String, String>? = null,
    /**
     * 目标级身份字段人工覆盖(可选):该目标只用其中部分已连线身份字段判同时给出;
     * 留空/null = 按「任务级 keyFields ∩ 映射键」推导,推导为空提交校验拦下
     */
    val identity: CompareTargetIdentity? = null,
)

/** 提交比对任务请求:基准表 + 比对主键 + 比对字段(含主键) + 多个比对目标 */
data class CreateCompareJobRequest(
    @field:NotBlank val name: String?,
    @field:NotNull val baseDatasourceId: Long?,
    val baseDb: String?,
    val baseSchema: String?,
    @field:NotBlank val baseTable: String?,
    @field:NotBlank val keyField: String?,
    /**
     * 任务级身份字段(向导第一步多选,基准字段名):语义为「默认身份/身份字段并集」,
     * 各目标的有效身份 = 人工覆盖 ?? 「keyFields ∩ 该目标映射中已连线的基准字段」。
     * 留空/null = 仅 [keyField] 单字段(旧行为);给出时全部必须属于 fields
     */
    val keyFields: List<String>? = null,
    @field:NotEmpty val fields: List<String>?,
    val targets: List<CompareTargetSpec>?,
    /** 对象名称(显示名)字段:可选,必须属于 fields;留空 = 自动取比对字段中第一个文本型非主键字段 */
    val displayField: String? = null,
    /**
     * 对象对齐(匹配)逻辑,一任务一套。界面提供两种:EXACT(编码+名称)/ CODE_NAME_LLM(先编码后名称+
     * 大模型归一化);CODE_THEN_NAME 为旧版选项、界面不再提供,存量任务与直接调用仍兼容。
     * 留空 = EXACT;非 EXACT 要求显式给出对象名称字段。
     */
    val matchMode: String? = null,
    /**
     * 对比模式:ROW(仅行级对比,仅身份字段)/ COLUMN(行级+列级对比,全量信息字段 + 大模型预生成映射)。
     * 留空 = ROW(与既有行为一致);未知值报 400。
     */
    val compareMode: String? = null,
)

/** 对比模式(compare_job.compare_mode):决定新建向导默认比对多少字段、字段映射由谁生成;执行引擎同一套 */
enum class CompareMode(val value: String, val label: String) {
    /** 仅行级对比:只比对对象身份字段,映射人工连线 */
    ROW("ROW", "行级对比"),
    /** 行级+列级对比:身份对齐(行级)基础上逐字段比对全部信息字段,映射由大模型预生成、人工审核 */
    COLUMN("COLUMN", "行级+列级对比"),
    ;

    companion object {
        /** 请求值归一:空 = [ROW](与既有行为一致);未知值报 [IllegalArgumentException] */
        fun normalize(raw: String?): CompareMode {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return ROW
            return entries.firstOrNull { it.value.equals(v, ignoreCase = true) }
                ?: throw IllegalArgumentException("非法对比模式: $raw")
        }
    }
}

/** 待处理原因(compare_job.pending_reason,仅 PENDING 状态有意义;任务进入 RUNNING 时清空) */
enum class PendingReason(val value: String, val label: String) {
    /** 涉及异常数据源(连不上/缺用户名口令未实测):先修数据源再确认 */
    DS_ERROR("DS_ERROR", "数据源异常"),
    /**
     * 映射推导中:任务已落库,字段映射(含待新建数据源连接实测)正在后台推导,审核/编辑/直启暂不可用;
     * 推导完成转 MAPPING_REVIEW,实测失败转 DS_ERROR,重启残留由 recoverUnfinished 转 MAPPING_REVIEW
     */
    MAPPING_RUNNING("MAPPING_RUNNING", "映射推导中"),
    /** 映射待审核:字段映射已预生成,一律要人工审核后才能开始比对 */
    MAPPING_REVIEW("MAPPING_REVIEW", "映射待审核"),
    /** 导入异常:基准行校验失败(表不存在/缺身份字段等),需「编辑」修正 */
    IMPORT_ERROR("IMPORT_ERROR", "导入异常"),
    ;

    companion object {
        fun fromValue(raw: String?): PendingReason? =
            raw?.let { v -> entries.firstOrNull { it.value == v } }
    }
}

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
    /** 任务级身份字段(基准表实际列名,有序);老任务(库中 key_fields_json 为空)退化为 [keyField] 单元素 */
    val keyFields: List<String>,
    /** 对象名称(显示名)字段(基准表实际列名);null = 该任务无可用显示字段,object_name 落空串 */
    val displayField: String?,
    /**
     * 对象对齐(匹配)逻辑:EXACT(code+name 都相等)/ CODE_THEN_NAME(先 code 后 name)/
     * CODE_NAME_LLM(1、2 没配上的残余再交大模型归一化配)。老任务为 null,等价于「只按 code 对齐」。
     */
    val matchMode: String?,
    /** 对比模式:ROW(仅行级)/ COLUMN(行级+列级);老任务为 null,按 ROW 解读 */
    val compareMode: String?,
    val fields: List<String>,
    /** PENDING(待处理)/RUNNING/DONE/FAILED/CANCELED */
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
    /** 待处理原因(仅 PENDING):DS_ERROR/MAPPING_RUNNING/MAPPING_REVIEW/IMPORT_ERROR;其余状态为 null */
    val pendingReason: String? = null,
    /** 所属水利对象类别名称(批量导入时取自表格基准行,自由文本不校验,可空) */
    val objectCategory: String? = null,
    /** 来源导入批次 id(向导手工建的为 null) */
    val importId: Long? = null,
    /** 来源导入文件名(按 import_id 左联 compare_import 取;手工建为 null) */
    val importFileName: String? = null,
    /** 「打开文件」可点口径(V65):已导出且 SHA-256 与库中记录一致;「打开文件夹」不受此限制 */
    val exportFileOk: Boolean = false,
)

/** 比对任务详情:任务字段 + 目标指标列表 */
data class CompareJobDetailView(
    val job: CompareJobView,
    val targets: List<CompareTargetView>,
)

/**
 * 后台任务中心轮询用的轻量活动任务行(仅 RUNNING,跨全部库):
 * 不含比对字段清单/目标明细/数据源名解析(避免 1s 轮询重负),目标进度用 targetTotal/targetDone 计数表达
 */
data class CompareJobActiveView(
    val id: Long,
    val name: String,
    val baseDb: String,
    val baseSchema: String?,
    val baseTable: String,
    /** 对比模式:ROW(仅行级)/ COLUMN(行级+列级);老任务为 null,按 ROW 解读 */
    val compareMode: String?,
    /** RUNNING */
    val status: String,
    /** 阶段描述(人类可读,如「比对 xx 完成」) */
    val stage: String?,
    val totalUnits: Int,
    val doneUnits: Int,
    /** 进度百分比 0-100(totalUnits 为 0 时记 0) */
    val progressPercent: Int,
    val error: String?,
    val startedAt: LocalDateTime?,
    /** 目标总数 / 已出终态(DONE+FAILED)目标数 */
    val targetTotal: Int,
    val targetDone: Int,
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
    /** 目标级身份字段人工覆盖(基准表实际列名);null = 按「任务级 keyFields ∩ 映射键」推导 */
    val identityKeys: List<String>? = null,
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

/** 任务列表分页结果 */
data class CompareJobPage(
    val rows: List<CompareJobView>,
    val total: Long,
    val page: Int,
    val size: Int,
)

/** 问题字段排行项:按 DIFF 明细中字段出现次数降序;comment 为基准表字段注释(中文字段名,无注释为 null) */
data class FieldIssueRank(
    val field: String,
    val count: Long,
    val comment: String? = null,
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

/** 列级对比·字段映射预生成请求:基准表 + 需映射的比对字段 + 主键 + 目标表清单(mapping 字段忽略) */
data class MappingSuggestRequest(
    @field:NotNull val baseDatasourceId: Long?,
    val baseDb: String?,
    val baseSchema: String?,
    @field:NotBlank val baseTable: String?,
    /** 需映射的基准字段(= 比对字段);留空 = 基准表全部字段 */
    val fields: List<String>?,
    @field:NotBlank val keyField: String?,
    val targets: List<CompareTargetSpec>?,
)

/** 字段映射预生成:单目标结果。mapping 为「基准字段名 → 目标列名」建议(含主键兜底),
 *  null = 该目标未产出建议(看 note);note 为补充说明(如大模型未配置/调用失败/建议人工复核) */
data class MappingSuggestTargetView(
    val datasourceId: Long,
    val db: String?,
    val schema: String?,
    val table: String,
    val mapping: Map<String, String>?,
    val note: String?,
)

/** 字段映射预生成结果:与请求 targets 同序 */
data class MappingSuggestView(
    val targets: List<MappingSuggestTargetView>,
)

/**
 * 比对任务批量导入批次视图(compare_import):原件落盘留档 + 数据源映射报告 + 建出的任务清单。
 * dsReport 为逐数据源一行:key(地址+端口+库)/名称/地址/端口/库名/action(MATCHED 已匹配 /
 * CREATE 待新建 / CREATED 已建档 / ERROR 异常 / NOTE 提示)/datasourceId/error(说明)。
 */
data class CompareImportView(
    val id: Long,
    /** 原始文件名(不变) */
    val fileName: String,
    val fileSize: Long?,
    /** DS_REVIEW 待确认数据源 / BUILDING 建任务中 / DONE / FAILED */
    val status: String,
    val dsReport: List<Map<String, Any?>>,
    /** sheet 数(一 sheet 一任务) */
    val taskCount: Int,
    /** 建出的比对任务 id 清单 */
    val jobIds: List<Long>,
    val error: String?,
    val createdAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)
