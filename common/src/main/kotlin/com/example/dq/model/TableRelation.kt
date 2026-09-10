package com.example.dq.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/** 关系状态:CANDIDATE 候选(程序推导未确认)/ CONFIRMED 确认(人工核准的权威关系)/ REJECTED 否决(误报,再推导跳过) */
enum class RelationStatus { CANDIDATE, CONFIRMED, REJECTED }

/** 关系来源:NAME_MATCH 字段名匹配 / SEMANTIC 语义匹配(M2)/ MANUAL 人工补充 */
enum class RelationSource { NAME_MATCH, SEMANTIC, MANUAL }

/** 基数:ONE_TO_ONE / ONE_TO_MANY / SUSPECT_MANY_TO_MANY(两端均重复,疑似多对多待人工裁决) */
enum class RelationCardinality { ONE_TO_ONE, ONE_TO_MANY, SUSPECT_MANY_TO_MANY }

/** 推导任务阶段:NAME_MATCH 名字匹配 / SEMANTIC_TABLE 语义表级粗筛 / SEMANTIC_COLUMN 语义字段级精判 / VERIFY 值交集+基数验证 */
enum class RelationInferStage { NAME_MATCH, SEMANTIC_TABLE, SEMANTIC_COLUMN, VERIFY }

/**
 * 表间关系(table_relation 一行,方向化存储):
 * one 侧 = 唯一方(一的一端;ONE_TO_ONE/SUSPECT_MANY_TO_MANY 时按 (表名,字段名) 字典序小的一侧,方向归一化);
 * many 侧 = 重复方(多的一端)。
 */
data class TableRelation(
    val id: Long,
    val datasourceId: Long,
    val dbName: String,
    val schemaName: String,
    val oneTable: String,
    val oneColumn: String,
    val manyTable: String,
    val manyColumn: String,
    val cardinality: String,
    val status: String,
    val source: String,
    val confidence: String?,
    /** 值交集率(0~1),可空 */
    val overlapRatio: Double?,
    val remark: String?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
)

/** 锚点字段(归一化后的视图模型):name 锚点表字段名;aliases 用户手填的、其他表中引用该字段的常见映射字段名 */
data class AnchorField(
    val name: String,
    val aliases: List<String> = emptyList(),
)

/** 推导任务视图(relation_infer_job 一行);fields 由 anchor_columns 的 JSON 串解析(旧逗号分隔格式兜底) */
data class RelationInferJob(
    val id: Long,
    val datasourceId: Long,
    val dbName: String,
    val schemaName: String,
    val anchorTable: String,
    val fields: List<AnchorField>,
    val useSemantic: Boolean,
    /** RUNNING/DONE/FAILED */
    val status: String,
    val stage: String?,
    val totalSteps: Int,
    val doneSteps: Int,
    val foundCount: Int,
    val error: String?,
    val createdAt: LocalDateTime?,
    val startedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

/** 锚点字段请求项:name 锚点表字段名;aliases 用户手填的其他表中的映射字段名(可多个,名字匹配通道一并搜索) */
data class AnchorFieldRequest(
    @field:NotBlank val name: String?,
    val aliases: List<String> = emptyList(),
)

/** 提交推导请求;dbName 可空,内核归一为空串(与 manual_collect 口径一致) */
data class RelationInferRequest(
    @field:NotNull val datasourceId: Long?,
    val dbName: String?,
    @field:NotBlank val schemaName: String?,
    @field:NotBlank val table: String?,
    @field:NotEmpty val fields: List<AnchorFieldRequest>?,
    val useSemantic: Boolean = false,
    /** 仅映射名匹配:true 时搜索名集合只含 aliases(锚点字段本名不参与,避免 id 等通用名全库命中;缺省 false 向后兼容) */
    val aliasOnly: Boolean = false,
)

/**
 * 手动补充关系请求:方向由用户指定(one 侧 = 唯一方);
 * cardinality 为 ONE_TO_ONE/SUSPECT_MANY_TO_MANY 时方向无语义,服务端按字典序归一化
 */
data class RelationManualRequest(
    @field:NotNull val datasourceId: Long?,
    val dbName: String?,
    @field:NotBlank val schemaName: String?,
    @field:NotBlank val oneTable: String?,
    @field:NotBlank val oneColumn: String?,
    @field:NotBlank val manyTable: String?,
    @field:NotBlank val manyColumn: String?,
    @field:NotBlank val cardinality: String?,
    val remark: String?,
)

/** 手动补充关系结果:existing=true 表示命中唯一键的已存在关系(转 CONFIRMED 返回原 id,未新插) */
data class ManualRelationResult(val id: Long, val existing: Boolean)

/** ER 图节点(一张表);comment 取自 meta_table 缓存 */
data class RelationGraphNode(
    val name: String,
    val comment: String?,
)

/** ER 图数据:节点 = 库内表(+关系涉及但缓存缺失的表);边 = 关系(不含 REJECTED) */
data class RelationGraphView(
    val nodes: List<RelationGraphNode>,
    val edges: List<TableRelation>,
)
