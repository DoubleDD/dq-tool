package com.example.dq.service

import com.example.dq.model.SchemaTagCount
import com.example.dq.model.SchemaTagStat
import com.example.dq.model.Tag
import com.example.dq.model.TagKind
import com.example.dq.model.TagSchemaStat
import com.example.dq.model.TagStats
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.TagRepository

/**
 * 表标记:CRUD 校验(重名 409 / 操作空表标记 400)+ 两个统计视图组装 + 扫描完成的空表标记联动。
 * 异常约定与 WebServer 映射一致:IllegalArgumentException → 400,IllegalStateException → 409。
 */
class TagService(
    private val tagRepo: TagRepository,
    private val dataSourceRepo: DataSourceRepository,
) {

    /** 全部标记(含系统「空表」),带打标表数 */
    fun list(): List<Tag> = tagRepo.listAll()

    fun create(name: String?, color: String?): Tag {
        val n = normalizeName(name)
        val c = normalizeColor(color)
        if (tagRepo.findByName(n) != null) {
            throw IllegalStateException("标记名称已存在:$n")
        }
        return tagRepo.create(n, c)
    }

    fun update(id: Long, name: String?, color: String?): Tag {
        val tag = requireTag(id)
        requireUserKind(tag)
        val n = normalizeName(name)
        val c = normalizeColor(color)
        val dup = tagRepo.findByName(n)
        if (dup != null && dup.id != id) {
            throw IllegalStateException("标记名称已存在:$n")
        }
        tagRepo.update(id, n, c)
        return tagRepo.findById(id)!!
    }

    /** 删除标记;已打标关系由外键级联自动解除 */
    fun delete(id: Long) {
        requireUserKind(requireTag(id))
        tagRepo.delete(id)
    }

    /** 标记维度统计:顶部指标 + 按库分布(行/列只累计最近一次 DONE 扫描快照) */
    fun stats(id: Long): TagStats {
        val tag = requireTag(id)
        val dsNames = dataSourceRepo.findAll().associate { it.id!! to (it.name ?: "") }
        val schemas = tagRepo.schemaDistribution(id).map {
            TagSchemaStat(it.datasourceId, dsNames[it.datasourceId] ?: "", it.dbName, it.schemaName,
                it.tableCount, it.totalRows, it.totalColumns)
        }
        return TagStats(
            tag,
            schemas.sumOf { it.tableCount },
            sumOrNull(schemas.map { it.totalRows }),
            sumOrNull(schemas.map { it.totalColumns }),
            tagRepo.countCoveredTables(),
            schemas)
    }

    /** 库维度标记计数:只返回有标记表的库 */
    fun schemaTagStats(datasourceId: Long, database: String?): List<SchemaTagStat> {
        val rows = tagRepo.schemaTagCounts(datasourceId, normalizeDb(database))
        val bySchema = LinkedHashMap<String, MutableList<SchemaTagCount>>()
        for (row in rows) {
            bySchema.getOrPut(row.schemaName) { ArrayList() }
                .add(SchemaTagCount(row.tagId, row.tagName, row.color, row.kind, row.count))
        }
        return bySchema.map { (schema, tags) -> SchemaTagStat(schema, tags) }
    }

    /** 某库下全部表的打标 map:表名 -> 标记列表 */
    fun tableTags(datasourceId: Long, database: String?, schema: String): Map<String, List<Tag>> =
        tagRepo.tableTagsBySchema(datasourceId, normalizeDb(database), schema)

    /** 整体替换单表的 USER 标记(空表标记不可手动打摘),返回该表最新标记数组 */
    fun replaceTableTags(datasourceId: Long, database: String?, schema: String, table: String,
                         tagIds: List<Long>): List<Tag> {
        val ids = tagIds.distinct()
        for (tagId in ids) {
            val tag = tagRepo.findById(tagId) ?: throw IllegalArgumentException("标记不存在:$tagId")
            if (tag.kind == TagKind.EMPTY) {
                throw IllegalArgumentException("空表标记由扫描结果自动维护,不可手动打标")
            }
        }
        val db = normalizeDb(database)
        tagRepo.replaceUserTags(datasourceId, db, schema, table, ids)
        return tagRepo.tableTagsBySchema(datasourceId, db, schema)[table] ?: emptyList()
    }

    /** 扫描完成联动:空表(totalRows=0)确保打上「空表」标记,非空摘除;幂等 */
    fun syncEmptyTag(datasourceId: Long, dbName: String?, schema: String, table: String, totalRows: Long) {
        val empty = tagRepo.findEmptyTag() ?: return
        val db = normalizeDb(dbName)
        if (totalRows == 0L) {
            tagRepo.ensureTableTag(empty.id, datasourceId, db, schema, table)
        } else {
            tagRepo.removeTableTag(empty.id, datasourceId, db, schema, table)
        }
    }

    private fun requireTag(id: Long): Tag =
        tagRepo.findById(id) ?: throw IllegalArgumentException("标记不存在:$id")

    private fun requireUserKind(tag: Tag) {
        if (tag.kind == TagKind.EMPTY) {
            throw IllegalArgumentException("空表标记由扫描结果自动维护,不可编辑或删除")
        }
    }

    private fun normalizeName(name: String?): String {
        val n = name?.trim()
        if (n.isNullOrEmpty()) {
            throw IllegalArgumentException("标记名称不能为空")
        }
        return n
    }

    /** 颜色缺省回落到默认色,与 tag_def.color 默认值一致 */
    private fun normalizeColor(color: String?): String =
        color?.trim()?.takeIf { it.isNotEmpty() } ?: "#409EFF"

    private companion object {

        /** 无库概念的方言 db 为 null,统一存空串保证唯一键(与 table_doc 口径一致) */
        fun normalizeDb(database: String?): String = database ?: ""

        /** 全部行为 null 时合计为 null(无任何已扫描表,前端显示「—」),否则 null 按 0 累加 */
        fun sumOrNull(values: List<Long?>): Long? =
            if (values.all { it == null }) null else values.sumOf { it ?: 0L }
    }
}
