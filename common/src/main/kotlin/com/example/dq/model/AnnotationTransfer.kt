package com.example.dq.model

/** 标记与描述导出文件格式(Jackson 2 序列化;按 数据源名/标记名 对齐,跨实例可导入,不导出任何内部 id) */
data class AnnotationExportFile(
    val app: String,
    val version: Int,
    val exportedAt: String,
    val tags: List<AnnotationTagItem> = emptyList(),
    val tableTags: List<AnnotationTableTagItem> = emptyList(),
    val tableDocs: List<AnnotationTableDocItem> = emptyList(),
)

/** USER 标记定义(含描述);导入时按 name 合并 */
data class AnnotationTagItem(
    val name: String,
    val color: String? = null,
    val description: String? = null,
)

/** 表-标记关联:按 数据源名 + db + schema + table 定位表,按标记名定位标记 */
data class AnnotationTableTagItem(
    val datasourceName: String,
    val dbName: String = "",
    val schemaName: String,
    val tableName: String,
    val tagName: String,
)

/** 表描述(AI 生成或手动编辑) */
data class AnnotationTableDocItem(
    val datasourceName: String,
    val dbName: String = "",
    val schemaName: String,
    val tableName: String,
    val description: String,
)

/** 导入摘要:新建/更新标记数、新增/跳过表标记数、新增或覆盖/跳过描述数(跳过=本机无同名数据源或标记) */
data class AnnotationImportResult(
    var tagsCreated: Int = 0,
    var tagsUpdated: Int = 0,
    var tableTagsAdded: Int = 0,
    var tableTagsSkipped: Int = 0,
    var docsUpserted: Int = 0,
    var docsSkipped: Int = 0,
)
