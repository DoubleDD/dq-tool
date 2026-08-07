package com.example.dq.repository

import com.example.dq.model.Tag
import com.example.dq.model.TagKind
import java.sql.ResultSet

/** 表标记:全局标记定义 CRUD + 表级打标关系维护 + 标记统计查询(只读本地 H2,不连业务库) */
class TagRepository(private val jdbc: Jdbc) {

    private val mapper: (ResultSet) -> Tag = { rs ->
        Tag(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
            TagKind.valueOf(rs.getString("kind")))
    }

    /** 全部标记(含系统「空表」),带打标表数 */
    fun listAll(): List<Tag> =
        jdbc.query("SELECT d.id, d.name, d.color, d.kind, COUNT(t.id) AS table_count FROM tag_def d " +
                "LEFT JOIN table_tag t ON t.tag_id = d.id " +
                "GROUP BY d.id, d.name, d.color, d.kind ORDER BY d.id") { rs ->
            Tag(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                TagKind.valueOf(rs.getString("kind")), rs.getLong("table_count"))
        }

    fun findById(id: Long): Tag? =
        jdbc.queryOne("SELECT * FROM tag_def WHERE id=?", id, mapper = mapper)

    fun findByName(name: String): Tag? =
        jdbc.queryOne("SELECT * FROM tag_def WHERE name=?", name, mapper = mapper)

    /** 系统空表标记(V3 迁移保证存在);扫描联动与「不可编辑」判定都按 kind 识别 */
    fun findEmptyTag(): Tag? =
        jdbc.queryOne("SELECT * FROM tag_def WHERE kind='EMPTY'", mapper = mapper)

    fun create(name: String, color: String): Tag {
        val id = jdbc.insert("INSERT INTO tag_def(name, color, kind) VALUES (?,?,'USER')", name, color)
        return Tag(id, name, color, TagKind.USER)
    }

    fun update(id: Long, name: String, color: String) {
        jdbc.update("UPDATE tag_def SET name=?, color=? WHERE id=?", name, color, id)
    }

    /** 删除标记;table_tag 外键 ON DELETE CASCADE 自动解除全部打标关系 */
    fun delete(id: Long) {
        jdbc.update("DELETE FROM tag_def WHERE id=?", id)
    }

    /** 某库下全部表的打标情况:表名 -> 标记列表(一次拉回,供表列表页与客户端按标记过滤) */
    fun tableTagsBySchema(datasourceId: Long, dbName: String, schema: String): Map<String, List<Tag>> {
        val result = LinkedHashMap<String, MutableList<Tag>>()
        jdbc.query("SELECT tt.table_name, d.id, d.name, d.color, d.kind FROM table_tag tt " +
                "JOIN tag_def d ON d.id = tt.tag_id " +
                "WHERE tt.datasource_id=? AND tt.db_name=? AND tt.schema_name=? " +
                "ORDER BY tt.table_name, d.id",
            datasourceId, dbName, schema) { rs ->
            result.getOrPut(rs.getString("table_name")) { ArrayList() }
                .add(Tag(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                    TagKind.valueOf(rs.getString("kind"))))
        }
        return result
    }

