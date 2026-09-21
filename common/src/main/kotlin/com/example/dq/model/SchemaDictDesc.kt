package com.example.dq.model

import jakarta.validation.constraints.NotBlank

/**
 * 库列表「批量设置描述」请求:指定字典表(库名 -> 描述映射),按库名精准匹配回写 schema_doc。
 *
 * @property db               字典表所在库(多库方言必填;单库方言忽略,传 null/空)
 * @property schema           字典表所在 schema
 * @property table            字典表名
 * @property nameField        库名字段
 * @property descField        描述字段
 * @property dictDatasourceId 字典表所在数据源(可与目标数据源不同;空 = 目标数据源自身)
 */
data class SchemaDictDescRequest(
    val db: String?,
    @field:NotBlank val schema: String?,
    @field:NotBlank val table: String?,
    @field:NotBlank val nameField: String?,
    @field:NotBlank val descField: String?,
    val dictDatasourceId: Long?,
)

/**
 * 批量设置描述结果统计。
 *
 * @property totalRows      字典表总行数
 * @property matched        命中库数(精准匹配成功并写入描述的库名个数)
 * @property skipped        跳过的空行数(库名或描述 trim 后为空)
 * @property unmatchedTotal 未命中库名总数
 * @property unmatched      未命中库名样例(截断前 50 个,供人工核对)
 */
data class SchemaDictApplyResult(
    val totalRows: Int,
    val matched: Int,
    val skipped: Int,
    val unmatchedTotal: Int,
    val unmatched: List<String>,
)
