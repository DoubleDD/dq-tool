package com.example.dq.repository

import com.example.dq.model.TableRelation
import com.example.dq.model.TableRelationOriginal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDateTime

/**
 * ER 表间关系的本地 H2 读写(只读写本地库,不连业务库):
 * - table_relation = 最终关系(人工审核最后一次修改后的结果;`reviewed=true` 的行重新推导不得覆盖)
 * - table_relation_original = 原始关系(大模型推导产出快照,每次推导刷新,人工审核不改变它)
 * 方向化存储:one 侧 = 唯一方,many 侧 = 重复方(ONE_TO_ONE/SUSPECT_MANY_TO_MANY 时字典序小者入 one 侧)
 */
class TableRelationRepository(private val jdbc: Jdbc) {

    private val mapper: (ResultSet) -> TableRelation = { rs ->
        val ratio = rs.getDouble("overlap_ratio")
        TableRelation(
            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("one_table"), rs.getString("one_column"), rs.getString("many_table"), rs.getString("many_column"),
            rs.getString("cardinality"), rs.getString("status"), rs.getString("source"), rs.getString("confidence"),
            if (rs.wasNull()) null else ratio, rs.getString("remark"),
            rs.getBoolean("reviewed"), ts(rs, "reviewed_at"),
            ts(rs, "created_at"), ts(rs, "updated_at"))
    }

