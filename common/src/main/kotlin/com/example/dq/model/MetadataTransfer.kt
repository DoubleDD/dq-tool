package com.example.dq.model

/**
 * 元数据导出文件格式(Jackson 2 序列化;app=dq-tool-metadata)。
 * 把某数据源的结构元数据缓存(库/表/字段/索引/DDL 等)与派生标注数据(表说明/标记/ER 关系/数据目录等)
 * 打包成 JSON,交给连不上业务库的人导入,离线使用 ER 关系、图谱、对象管理等功能。
 * 数据源配置沿用 [DataSourceExportItem](密码 TransferCrypto 固定密钥加密,与数据源导出同一口径)。
 *
 * 各数据段为「行袋」格式:Map 的键与本地 H2 表列名一致(snake_case),值为 JSON 原生类型,
 * 时间列统一为 ISO_LOCAL_DATE_TIME 字符串。兼容策略:
 * - 前向兼容(新文件进旧软件):导入端按目标表实际列过滤行内多余键,未知列直接忽略;
 * - 后向兼容(旧文件进新软件):同版本内新增段/新增键靠可空列与默认值兜底;
 * - 破坏性变更才升 version 并分版本解析(铁律见 docs/wiki/代码约定与安全.md)。
 * 维护约定:新增结构缓存表时需把表名登记到 MetadataTransferService 的段清单,且新列必须可空或有默认值。
 */
data class MetadataExportFile(
    val app: String,
    val version: Int,
    val exportedAt: String,
    val items: List<MetadataExportItem> = emptyList(),
)

/** 单个数据源的元数据快照;各段缺省按空导入(同版本内后追加的段对旧文件兜底) */
data class MetadataExportItem(
    /** 数据源连接配置(密码 TransferCrypto 密文;导入端按名称匹配本机数据源,匹配不到才新建) */
    val datasource: DataSourceExportItem,
    /** meta_database 行(库/schema 清单缓存) */
    val databases: List<Map<String, Any?>> = emptyList(),
    /** meta_table 行(表清单/注释/估算行数/体积) */
    val tables: List<Map<String, Any?>> = emptyList(),
    /** meta_column 行(单表字段明细) */
    val columns: List<Map<String, Any?>> = emptyList(),
    /** meta_index 行(索引列展开) */
    val indexes: List<Map<String, Any?>> = emptyList(),
    /** meta_schema_column 行(整库字段清单 lite 版) */
    val schemaColumns: List<Map<String, Any?>> = emptyList(),
    /** meta_ddl 行(单表建表 DDL 缓存) */
    val ddls: List<Map<String, Any?>> = emptyList(),
    /** meta_column_count 行(schema 字段总数) */
    val columnCounts: List<Map<String, Any?>> = emptyList(),
    /** meta_cache_flag 行(缓存存在标记,随结构缓存一起迁移,导入端据此识别「已缓存但为空」) */
    val cacheFlags: List<Map<String, Any?>> = emptyList(),
    /** schema_stat 行(库概览缓存:表数量/体积) */
    val schemaStats: List<Map<String, Any?>> = emptyList(),
    /** table_doc 行(表说明;含 model 列) */
    val tableDocs: List<Map<String, Any?>> = emptyList(),
    /** schema_doc 行(库级描述) */
    val schemaDocs: List<Map<String, Any?>> = emptyList(),
    /** table_tag 行(tag_name 代 tag_id;仅 USER 标记,系统空表标记由扫描自动维护不迁移) */
    val tableTags: List<Map<String, Any?>> = emptyList(),
    /** table_system 行(表所属系统) */
    val tableSystems: List<Map<String, Any?>> = emptyList(),
    /** manual_collect 行(人工采集收藏,含表注释快照) */
    val manualCollects: List<Map<String, Any?>> = emptyList(),
    /** table_relation 行(ER 关系三态:候选/确认/否决) */
    val relations: List<Map<String, Any?>> = emptyList(),
    /** object_dir 行(source_id/source_parent_id 保留源树结构供导入端 id 重映射) */
    val objectDirs: List<Map<String, Any?>> = emptyList(),
    /** object_table 行(source_id/source_dir_id 指向导出端目录 id) */
    val objectTables: List<Map<String, Any?>> = emptyList(),
    /** object_table_rel 行(source_object_table_id 指向导出端挂载记录 id) */
    val objectTableRels: List<Map<String, Any?>> = emptyList(),
    /** 被本数据源表标记引用的 USER 标记定义(按 name 合并) */
    val tags: List<AnnotationTagItem> = emptyList(),
)

/** 元数据导入预检:文件内各数据源的规模与本机数据源清单 + 连接身份自动匹配结果,供前端做数据源映射 */
data class MetadataImportPreview(
    val items: List<MetadataPreviewItem>,
    val datasources: List<MetadataPreviewLocalDs>,
)

/** 文件里单个数据源;matched* 为按连接身份(type|host|port|username|database,与批量导入去重同口径)自动匹配的本机数据源 */
data class MetadataPreviewItem(
    val name: String,
    val jdbcUrl: String,
    val username: String?,
    val dbType: String,
    val tables: Int,
    val columns: Int,
    val relations: Int,
    val matchedId: Long?,
    val matchedName: String?,
)

/** 本机数据源选项(映射下拉用) */
data class MetadataPreviewLocalDs(val id: Long, val name: String, val jdbcUrl: String)
