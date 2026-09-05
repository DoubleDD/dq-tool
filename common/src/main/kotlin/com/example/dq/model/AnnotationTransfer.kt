package com.example.dq.model

/** 标记与描述导出文件格式(Jackson 2 序列化;按 数据源名/标记名 对齐,跨实例可导入,不导出任何内部 id) */
data class AnnotationExportFile(
    val app: String,
    val version: Int,
    val exportedAt: String,
    val tags: List<AnnotationTagItem> = emptyList(),
    val tableTags: List<AnnotationTableTagItem> = emptyList(),
    val tableDocs: List<AnnotationTableDocItem> = emptyList(),
    // 同版本内追加的可选字段(默认空列表):旧版本导出的文件无此字段,按空导入
    val tableSystems: List<AnnotationTableSystemItem> = emptyList(),
)

/** USER 标记定义(含描述);导入时按 name 合并;tagType 为后追加的可选字段,老文件缺省按 null 处理(新建缺省 AI、更新保留原值) */
data class AnnotationTagItem(
    val name: String,
    val color: String? = null,
    val description: String? = null,
    val tagType: String? = null,
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

/** 表所属系统(一张表最多归属一个系统) */
data class AnnotationTableSystemItem(
    val datasourceName: String,
    val dbName: String = "",
    val schemaName: String,
    val tableName: String,
    val systemName: String,
)

/** 导入摘要:新建/更新标记数、新增/跳过表标记数、新增或覆盖/跳过描述数与所属系统数(跳过=数据源未映射/未匹配或标记不存在) */
data class AnnotationImportResult(
    var tagsCreated: Int = 0,
    var tagsUpdated: Int = 0,
    var tableTagsAdded: Int = 0,
    var tableTagsSkipped: Int = 0,
    var docsUpserted: Int = 0,
    var docsSkipped: Int = 0,
    var systemsUpserted: Int = 0,
    var systemsSkipped: Int = 0,
)

/** 导入预检:文件里标记数量与表级数据按数据源的分布(导入前让用户把文件数据源映射到本机数据源) */
data class AnnotationImportPreview(
    val tags: Int,
    val datasources: List<AnnotationPreviewDs>,
)

/** 文件里单个数据源名下的表级行数 */
data class AnnotationPreviewDs(
    val datasourceName: String,
    val tableTags: Int,
    val tableDocs: Int,
    // 同版本内追加的可选字段(默认 0):旧版本导出的文件无所属系统数据
    val tableSystems: Int = 0,
)
