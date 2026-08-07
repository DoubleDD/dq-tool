package com.example.dq.model

/** 标记的库维度分布行;行数/列数只累计最近一次 DONE 扫描快照,该库无任何已扫描表时为 null(前端显示「—」) */
data class TagSchemaStat(
    val datasourceId: Long,
    val datasourceName: String,
    val dbName: String,
    val schemaName: String,
    val tableCount: Int,
    val totalRows: Long?,
    val totalColumns: Long?,
)

/** 标记维度统计:coveredTables 为全局打标表数(跨所有标记去重),与各标记表数之和可能不等 */
data class TagStats(
    val tag: Tag,
    val totalTables: Int,
    val totalRows: Long?,
    val totalColumns: Long?,
    val coveredTables: Int,
    val schemas: List<TagSchemaStat>,
)

/** 库维度标记计数(schema-tag-stats 接口):一个库下各标记的打标表数 */
data class SchemaTagStat(
    val schemaName: String,
    val tags: List<SchemaTagCount>,
)

/** 单个标记在某库下的打标表数 */
data class SchemaTagCount(
    val tagId: Long,
    val tagName: String,
    val color: String,
    val kind: TagKind,
    val count: Int,
)
