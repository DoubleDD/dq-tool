package com.example.dq.service

import com.example.dq.model.AnnotationTagItem
import com.example.dq.model.DataSourceExportItem
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ImportFailure
import com.example.dq.model.ImportResult
import com.example.dq.model.MetadataExportFile
import com.example.dq.model.MetadataExportItem
import com.example.dq.model.MetadataImportPreview
import com.example.dq.model.MetadataPreviewItem
import com.example.dq.model.MetadataPreviewLocalDs
import com.example.dq.model.DbType
import com.example.dq.model.TagKind
import com.example.dq.model.TagSource
import com.example.dq.model.TagType
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ManualCollectRepository
import com.example.dq.repository.MetaSyncRepository
import com.example.dq.repository.ObjectCatalogRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TableRelationRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import com.example.dq.util.TransferCrypto
import com.example.dq.util.TransferJson
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.sql.Types
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 元数据缓存的导出/导入:把数据源的结构元数据缓存(9 张缓存表,与「刷新」同口径)连同派生标注数据
 * (表说明/库描述/表标记/所属系统/人工采集/ER 关系/数据目录)打包成 JSON,
 * 现场人员刷新元数据后导出,交给连不上业务库的人导入,离线使用 ER 关系、图谱、对象管理等功能。
 *
 * 格式与兼容策略见 [MetadataExportFile](行袋格式:键=本地表列名,时间列 ISO 字符串;
 * 导入端按目标表实际列过滤行内多余键,前向兼容;新增缓存表需登记 [STRUCTURE_TABLES]/段清单,新列必须可空或有默认值)。
 *
 * 导入语义:
 * - 数据源定位:**显式映射优先**(dsMapping:文件数据源名 → 本机数据源 id),未给映射按**连接身份**
 *   (type|host小写|port|username|database,与表格批量导入去重同口径,见 SampleTableExcelParser.dsKey)自动匹配,
 *   都没有才按文件配置新建(重名自动加「 (2)」后缀;匹配不是按名称——数据源改名不影响身份);
 * - 结构缓存 = 整粒度替换(与元数据「刷新」的 delete+insert 同口径,属缓存语义);
 * - 派生数据 = 按自然键合并(幂等,不覆盖本机已有的 ER 否决/确认决策与手工标注,对齐 AnnotationTransferService 口径);
 * - object_dir/object_table 带代理主键,导出保留源 id,导入按 (parent_id, name) 逐级匹配/新建做 id 重映射;
 * - 数据源正在跑元数据同步时拒绝导入该条(避免与同步的整粒度覆盖互相踩踏)。
 * 不导出:scan_*(扫描记录有独立 Transfer)、relation_infer_job/meta_sync_*(纯历史任务)。
 */
