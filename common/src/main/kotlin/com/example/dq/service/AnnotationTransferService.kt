package com.example.dq.service

import com.example.dq.model.AnnotationExportFile
import com.example.dq.model.AnnotationImportPreview
import com.example.dq.model.AnnotationImportResult
import com.example.dq.model.AnnotationPreviewDs
import com.example.dq.model.AnnotationTableDocItem
import com.example.dq.model.AnnotationTableTagItem
import com.example.dq.model.AnnotationTagItem
import com.example.dq.model.TagKind
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.TransferJson
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 标记与描述数据的导出/导入:把 USER 标记定义(含描述)、表-标记关联、表描述打包成 JSON,
 * 在另一台机器导入,避免换机后重新打标与重新生成描述。
 * 跨实例对齐键:标记按 name,表级数据按 数据源名 + db + schema + table;不导出任何内部 id。
 * 导入合并规则:标记不存在则创建、已存在则用文件里的 color/description 覆盖更新;
 * 表级行的数据源对应:显式映射(dsMapping:文件数据源名 → 本机数据源 id,值 0 表示强制跳过)优先,
 * 未给映射的名称回退按数据源名匹配(不同机器上同一数据源的命名可能不同,故提供预检+映射);
 * 匹配不到的行跳过并计数;表标记 ensure 幂等插入,表描述 upsert 覆盖现有内容。
 * EMPTY 系统空表标记由扫描自动维护,定义与关联都不参与导出/导入。
 */
class AnnotationTransferService(
    private val tagRepo: TagRepository,
    private val tableDocRepo: TableDocRepository,
    private val dataSourceRepo: DataSourceRepository,
) {

    private val objectMapper = jacksonObjectMapper()

    /** 导出全部 USER 标记定义、USER 标记的表级关联、全部表描述为 JSON 文件 */
    fun export(out: OutputStream) {
        val dsNames = dataSourceRepo.findAll().associate { it.id!! to (it.name ?: "") }
        val tags = tagRepo.listAll()
            .filter { it.kind == TagKind.USER }
            .map { AnnotationTagItem(it.name, it.color, it.description) }
        val tableTags = tagRepo.listUserTableTagRows().map { r ->
            AnnotationTableTagItem(dsNames[r.datasourceId] ?: "", r.dbName, r.schemaName, r.tableName, r.tagName)
        }
        val tableDocs = tableDocRepo.findAll().map { r ->
            AnnotationTableDocItem(dsNames[r.datasourceId] ?: "", r.dbName, r.schemaName, r.tableName, r.description)
        }
        val file = AnnotationExportFile(
            app = APP_MARKER,
            version = VERSION,
            exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            tags = tags,
            tableTags = tableTags,
            tableDocs = tableDocs,
        )
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, file)
    }

    /** 导入预检:解析文件并按数据源名聚合表级行数,供前端做数据源映射;格式不对抛 IllegalArgumentException */
    fun preview(input: InputStream): AnnotationImportPreview {
        val file = parseFile(input)
        val dsRows = LinkedHashMap<String, AnnotationPreviewDs>()
        for (item in file.tableTags) {
            val row = dsRows.getOrPut(item.datasourceName) { AnnotationPreviewDs(item.datasourceName, 0, 0) }
            dsRows[item.datasourceName] = row.copy(tableTags = row.tableTags + 1)
        }
        for (item in file.tableDocs) {
            val row = dsRows.getOrPut(item.datasourceName) { AnnotationPreviewDs(item.datasourceName, 0, 0) }
            dsRows[item.datasourceName] = row.copy(tableDocs = row.tableDocs + 1)
        }
        return AnnotationImportPreview(tags = file.tags.size, datasources = dsRows.values.toList())
    }

    /**
     * 导入导出文件;格式标识不对抛 IllegalArgumentException(Web 层映射 400)。
     * dsMapping:文件数据源名 → 本机数据源 id;值 0 表示该数据源的表级行强制跳过;
     * 未包含的名称回退按数据源名匹配本机数据源(兼容无映射的直接导入)。
     */
    @JvmOverloads
    fun importJson(input: InputStream, dsMapping: Map<String, Long> = emptyMap()): AnnotationImportResult {
        val file = parseFile(input)

        val result = AnnotationImportResult()

        // 1. 标记按 name 合并:不存在则创建,已存在则覆盖 color/description(系统空表标记不动)
        for (item in file.tags) {
            val name = item.name.trim()
            if (name.isEmpty()) {
                continue
            }
            val color = normalizeColor(item.color)
            val description = item.description?.trim()?.takeIf { it.isNotEmpty() }
            val existing = tagRepo.findByName(name)
            when {
                existing == null -> {
                    tagRepo.create(name, color, description)
                    result.tagsCreated++
                }
                existing.kind == TagKind.USER -> {
                    tagRepo.update(existing.id, name, color, description)
                    result.tagsUpdated++
                }
                // 与系统空表标记重名:由扫描自动维护,不覆盖
            }
        }

        // 2. 表级数据:显式映射优先,未映射的名称回退按数据源名匹配;匹配不到(或映射为跳过)的行计数跳过
        val dsIds = dataSourceRepo.findAll()
            .mapNotNull { c -> c.name?.takeIf { it.isNotBlank() }?.let { it to c.id!! } }
            .toMap()

        fun resolveDs(datasourceName: String): Long? {
            val mapped = dsMapping[datasourceName]
            if (mapped != null) {
                return mapped.takeIf { it > 0 }
            }
            return dsIds[datasourceName]
        }

        for (item in file.tableTags) {
            val dsId = resolveDs(item.datasourceName)
            val tag = item.tagName.trim().takeIf { it.isNotEmpty() }?.let { tagRepo.findByName(it) }
            if (dsId == null || tag == null || tag.kind != TagKind.USER || item.tableName.isBlank()) {
                result.tableTagsSkipped++
                continue
            }
            // 幂等:已存在的关系不重复插入(唯一键兜底)
            tagRepo.ensureTableTag(tag.id, dsId, item.dbName, item.schemaName, item.tableName)
            result.tableTagsAdded++
        }

        for (item in file.tableDocs) {
            val dsId = resolveDs(item.datasourceName)
            if (dsId == null || item.tableName.isBlank()) {
                result.docsSkipped++
                continue
            }
            // upsert 覆盖现有描述;model 记为 import 表示来自导入而非大模型生成
            tableDocRepo.upsert(dsId, item.dbName, item.schemaName, item.tableName, item.description, MODEL_IMPORT)
            result.docsUpserted++
        }
        return result
    }

    /** 解析并校验导出文件(GBK 转存/未知字段由 TransferJson 兜底);格式不对抛 IllegalArgumentException(Web 层映射 400) */
    private fun parseFile(input: InputStream): AnnotationExportFile {
        val file: AnnotationExportFile = try {
            TransferJson.read(input, AnnotationExportFile::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的标记与描述导出文件", e)
        }
        if (file.app != APP_MARKER || file.version != VERSION) {
            throw IllegalArgumentException("不是有效的标记与描述导出文件")
        }
        return file
    }

    private companion object {
        const val APP_MARKER = "dq-tool-annotations"
        const val VERSION = 1

        /** 导入落库的 model 标记:区分大模型生成的描述 */
        const val MODEL_IMPORT = "import"

        /** 颜色缺省回落到默认色,与 tag_def.color 默认值一致 */
        fun normalizeColor(color: String?): String =
            color?.trim()?.takeIf { it.isNotEmpty() } ?: "#409EFF"
    }
}