    private val originalMapper: (ResultSet) -> TableRelationOriginal = { rs ->
        val ratio = rs.getDouble("overlap_ratio")
        TableRelationOriginal(
            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("one_table"), rs.getString("one_column"), rs.getString("many_table"), rs.getString("many_column"),
            rs.getString("cardinality"), rs.getString("status"), rs.getString("source"), rs.getString("confidence"),
            if (rs.wasNull()) null else ratio, rs.getString("remark"),
            ts(rs, "derived_at"), ts(rs, "updated_at"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    /** 某库最终关系列表;status 非空时按状态过滤(图组装取 CONFIRMED 等均走这里) */
    fun listBySchema(datasourceId: Long, dbName: String, schema: String, status: String? = null): List<TableRelation> =
        if (status == null) {
            jdbc.query("SELECT * FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? ORDER BY id",
                datasourceId, dbName, schema, mapper = mapper)
        } else {
            jdbc.query("SELECT * FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? AND status=? ORDER BY id",
                datasourceId, dbName, schema, status, mapper = mapper)
        }

    /** 某表参与的最终关系(星型图;两端任一命中);status 非空时按状态过滤 */
    fun listByTable(datasourceId: Long, dbName: String, schema: String, table: String, status: String? = null): List<TableRelation> {
        val base = "SELECT * FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                "AND (one_table=? OR many_table=?)"
        return if (status == null) {
            jdbc.query("$base ORDER BY id", datasourceId, dbName, schema, table, table, mapper = mapper)
        } else {
            jdbc.query("$base AND status=? ORDER BY id", datasourceId, dbName, schema, table, table, status, mapper = mapper)
        }
    }

    fun findById(id: Long): TableRelation? =
        jdbc.queryOne("SELECT * FROM table_relation WHERE id=?", id, mapper = mapper)

    /** 按唯一键(四元组 + 两端表/字段)查已存在关系 */
    fun findByKey(datasourceId: Long, dbName: String, schema: String,
                  oneTable: String, oneColumn: String, manyTable: String, manyColumn: String): TableRelation? =
        jdbc.queryOne("SELECT * FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                "AND one_table=? AND one_column=? AND many_table=? AND many_column=?",
            datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn, mapper = mapper)

    /** 某库原始关系列表(导出「原始关系」sheet / 与最终关系做差) */
    fun listOriginalsBySchema(datasourceId: Long, dbName: String, schema: String): List<TableRelationOriginal> =
        jdbc.query("SELECT * FROM table_relation_original WHERE datasource_id=? AND db_name=? AND schema_name=? ORDER BY id",
            datasourceId, dbName, schema, mapper = originalMapper)

    /** 某表参与的原始关系(两端任一命中;含已被人工删除、只剩原始快照的关系) */
    fun listOriginalsByTable(datasourceId: Long, dbName: String, schema: String, table: String): List<TableRelationOriginal> =
        jdbc.query("SELECT * FROM table_relation_original WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                "AND (one_table=? OR many_table=?) ORDER BY id",
            datasourceId, dbName, schema, table, table, mapper = originalMapper)

    /**
     * 已存在(按唯一键)则跳过返回 null,否则插入返回自增 id;不覆盖已有 status/confidence(已确认关系不被新推导降级)。
     * `reviewed` 表示该行是否为人工裁决结果(人工补充/导入的确认否决态为 true)。
     */
    fun insertIfAbsent(datasourceId: Long, dbName: String, schema: String,
                       oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                       cardinality: String, status: String, source: String,
                       confidence: String?, overlapRatio: Double?, remark: String?,
                       reviewed: Boolean = false): Long? =
        jdbc.tx { conn ->
            conn.prepareStatement("SELECT id FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                    "AND one_table=? AND one_column=? AND many_table=? AND many_column=?").use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
                ps.executeQuery().use { rs -> if (rs.next()) return@tx null }
            }
            doInsert(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn,
                cardinality, status, source, confidence, overlapRatio, remark, reviewed)
        }

    /**
     * 原始关系快照入库(仅推导/导入用):方向无关 upsert,总是按最新推导数据整行刷新,
     * 人工审核不触碰本表,故它是「大模型生成的关系」的忠实留痕。
     */
    fun upsertOriginal(datasourceId: Long, dbName: String, schema: String,
                       oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                       cardinality: String, status: String, source: String,
                       confidence: String?, overlapRatio: Double?, remark: String?) {
        jdbc.tx { conn ->
            upsertOriginal(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn,
                cardinality, status, source, confidence, overlapRatio, remark)
        }
    }

    /** 原始关系 insert-if-absent(元数据导入用):本机已有快照一律保留,只做补齐 */
    fun insertOriginalIfAbsent(datasourceId: Long, dbName: String, schema: String,
                               oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                               cardinality: String, status: String, source: String,
                               confidence: String?, overlapRatio: Double?, remark: String?): Long? =
        jdbc.tx { conn ->
            if (findOriginalId(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn) != null) {
                return@tx null
            }
            doInsertOriginal(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn,
                cardinality, status, source, confidence, overlapRatio, remark)
        }

    /**
     * 推导结果入库(仅 RelationInferService 用),返回行 id 或 null:
     * 1) 原始关系快照始终刷新(方向无关),留痕「大模型生成的关系」;
     * 2) 最终关系:不存在 → 插入 CANDIDATE(reviewed=false,留待人工审核);
     *    已存在且 `reviewed=true`(人工已裁决)→ **原样保留**(不降级、不复活,重新推导命中不再回炉);
     *    已存在且未审核 → 刷新推导属性,保持 CANDIDATE;
     * 返回非 null 仅表示「新插入了一条最终关系」(即人工待审核的新关系)。
     */
    fun upsertDerived(datasourceId: Long, dbName: String, schema: String,
                      oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                      cardinality: String, source: String,
                      confidence: String?, overlapRatio: Double?, remark: String?): Long? =
        jdbc.tx { conn ->
            // 1. 原始关系留痕:人工审核前/后都保持模型产出,重新推导按最新验证数据刷新
            upsertOriginal(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn,
                cardinality, "CANDIDATE", source, confidence, overlapRatio, remark)
            // 2. 最终关系:方向无关命中(兼容历史推导方向翻转)
            var hitId: Long? = null
            var hitReviewed = false
            conn.prepareStatement("SELECT id, reviewed FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                    "AND ((one_table=? AND one_column=? AND many_table=? AND many_column=?) " +
                    "OR (one_table=? AND one_column=? AND many_table=? AND many_column=?))").use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
                ps.setString(8, manyTable); ps.setString(9, manyColumn); ps.setString(10, oneTable); ps.setString(11, oneColumn)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        hitId = rs.getLong(1)
                        hitReviewed = rs.getBoolean(2)
                    }
                }
            }
            val id = hitId ?: return@tx doInsert(conn, datasourceId, dbName, schema,
                oneTable, oneColumn, manyTable, manyColumn,
                cardinality, "CANDIDATE", source, confidence, overlapRatio, remark, reviewed = false)
            // 人工裁决过的关系:保留上次审核结果(状态/备注/方向全不动)
            if (hitReviewed) return@tx null
            // 未审核的既有行:刷新推导属性,状态仍是候选
            conn.prepareStatement("UPDATE table_relation SET cardinality=?, source=?, confidence=?, overlap_ratio=?, " +
                    "remark=?, updated_at=CURRENT_TIMESTAMP WHERE id=?").use { ps ->
                ps.setString(1, cardinality); ps.setString(2, source); ps.setString(3, confidence)
                if (overlapRatio != null) ps.setDouble(4, overlapRatio) else ps.setNull(4, Types.DOUBLE)
                ps.setString(5, remark)
                ps.setLong(6, id)
                ps.executeUpdate()
            }
            null
        }

    private fun findOriginalId(conn: Connection, datasourceId: Long, dbName: String, schema: String,
                               oneTable: String, oneColumn: String, manyTable: String, manyColumn: String): Long? =
        conn.prepareStatement("SELECT id FROM table_relation_original WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                "AND ((one_table=? AND one_column=? AND many_table=? AND many_column=?) " +
                "OR (one_table=? AND one_column=? AND many_table=? AND many_column=?))").use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
            ps.setString(8, manyTable); ps.setString(9, manyColumn); ps.setString(10, oneTable); ps.setString(11, oneColumn)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun upsertOriginal(conn: Connection, datasourceId: Long, dbName: String, schema: String,
                               oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                               cardinality: String, status: String, source: String,
                               confidence: String?, overlapRatio: Double?, remark: String?) {
        val existing = findOriginalId(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn)
        if (existing == null) {
            doInsertOriginal(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn,
                cardinality, status, source, confidence, overlapRatio, remark)
            return
        }
        conn.prepareStatement("UPDATE table_relation_original SET one_table=?, one_column=?, many_table=?, many_column=?, " +
                "cardinality=?, status=?, source=?, confidence=?, overlap_ratio=?, remark=?, " +
                "updated_at=CURRENT_TIMESTAMP WHERE id=?").use { ps ->
            ps.setString(1, oneTable); ps.setString(2, oneColumn); ps.setString(3, manyTable); ps.setString(4, manyColumn)
            ps.setString(5, cardinality); ps.setString(6, status); ps.setString(7, source); ps.setString(8, confidence)
            if (overlapRatio != null) ps.setDouble(9, overlapRatio) else ps.setNull(9, Types.DOUBLE)
            ps.setString(10, remark)
            ps.setLong(11, existing)
            ps.executeUpdate()
        }
    }

    private fun doInsert(conn: Connection, datasourceId: Long, dbName: String, schema: String,
                         oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                         cardinality: String, status: String, source: String,
                         confidence: String?, overlapRatio: Double?, remark: String?, reviewed: Boolean): Long =
        conn.prepareStatement(
            "INSERT INTO table_relation(datasource_id, db_name, schema_name, one_table, one_column, many_table, many_column, " +
                    "cardinality, status, source, confidence, overlap_ratio, remark, reviewed, reviewed_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
            ps.setString(8, cardinality); ps.setString(9, status); ps.setString(10, source)
            ps.setString(11, confidence)
            if (overlapRatio != null) ps.setDouble(12, overlapRatio) else ps.setNull(12, Types.DOUBLE)
            ps.setString(13, remark)
            ps.setBoolean(14, reviewed)
            if (reviewed) ps.setTimestamp(15, java.sql.Timestamp.valueOf(LocalDateTime.now())) else ps.setNull(15, Types.TIMESTAMP)
            ps.executeUpdate()
            ps.generatedKeys.use { rs ->
                check(rs.next()) { "插入未返回自增主键" }
                rs.getLong(1)
            }
        }

    private fun doInsertOriginal(conn: Connection, datasourceId: Long, dbName: String, schema: String,
                                 oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                                 cardinality: String, status: String, source: String,
                                 confidence: String?, overlapRatio: Double?, remark: String?): Long =
        conn.prepareStatement(
            "INSERT INTO table_relation_original(datasource_id, db_name, schema_name, one_table, one_column, many_table, many_column, " +
                    "cardinality, status, source, confidence, overlap_ratio, remark) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
            ps.setString(8, cardinality); ps.setString(9, status); ps.setString(10, source)
            ps.setString(11, confidence)
            if (overlapRatio != null) ps.setDouble(12, overlapRatio) else ps.setNull(12, Types.DOUBLE)
            ps.setString(13, remark)
            ps.executeUpdate()
            ps.generatedKeys.use { rs ->
                check(rs.next()) { "插入未返回自增主键" }
                rs.getLong(1)
            }
        }

    /** 状态流转(confirm/reject 互转,调用方不做前置状态限制),同时标记人工已审核,返回影响行数(0=记录不存在) */
    fun updateStatus(id: Long, status: String): Int =
        jdbc.update("UPDATE table_relation SET status=?, reviewed=TRUE, reviewed_at=CURRENT_TIMESTAMP, " +
                "updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id)

    /**
     * 批量状态流转(批量确认/否决),同时标记人工已审核,返回实际更新行数(不存在的 id 忽略);
     * ids 非空由 service 层保证,空集合兜底直接返回 0(避免拼出非法 IN ())
     */
    fun updateStatusBatch(ids: Collection<Long>, status: String): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return jdbc.update("UPDATE table_relation SET status=?, reviewed=TRUE, reviewed_at=CURRENT_TIMESTAMP, " +
                "updated_at=CURRENT_TIMESTAMP WHERE id IN ($marks)",
            status, *ids.toTypedArray())
    }

    /**
     * 批量人工审核落库:状态流转 + 逐条备注(remarks 里给了的行才覆盖备注,键为关系 id),
     * 同事务执行,返回实际更新行数(不存在的 id 忽略)。人工填写的否决原因随本次审核一并保存。
     */
    fun reviewBatch(ids: Collection<Long>, status: String, remarks: Map<Long, String>): Int {
        if (ids.isEmpty()) return 0
        return jdbc.tx { conn ->
            var updated = 0
            conn.prepareStatement("UPDATE table_relation SET status=?, remark=?, reviewed=TRUE, " +
                    "reviewed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?").use { withRemark ->
                conn.prepareStatement("UPDATE table_relation SET status=?, reviewed=TRUE, " +
                        "reviewed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?").use { plain ->
                    for (id in ids) {
                        val remark = remarks[id]
                        updated += if (remark != null) {
                            withRemark.setString(1, status); withRemark.setString(2, remark); withRemark.setLong(3, id)
                            withRemark.executeUpdate()
                        } else {
                            plain.setString(1, status); plain.setLong(2, id)
                            plain.executeUpdate()
                        }
                    }
                }
            }
            updated
        }
    }

    /** 批量删除(任意状态,误删可重新推导找回),返回实际删除行数;原始关系快照保留(供「关系变化」体现删除) */
    fun deleteByIds(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return jdbc.update("DELETE FROM table_relation WHERE id IN ($marks)", *ids.toTypedArray())
    }

    /** 删除单条(任意状态,误删可重新推导找回),返回影响行数(0=记录不存在);原始关系快照保留 */
    fun deleteById(id: Long): Int =
        jdbc.update("DELETE FROM table_relation WHERE id=?", id)
}