class MetadataTransferService(
    private val jdbc: Jdbc,
    private val dataSourceRepo: DataSourceRepository,
    private val crypto: CryptoUtil,
    private val dataSourceService: DataSourceService,
    private val metaSyncRepo: MetaSyncRepository,
    private val tagRepo: TagRepository,
    private val tableDocRepo: TableDocRepository,
    private val schemaDocRepo: SchemaDocRepository,
    private val tableSystemRepo: TableSystemRepository,
    private val manualCollectRepo: ManualCollectRepository,
    private val tableRelationRepo: TableRelationRepository,
    private val objectCatalogRepo: ObjectCatalogRepository,
) {

    private val objectMapper = jacksonObjectMapper()

    private val log = LoggerFactory.getLogger(MetadataTransferService::class.java)

    // ---------- 导出 ----------

    /** 导出指定数据源的元数据快照为 JSON 文件;数据源不存在抛 IllegalArgumentException */
    fun export(ids: List<Long>, out: OutputStream) {
        val items = ids.map { exportOne(it) }
        val file = MetadataExportFile(
            app = APP_MARKER,
            version = VERSION,
            exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            items = items,
        )
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, file)
    }

    private fun exportOne(id: Long): MetadataExportItem {
        val c = dataSourceRepo.findById(id)
            ?: throw IllegalArgumentException("数据源不存在: $id")
        val dsItem = DataSourceExportItem(
            name = c.name ?: "",
            jdbcUrl = c.jdbcUrl ?: "",
            username = c.username,
            // 与数据源导出同一口径:实例密钥解密后用导出文件固定密钥重加密
            passwordEnc = TransferCrypto.encrypt(crypto.decrypt(c.password)),
            rowThreshold = c.rowThreshold,
            sizeThresholdBytes = c.sizeThresholdBytes,
            schemaFilter = c.schemaFilter,
            groupName = c.groupName,
            sshEnabled = c.sshEnabled,
            sshHost = c.sshHost,
            sshPort = c.sshPort,
            sshUsername = c.sshUsername,
            sshAuthMethod = c.sshAuthMethod,
            sshPasswordEnc = TransferCrypto.encrypt(crypto.decrypt(c.sshPassword)),
            sshPrivateKeyEnc = TransferCrypto.encrypt(crypto.decrypt(c.sshPrivateKey)),
            sshPassphraseEnc = TransferCrypto.encrypt(crypto.decrypt(c.sshPassphrase)),
        )
        return MetadataExportItem(
            datasource = dsItem,
            databases = rows("SELECT * FROM meta_database WHERE datasource_id=?", id, dropId = true),
            tables = rows("SELECT * FROM meta_table WHERE datasource_id=?", id, dropId = true),
            columns = rows("SELECT * FROM meta_column WHERE datasource_id=?", id, dropId = true),
            indexes = rows("SELECT * FROM meta_index WHERE datasource_id=?", id, dropId = true),
            schemaColumns = rows("SELECT * FROM meta_schema_column WHERE datasource_id=?", id, dropId = true),
            ddls = rows("SELECT * FROM meta_ddl WHERE datasource_id=?", id, dropId = true),
            columnCounts = rows("SELECT * FROM meta_column_count WHERE datasource_id=?", id, dropId = true),
            cacheFlags = rows("SELECT * FROM meta_cache_flag WHERE datasource_id=?", id, dropId = true),
            schemaStats = rows("SELECT * FROM schema_stat WHERE datasource_id=?", id, dropId = true),
            tableDocs = rows("SELECT db_name, schema_name, table_name, description, model FROM table_doc WHERE datasource_id=?", id),
            schemaDocs = rows("SELECT db_name, schema_name, description FROM schema_doc WHERE datasource_id=?", id),
            // 仅 USER 标记:系统空表标记由扫描联动自动维护,不迁移(与 AnnotationTransfer 同口径)
            tableTags = rows(
                "SELECT tt.db_name, tt.schema_name, tt.table_name, tt.source, d.name AS tag_name " +
                        "FROM table_tag tt JOIN tag_def d ON d.id = tt.tag_id " +
                        "WHERE tt.datasource_id=? AND d.kind='USER' ORDER BY tt.table_name, d.name", id),
            tableSystems = rows("SELECT db_name, schema_name, table_name, system_name FROM table_system WHERE datasource_id=?", id),
            manualCollects = rows("SELECT db_name, schema_name, table_name, table_comment FROM manual_collect WHERE datasource_id=?", id),
            relations = rows(
                "SELECT db_name, schema_name, one_table, one_column, many_table, many_column, " +
                        "cardinality, status, source, confidence, overlap_ratio, remark FROM table_relation WHERE datasource_id=?", id),
            objectDirs = rows(
                "SELECT id AS source_id, parent_id AS source_parent_id, name FROM object_dir WHERE datasource_id=? ORDER BY id", id),
            objectTables = rows(
                "SELECT t.id AS source_id, t.dir_id AS source_dir_id, t.db_name, t.schema_name, t.table_name, t.remark, t.rel_kind " +
                        "FROM object_table t JOIN object_dir d ON d.id = t.dir_id WHERE d.datasource_id=? ORDER BY t.id", id),
            objectTableRels = rows(
                "SELECT r.object_table_id AS source_object_table_id, r.db_name, r.schema_name, r.table_name, r.remark, r.rel_kind " +
                        "FROM object_table_rel r JOIN object_table t ON t.id = r.object_table_id " +
                        "JOIN object_dir d ON d.id = t.dir_id WHERE d.datasource_id=? ORDER BY r.id", id),
            tags = tagRepo.listUserTableTagRows()
                .filter { it.datasourceId == id }
                .map { it.tagName }
                .distinct()
                .mapNotNull { tagRepo.findByName(it) }
                .filter { it.kind == TagKind.USER }
                .map { AnnotationTagItem(it.name, it.color, it.description, it.tagType.name) },
        )
    }

    /** 行查询:列名转小写,时间列转 ISO 字符串,CLOB 读全文;dropId 时剔除自增主键(文件不带内部 id) */
    private fun rows(sql: String, vararg args: Any?, dropId: Boolean = false): List<Map<String, Any?>> =
        jdbc.query(sql, *args) { rs ->
            val md = rs.metaData
            val row = LinkedHashMap<String, Any?>()
            for (i in 1..md.columnCount) {
                val value = when (val v = rs.getObject(i)) {
                    is java.sql.Clob -> v.characterStream.use { it.readText() }
                    else -> v
                }
                row[md.getColumnLabel(i).lowercase()] = normalize(value)
            }
            if (dropId) row.remove("id")
            row
        }

    private fun normalize(v: Any?): Any? = when (v) {
        null -> null
        is java.sql.Timestamp -> v.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is LocalDateTime -> v.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        else -> v
    }

    // ---------- 导入 ----------

    /** 导入预检:解析文件,返回各数据源的规模与本机数据源清单,并按连接身份自动匹配(供前端预填映射,无匹配默认新建) */
    fun preview(input: InputStream): MetadataImportPreview {
        val file = parseFile(input)
        if (file.items.isEmpty()) {
            throw IllegalArgumentException("导出文件中没有数据源")
        }
        val local = dataSourceRepo.findAll()
        val localByKey = local.mapNotNull { ds ->
            SampleTableExcelParser.keyOfExisting(ds)?.let { it to ds }
        }.toMap()
        return MetadataImportPreview(
            items = file.items.map { item ->
                val matched = localByKey[SampleTableExcelParser.keyOfJdbcUrl(item.datasource.jdbcUrl, item.datasource.username)]
                MetadataPreviewItem(
                    name = item.datasource.name,
                    jdbcUrl = item.datasource.jdbcUrl,
                    username = item.datasource.username,
                    dbType = runCatching { DbType.fromJdbcUrl(item.datasource.jdbcUrl).name }.getOrElse { "" },
                    tables = item.tables.size,
                    columns = item.columns.size,
                    relations = item.relations.size,
                    matchedId = matched?.id,
                    matchedName = matched?.name,
                )
            },
            datasources = local.map { MetadataPreviewLocalDs(it.id!!, it.name ?: "", it.jdbcUrl ?: "") },
        )
    }

    /**
     * 导入元数据导出文件;格式标识不对抛 IllegalArgumentException(Web 层映射 400);单条失败收集不中断整批。
     * dsMapping:文件数据源名 → 本机数据源 id——缺失=按连接身份自动匹配,0=强制新建,>0=映射到指定本机数据源
     */
    @JvmOverloads
    fun importJson(input: InputStream, dsMapping: Map<String, Long> = emptyMap()): ImportResult {
        val file = parseFile(input)
        if (file.items.isEmpty()) {
            throw IllegalArgumentException("导出文件中没有数据源")
        }
        val result = ImportResult(total = file.items.size)
        for (item in file.items) {
            val name = item.datasource.name.trim()
            if (name.isEmpty()) {
                result.failed.add(ImportFailure("(未命名)", "缺少数据源名称"))
                continue
            }
            try {
                importOne(item, name, dsMapping, result)
            } catch (e: Exception) {
                // 错误日志纪律:业务 catch 不允许静默吞掉——失败条目除回传导入结果外,必须带堆栈落 error 日志
                log.error("元数据导入失败:数据源「{}」", name, e)
                result.failed.add(ImportFailure(name, e.message ?: "导入失败"))
            }
        }
        return result
    }

    private fun importOne(item: MetadataExportItem, name: String, dsMapping: Map<String, Long>, result: ImportResult) {
        // 1. 数据源定位:显式映射优先(0=强制新建)→ 连接身份自动匹配 → 按文件配置新建(重名自动加后缀)
        val local = dataSourceRepo.findAll()
        val explicitRaw = dsMapping[name]
        val explicitId = explicitRaw?.takeIf { it > 0 }
        val fileKey = SampleTableExcelParser.keyOfJdbcUrl(item.datasource.jdbcUrl, item.datasource.username)
        val matched = local.firstOrNull { SampleTableExcelParser.keyOfExisting(it) == fileKey && fileKey != null }
        val dsId: Long
        if (explicitId != null || (explicitRaw == null && matched != null)) {
            dsId = explicitId ?: matched!!.id!!
            if (explicitId != null && local.none { it.id == explicitId }) {
                throw IllegalArgumentException("映射的本机数据源(id=$explicitId)不存在")
            }
            // 同步进行中禁止导入:避免与同步的整粒度覆盖互相踩踏
            if (metaSyncRepo.hasRunningForDatasource(dsId)) {
                throw IllegalArgumentException("该数据源正在同步元数据,请稍后再导入")
            }
        } else {
            val plain = TransferCrypto.decrypt(item.datasource.passwordEnc)
            val usedNames = local.mapNotNull { it.name }.toMutableSet()
            usedNames.addAll(result.renamed.values)
            var finalName = name
            var seq = 2
            while (finalName in usedNames) {
                finalName = "$name (${seq++})"
            }
            if (finalName != name) {
                result.renamed[name] = finalName
            }
            dsId = dataSourceService.create(
                DataSourceRequest(finalName, item.datasource.jdbcUrl, item.datasource.username, plain,
                    item.datasource.rowThreshold, item.datasource.sizeThresholdBytes,
                    schemaFilter = item.datasource.schemaFilter, groupName = item.datasource.groupName,
                    sshEnabled = item.datasource.sshEnabled, sshHost = item.datasource.sshHost,
                    sshPort = item.datasource.sshPort, sshUsername = item.datasource.sshUsername,
                    sshAuthMethod = item.datasource.sshAuthMethod,
                    sshPassword = TransferCrypto.decrypt(item.datasource.sshPasswordEnc),
                    sshPrivateKey = TransferCrypto.decrypt(item.datasource.sshPrivateKeyEnc),
                    sshPassphrase = TransferCrypto.decrypt(item.datasource.sshPassphraseEnc)))
            result.warnings.add("「$finalName」未匹配到本机数据源,已按导出配置新建数据源")
            if (plain.isNullOrEmpty()) {
                result.warnings.add("「$finalName」导入成功但未包含密码,请编辑数据源补充密码")
            }
        }

        // 2. 结构缓存:整粒度替换(与「刷新」同口径);表级事务,行内多余键按目标表列过滤忽略
        val skipped = intArrayOf(0)
        replaceStructure(dsId, "meta_database", item.databases, skipped)
        replaceStructure(dsId, "meta_table", item.tables, skipped)
        replaceStructure(dsId, "meta_column", item.columns, skipped)
        replaceStructure(dsId, "meta_index", item.indexes, skipped)
        replaceStructure(dsId, "meta_schema_column", item.schemaColumns, skipped)
        replaceStructure(dsId, "meta_ddl", item.ddls, skipped)
        replaceStructure(dsId, "meta_column_count", item.columnCounts, skipped)
        replaceStructure(dsId, "meta_cache_flag", item.cacheFlags, skipped)
        replaceStructure(dsId, "schema_stat", item.schemaStats, skipped)

        // 3. 派生数据:按自然键合并(幂等,不覆盖本机已有决策)
        mergeTags(item.tags)
        mergeTableTags(item.tableTags, dsId, skipped)
        mergeTableDocs(item.tableDocs, dsId)
        mergeSchemaDocs(item.schemaDocs, dsId)
        mergeTableSystems(item.tableSystems, dsId, skipped)
        mergeManualCollects(item.manualCollects, dsId)
        mergeRelations(item.relations, dsId, skipped)
        mergeObjectCatalog(item, dsId, name, result, skipped)
        if (skipped[0] > 0) {
            result.warnings.add("「$name」有 ${skipped[0]} 行数据缺少关键字段或所属目录缺失,已跳过")
            log.warn("元数据导入:数据源「{}」跳过 {} 行(缺少关键字段或所属目录缺失)", name, skipped[0])
        }
        result.imported.add("$name(${item.tables.size} 表/${item.columns.size} 字段/${item.relations.size} 关系)")
    }

    /** 结构缓存表整粒度替换:先按数据源清空再批量插入;行键与目标表列取交集,datasource_id 强制写目标 id;
     * 缺 NOT NULL 无默认值关键列的坏行在插入前过滤(避免一条坏行让整个表回滚),返回跳过的行数 */
    private fun replaceStructure(dsId: Long, table: String, data: List<Map<String, Any?>>, skipped: IntArray) {
        val cols = targetColumns(table).filter { it.name != "id" }
        if (cols.isEmpty()) return
        try {
            fun missingCols(row: Map<String, Any?>): List<String> =
                cols.filter { it.name != "datasource_id" && row[it.name] == null && !it.nullable && !it.hasDefault }
                    .map { it.name }

            val insertable = data.filter { missingCols(it).isEmpty() }
            if (insertable.size < data.size) {
                skipped[0] += data.size - insertable.size
                // 跳过行必须留痕:总量 + 首行缺失的必填列,便于回溯文件问题
                log.warn("结构缓存表 {} 跳过 {}/{} 行(缺必填列,首行缺: {})",
                    table, data.size - insertable.size, data.size, missingCols(data.first { missingCols(it).isNotEmpty() }))
            }
            jdbc.tx { conn ->
                conn.prepareStatement("DELETE FROM $table WHERE datasource_id=?").use { ps ->
                    ps.setLong(1, dsId)
                    ps.executeUpdate()
                }
                if (insertable.isEmpty()) return@tx
                val sql = "INSERT INTO $table(${cols.joinToString(",") { it.name }}) " +
                        "VALUES (${cols.joinToString(",") { "?" }})"
                conn.prepareStatement(sql).use { ps ->
                    for (row in insertable) {
                        var idx = 1
                        for (col in cols) {
                            val v = if (col.name == "datasource_id") dsId else coerce(row[col.name], col.sqlType)
                            if (v == null) ps.setObject(idx++, null) else ps.setObject(idx++, v)
                        }
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("结构缓存表 $table 导入失败: ${e.message}", e)
        }
    }

    /** 目标表列清单(INFORMATION_SCHEMA,H2 标识符大写存储统一转小写;DATA_TYPE 为类型名字符串,映射回 JDBC 类型码),按表缓存 */
    private data class Col(val name: String, val sqlType: Int, val nullable: Boolean, val hasDefault: Boolean)

    private val tableColumns = ConcurrentHashMap<String, List<Col>>()

    private fun targetColumns(table: String): List<Col> = tableColumns.getOrPut(table) {
        jdbc.query(
            "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE UPPER(TABLE_NAME)=UPPER(?) ORDER BY ORDINAL_POSITION", table) { rs ->
            Col(rs.getString(1).lowercase(), sqlTypeOf(rs.getString(2)),
                "YES".equals(rs.getString(3), ignoreCase = true), rs.getString(4) != null)
        }
    }

    /** H2 的 INFORMATION_SCHEMA.COLUMNS.DATA_TYPE 是类型名(如 BIGINT/CHARACTER VARYING),映射回 java.sql.Types */
    private fun sqlTypeOf(typeName: String?): Int = when (typeName?.uppercase()) {
        "BIGINT" -> Types.BIGINT
        "INT", "INTEGER", "MEDIUMINT" -> Types.INTEGER
        "SMALLINT" -> Types.SMALLINT
        "TINYINT" -> Types.TINYINT
        "TIMESTAMP" -> Types.TIMESTAMP
        "DATE" -> Types.DATE
        "TIME" -> Types.TIME
        "BOOLEAN", "BIT" -> Types.BOOLEAN
        "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> Types.DOUBLE
        "REAL", "FLOAT", "FLOAT4" -> Types.FLOAT
        "DECIMAL", "NUMERIC" -> Types.DECIMAL
        // VARCHAR/CHARACTER VARYING/CLOB 等文本列:统一按字符串处理(coerce 落 value.toString())
        else -> Types.VARCHAR
    }

    /** 行值按目标列 SQL 类型纠正:JSON 数字是 Int/Long/Double,时间列是 ISO 字符串 */
    private fun coerce(value: Any?, sqlType: Int): Any? {
        if (value == null) return null
        return when (sqlType) {
            Types.TIMESTAMP -> when (value) {
                is String -> java.sql.Timestamp.valueOf(LocalDateTime.parse(value))
                is Number -> java.sql.Timestamp(value.toLong())
                else -> value
            }
            Types.BIGINT -> when (value) {
                is Number -> value.toLong()
                is String -> value.toLong()
                else -> value
            }
            Types.INTEGER, Types.SMALLINT, Types.TINYINT -> when (value) {
                is Number -> value.toInt()
                is String -> value.toInt()
                else -> value
            }
            Types.DOUBLE, Types.FLOAT, Types.REAL -> when (value) {
                is Number -> value.toDouble()
                is String -> value.toDouble()
                else -> value
            }
            Types.DECIMAL, Types.NUMERIC -> java.math.BigDecimal(value.toString())
            Types.BOOLEAN, Types.BIT -> when (value) {
                is Boolean -> value
                is String -> value.toBoolean()
                is Number -> value.toInt() != 0
                else -> value
            }
            else -> value
        }
    }

    /** 标记定义按 name 合并(不存在则创建,已有 USER 标记按文件覆盖外观/描述;系统空表标记不动) */
    private fun mergeTags(tags: List<AnnotationTagItem>) {
        for (t in tags) {
            val tagName = t.name.trim()
            if (tagName.isEmpty()) continue
            val color = t.color?.trim()?.takeIf { it.isNotEmpty() } ?: "#409EFF"
            val description = t.description?.trim()?.takeIf { it.isNotEmpty() }
            val tagType = t.tagType?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { TagType.valueOf(it) }.getOrNull() }
                ?.takeIf { it != TagType.SYSTEM }
            val existing = tagRepo.findByName(tagName)
            when {
                existing == null -> tagRepo.create(tagName, color, description, tagType ?: TagType.AI)
                existing.kind == TagKind.USER -> tagRepo.update(existing.id, tagName, color, description, tagType)
                // 与系统空表标记重名:由扫描自动维护,不覆盖
            }
        }
    }

    /** 表标记:标记名解析到本机标记(定义随文件 tags 段已合并),MERGE 幂等,保留打标来源 */
    private fun mergeTableTags(rows: List<Map<String, Any?>>, dsId: Long, skipped: IntArray) {
        for (row in rows) {
            val table = str(row, "table_name")?.takeIf { it.isNotBlank() }
            val tagName = str(row, "tag_name")?.trim()?.takeIf { it.isNotEmpty() }
            if (table == null || tagName == null) {
                skipped[0]++
                continue
            }
            val tag = tagRepo.findByName(tagName)
            if (tag == null || tag.kind != TagKind.USER) {
                skipped[0]++
                continue
            }
            val source = str(row, "source")?.takeIf { it.isNotBlank() } ?: TagSource.MANUAL
            tagRepo.ensureTableTag(tag.id, dsId, str(row, "db_name") ?: "", str(row, "schema_name") ?: "", table, source)
        }
    }

    /** 表说明 upsert 覆盖(model 缺省记 import,与 AnnotationTransfer 口径一致) */
    private fun mergeTableDocs(rows: List<Map<String, Any?>>, dsId: Long) {
        for (row in rows) {
            val table = str(row, "table_name")?.takeIf { it.isNotBlank() } ?: continue
            val description = row["description"]?.toString() ?: continue
            tableDocRepo.upsert(dsId, str(row, "db_name") ?: "", str(row, "schema_name") ?: "",
                table, description, str(row, "model") ?: MODEL_IMPORT)
        }
    }

    private fun mergeSchemaDocs(rows: List<Map<String, Any?>>, dsId: Long) {
        for (row in rows) {
            val schema = str(row, "schema_name")?.takeIf { it.isNotBlank() } ?: continue
            schemaDocRepo.upsert(dsId, str(row, "db_name") ?: "", schema,
                row["description"]?.toString() ?: "")
        }
    }

    /** 表所属系统 upsert 覆盖(一张表最多归属一个系统) */
    private fun mergeTableSystems(rows: List<Map<String, Any?>>, dsId: Long, skipped: IntArray) {
        for (row in rows) {
            val table = str(row, "table_name")?.takeIf { it.isNotBlank() }
            val systemName = str(row, "system_name")?.trim()?.takeIf { it.isNotEmpty() }
            if (table == null || systemName == null) {
                skipped[0]++
                continue
            }
            tableSystemRepo.upsert(dsId, str(row, "db_name") ?: "", str(row, "schema_name") ?: "", table, systemName)
        }
    }

    /** 人工采集 insert-if-absent:本机已收藏的同表记录保留(注释快照以本机为准) */
    private fun mergeManualCollects(rows: List<Map<String, Any?>>, dsId: Long) {
        for (row in rows) {
            val table = str(row, "table_name")?.takeIf { it.isNotBlank() } ?: continue
            val db = str(row, "db_name") ?: ""
            val schema = str(row, "schema_name") ?: ""
            if (manualCollectRepo.exists(dsId, db, schema, table)) continue
            manualCollectRepo.insert(dsId, db, schema, table, str(row, "table_comment"))
        }
    }

    /** ER 关系 insert-if-absent:本机已有的候选/确认/否决决策一律保留,导入只做补齐 */
    private fun mergeRelations(rows: List<Map<String, Any?>>, dsId: Long, skipped: IntArray) {
        for (row in rows) {
            val oneTable = str(row, "one_table")?.takeIf { it.isNotBlank() }
            val oneColumn = str(row, "one_column")?.takeIf { it.isNotBlank() }
            val manyTable = str(row, "many_table")?.takeIf { it.isNotBlank() }
            val manyColumn = str(row, "many_column")?.takeIf { it.isNotBlank() }
            val cardinality = str(row, "cardinality")?.takeIf { it.isNotBlank() }
            if (oneTable == null || oneColumn == null || manyTable == null || manyColumn == null || cardinality == null) {
                skipped[0]++
                continue
            }
            val status = str(row, "status")?.takeIf { it in RELATION_STATUSES } ?: "CANDIDATE"
            val source = str(row, "source")?.takeIf { it.isNotBlank() } ?: "MANUAL"
            tableRelationRepo.insertIfAbsent(dsId, str(row, "db_name") ?: "", str(row, "schema_name") ?: "",
                oneTable, oneColumn, manyTable, manyColumn, cardinality, status, source,
                str(row, "confidence"), (row["overlap_ratio"] as? Number)?.toDouble(), str(row, "remark"))
        }
    }

    /** 数据目录:目录按 (parent_id, name) 逐级匹配/新建做 id 重映射,挂载表/关系表按重映射后 id 幂等补齐 */
    private fun mergeObjectCatalog(item: MetadataExportItem, dsId: Long, name: String,
                                   result: ImportResult, skipped: IntArray) {
        if (item.objectDirs.isEmpty()) return
        // 已有目录索引 + 本次新建,避免重复创建(uk_object_dir 兜底)
        val dirIndex = objectCatalogRepo.listDirs(dsId)
            .associateBy({ it.parentId to it.name }, { it.id })
            .toMutableMap()
        val bySourceId = item.objectDirs.associateBy { num(it["source_id"]) }
        val idMap = HashMap<Long, Long>()
        var orphan = 0

        fun resolveDir(row: Map<String, Any?>): Long? {
            val sid = num(row["source_id"]) ?: return null
            idMap[sid]?.let { return it }
            val parentSid = num(row["source_parent_id"]) ?: 0L
            val parentId = when {
                parentSid == 0L -> 0L
                else -> bySourceId[parentSid]?.let { resolveDir(it) } ?: run { orphan++; 0L }
            }
            val dirName = str(row, "name")?.takeIf { it.isNotBlank() } ?: return null
            val key = parentId to dirName
            val newId = dirIndex[key] ?: objectCatalogRepo.insertDir(dsId, parentId, dirName).also { dirIndex[key] = it }
            idMap[sid] = newId
            return newId
        }

        for (row in item.objectDirs) resolveDir(row)
        if (orphan > 0) {
            result.warnings.add("「$name」有 $orphan 个目录的父目录在导出文件中缺失,已挂到根目录下")
        }

        // 挂载表:源 id → 本机 id(已存在则沿用,保证重复导入幂等)
        val tableIdMap = HashMap<Long, Long>()
        for (row in item.objectTables) {
            val sid = num(row["source_id"])
            val dirId = num(row["source_dir_id"])?.let { idMap[it] }
            val table = str(row, "table_name")?.takeIf { it.isNotBlank() }
            val schema = str(row, "schema_name")?.takeIf { it.isNotBlank() }
            if (sid == null || dirId == null || table == null || schema == null) {
                skipped[0]++
                continue
            }
            val db = str(row, "db_name") ?: ""
            val found = objectCatalogRepo.findTable(dirId, db, schema, table)
            tableIdMap[sid] = found?.id
                ?: objectCatalogRepo.insertTable(dirId, db, schema, table, str(row, "remark"), relKind(row))
        }

        // 挂载表登记的关系表:按重映射后的挂载记录 id 幂等补齐
        for (row in item.objectTableRels) {
            val objectTableId = num(row["source_object_table_id"])?.let { tableIdMap[it] }
            val table = str(row, "table_name")?.takeIf { it.isNotBlank() }
            val schema = str(row, "schema_name")?.takeIf { it.isNotBlank() }
            if (objectTableId == null || table == null || schema == null) {
                skipped[0]++
                continue
            }
            val db = str(row, "db_name") ?: ""
            if (objectCatalogRepo.findRel(objectTableId, db, schema, table) == null) {
                objectCatalogRepo.insertRel(objectTableId, db, schema, table, str(row, "remark"), relKind(row))
            }
        }
    }

    private fun relKind(row: Map<String, Any?>): String? =
        str(row, "rel_kind")?.takeIf { it == "INCLUDE" || it == "ASSOC" }

    private fun str(row: Map<String, Any?>, key: String): String? = row[key]?.toString()

    private fun num(v: Any?): Long? = (v as? Number)?.toLong()

    private fun parseFile(input: InputStream): MetadataExportFile {
        val file: MetadataExportFile = try {
            TransferJson.read(input, MetadataExportFile::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的元数据导出文件", e)
        }
        if (file.app != APP_MARKER || file.version != VERSION) {
            throw IllegalArgumentException("不是有效的元数据导出文件")
        }
        return file
    }

    private companion object {
        const val APP_MARKER = "dq-tool-metadata"
        const val VERSION = 1

        /** 导入落库的 model 标记(与 AnnotationTransferService 同口径):区分大模型生成的描述 */
        const val MODEL_IMPORT = "import"

        val RELATION_STATUSES = setOf("CANDIDATE", "CONFIRMED", "REJECTED")
    }
}