    /**
     * 整体替换单表的 USER 标记(不动 EMPTY 系统标记关系),同一事务内先删后插。
     * tagIds 的合法性(存在且为 USER)由 service 层校验。
     */
    fun replaceUserTags(datasourceId: Long, dbName: String, schema: String, table: String, tagIds: Collection<Long>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "DELETE FROM table_tag WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=? " +
                        "AND tag_id IN (SELECT id FROM tag_def WHERE kind='USER')").use { ps ->
                ps.setLong(1, datasourceId)
                ps.setString(2, dbName)
                ps.setString(3, schema)
                ps.setString(4, table)
                ps.executeUpdate()
            }
            if (tagIds.isNotEmpty()) {
                conn.prepareStatement(
                    "INSERT INTO table_tag(tag_id, datasource_id, db_name, schema_name, table_name) " +
                            "VALUES (?,?,?,?,?)").use { ps ->
                    for (tagId in tagIds) {
                        ps.setLong(1, tagId)
                        ps.setLong(2, datasourceId)
                        ps.setString(3, dbName)
                        ps.setString(4, schema)
                        ps.setString(5, table)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    /** 打上标记(幂等,唯一键兜底);空表标记联动用 */
    fun ensureTableTag(tagId: Long, datasourceId: Long, dbName: String, schema: String, table: String) {
        jdbc.update("MERGE INTO table_tag(tag_id, datasource_id, db_name, schema_name, table_name) " +
                "KEY(tag_id, datasource_id, db_name, schema_name, table_name) VALUES (?,?,?,?,?)",
            tagId, datasourceId, dbName, schema, table)
    }

    /** 摘除标记(幂等);空表标记联动用 */
    fun removeTableTag(tagId: Long, datasourceId: Long, dbName: String, schema: String, table: String) {
        jdbc.update("DELETE FROM table_tag WHERE tag_id=? AND datasource_id=? AND db_name=? " +
                "AND schema_name=? AND table_name=?",
            tagId, datasourceId, dbName, schema, table)
    }

    /** 全局打标表数(跨所有标记按 数据源+库+表 去重) */
    fun countCoveredTables(): Int =
        jdbc.queryOne("SELECT COUNT(*) FROM (SELECT DISTINCT datasource_id, db_name, schema_name, table_name " +
                "FROM table_tag) x") { rs -> rs.getInt(1) } ?: 0

    /** 库维度标记计数行(schema-tag-stats 的扁平结果,由 service 组装成嵌套结构) */
    data class SchemaTagCountRow(val schemaName: String, val tagId: Long, val tagName: String,
                                 val color: String, val kind: TagKind, val count: Int)

    /** 某数据源某库下,有标记表的库的标记计数(无标记表的库不返回) */
    fun schemaTagCounts(datasourceId: Long, dbName: String): List<SchemaTagCountRow> =
        jdbc.query("SELECT tt.schema_name, d.id AS tag_id, d.name, d.color, d.kind, COUNT(*) AS cnt " +
                "FROM table_tag tt JOIN tag_def d ON d.id = tt.tag_id " +
                "WHERE tt.datasource_id=? AND tt.db_name=? " +
                "GROUP BY tt.schema_name, d.id, d.name, d.color, d.kind " +
                "ORDER BY tt.schema_name, d.id",
            datasourceId, dbName) { rs ->
            SchemaTagCountRow(rs.getString("schema_name"), rs.getLong("tag_id"), rs.getString("name"),
                rs.getString("color"), TagKind.valueOf(rs.getString("kind")), rs.getInt("cnt"))
        }

    /** 标记的库维度分布行;datasourceName 由 service 层补齐 */
    data class SchemaDistribution(val datasourceId: Long, val dbName: String, val schemaName: String,
                                  val tableCount: Int, val totalRows: Long?, val totalColumns: Long?)

    /**
     * 按标记的库维度分布:表数直接数打标关系;行数/列数只累计每张表最近一次 DONE 扫描快照
     * (scan_table.total_rows + scan_column 行数),从未扫描过的表不计入;
     * 某库下无任何已扫描表时该行 totalRows/totalColumns 为 null。
     */
    fun schemaDistribution(tagId: Long): List<SchemaDistribution> {
        // 打标关系(统计基数,不依赖扫描)
        data class TableKey(val datasourceId: Long, val dbName: String, val schemaName: String, val tableName: String)

        val tagged = LinkedHashSet<TableKey>()
        jdbc.query("SELECT datasource_id, db_name, schema_name, table_name FROM table_tag WHERE tag_id=?",
            tagId) { rs ->
            tagged.add(TableKey(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
        }
        if (tagged.isEmpty()) {
            return emptyList()
        }

        // 每张打标表最近一次 DONE 扫描快照:按 job_id 升序遍历、后者覆盖前者(与 latestDoneJobsByTable 同口径);
        // scan_job.db_name 可空,与 table_tag 的空串兜底口径在 JOIN 条件里对齐
        data class Snapshot(val scanTableId: Long, val totalRows: Long?)

        val snapshots = HashMap<TableKey, Snapshot>()
        jdbc.query("SELECT tt.datasource_id, tt.db_name, tt.schema_name, tt.table_name, " +
                "t.id AS scan_table_id, t.total_rows FROM table_tag tt " +
                "JOIN scan_job j ON j.datasource_id = tt.datasource_id " +
                "AND (j.db_name = tt.db_name OR (j.db_name IS NULL AND tt.db_name = '')) " +
                "AND j.schema_name = tt.schema_name " +
                "JOIN scan_table t ON t.job_id = j.id AND t.table_name = tt.table_name AND t.status = 'DONE' " +
                "WHERE tt.tag_id = ? ORDER BY t.job_id",
            tagId) { rs ->
            val key = TableKey(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))
            val rows = rs.getLong("total_rows")
            snapshots[key] = Snapshot(rs.getLong("scan_table_id"), if (rs.wasNull()) null else rows)
        }

        // 快照对应的列数(scan_column 行数)
        val columnCounts = HashMap<Long, Int>()
        if (snapshots.isNotEmpty()) {
            val ids = snapshots.values.map { it.scanTableId }.distinct()
            val placeholders = ids.joinToString(", ") { "?" }
            jdbc.query("SELECT scan_table_id, COUNT(*) AS cnt FROM scan_column " +
                    "WHERE scan_table_id IN ($placeholders) GROUP BY scan_table_id",
                *ids.toTypedArray()) { rs ->
                columnCounts[rs.getLong("scan_table_id")] = rs.getInt("cnt")
            }
        }

        // 按库聚合:表数=打标表数;行/列只加总有快照的表,整库无快照则为 null
        val bySchema = LinkedHashMap<Triple<Long, String, String>, MutableList<TableKey>>()
        for (key in tagged) {
            bySchema.getOrPut(Triple(key.datasourceId, key.dbName, key.schemaName)) { ArrayList() }.add(key)
        }
        return bySchema.map { (schemaKey, tables) ->
            var rowsSum = 0L
            var colsSum = 0L
            var anyScanned = false
            for (table in tables) {
                val snap = snapshots[table] ?: continue
                anyScanned = true
                rowsSum += snap.totalRows ?: 0L
                colsSum += columnCounts[snap.scanTableId] ?: 0
            }
            SchemaDistribution(schemaKey.first, schemaKey.second, schemaKey.third, tables.size,
                if (anyScanned) rowsSum else null, if (anyScanned) colsSum else null)
        }
    }
}
