package com.example.dq.repository

import com.example.dq.model.TableRelation
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDateTime

/**
 * ER 表间关系(table_relation)的本地 H2 CRUD(只读写本地库,不连业务库);
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
            ts(rs, "created_at"), ts(rs, "updated_at"))
    }

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()

    /** 某库关系列表;status 非空时按状态过滤(推导排除 REJECTED 对、图组装取 CONFIRMED 均走这里) */
    fun listBySchema(datasourceId: Long, dbName: String, schema: String, status: String? = null): List<TableRelation> =
        if (status == null) {
            jdbc.query("SELECT * FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? ORDER BY id",
                datasourceId, dbName, schema, mapper = mapper)
        } else {
            jdbc.query("SELECT * FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? AND status=? ORDER BY id",
                datasourceId, dbName, schema, status, mapper = mapper)
        }

    /** 某表参与的关系(星型图;两端任一命中);status 非空时按状态过滤 */
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

    /** 已存在(按唯一键)则跳过返回 null,否则插入返回自增 id;不覆盖已有 status/confidence(已确认关系不被新推导降级) */
    fun insertIfAbsent(datasourceId: Long, dbName: String, schema: String,
                       oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                       cardinality: String, status: String, source: String,
                       confidence: String?, overlapRatio: Double?, remark: String?): Long? =
        jdbc.tx { conn ->
            conn.prepareStatement("SELECT id FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                    "AND one_table=? AND one_column=? AND many_table=? AND many_column=?").use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
                ps.executeQuery().use { rs -> if (rs.next()) return@tx null }
            }
            doInsert(conn, datasourceId, dbName, schema, oneTable, oneColumn, manyTable, manyColumn,
                cardinality, status, source, confidence, overlapRatio, remark)
        }

    /**
     * 推导结果入库(仅 RelationInferService 用),返回行 id 或 null:
     * 正向/反向唯一键命中 REJECTED 行 → 回炉为 CANDIDATE:按最新推导方向与验证数据整行刷新(含 one/many 方向翻转),返回原 id;
     * 命中非 REJECTED 行(并发/重跑撞已存在关系)→ 不动,返回 null;
     * 不存在 → 插入 CANDIDATE,返回自增 id
     */
    fun upsertDerived(datasourceId: Long, dbName: String, schema: String,
                      oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                      cardinality: String, source: String,
                      confidence: String?, overlapRatio: Double?, remark: String?): Long? =
        jdbc.tx { conn ->
            var hitId: Long? = null
            var hitRejected = false
            conn.prepareStatement("SELECT id, status FROM table_relation WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                    "AND ((one_table=? AND one_column=? AND many_table=? AND many_column=?) " +
                    "OR (one_table=? AND one_column=? AND many_table=? AND many_column=?))").use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, oneTable); ps.setString(5, oneColumn); ps.setString(6, manyTable); ps.setString(7, manyColumn)
                ps.setString(8, manyTable); ps.setString(9, manyColumn); ps.setString(10, oneTable); ps.setString(11, oneColumn)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        hitId = rs.getLong(1)
                        hitRejected = rs.getString(2) == "REJECTED"
                    }
                }
            }
            val id = hitId ?: return@tx doInsert(conn, datasourceId, dbName, schema,
                oneTable, oneColumn, manyTable, manyColumn,
                cardinality, "CANDIDATE", source, confidence, overlapRatio, remark)
            if (!hitRejected) return@tx null
            conn.prepareStatement("UPDATE table_relation SET one_table=?, one_column=?, many_table=?, many_column=?, " +
                    "cardinality=?, status='CANDIDATE', source=?, confidence=?, overlap_ratio=?, remark=?, " +
                    "updated_at=CURRENT_TIMESTAMP WHERE id=?").use { ps ->
                ps.setString(1, oneTable); ps.setString(2, oneColumn); ps.setString(3, manyTable); ps.setString(4, manyColumn)
                ps.setString(5, cardinality); ps.setString(6, source); ps.setString(7, confidence)
                if (overlapRatio != null) ps.setDouble(8, overlapRatio) else ps.setNull(8, Types.DOUBLE)
                ps.setString(9, remark)
                ps.setLong(10, id)
                ps.executeUpdate()
            }
            id
        }

    private fun doInsert(conn: java.sql.Connection, datasourceId: Long, dbName: String, schema: String,
                         oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                         cardinality: String, status: String, source: String,
                         confidence: String?, overlapRatio: Double?, remark: String?): Long =
        conn.prepareStatement(
            "INSERT INTO table_relation(datasource_id, db_name, schema_name, one_table, one_column, many_table, many_column, " +
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

    /** 状态流转(confirm/reject 互转,调用方不做前置状态限制),返回影响行数(0=记录不存在) */
    fun updateStatus(id: Long, status: String): Int =
        jdbc.update("UPDATE table_relation SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id)

    /**
     * 批量状态流转(批量确认/否决),返回实际更新行数(不存在的 id 忽略);
     * ids 非空由 service 层保证,空集合兜底直接返回 0(避免拼出非法 IN ())
     */
    fun updateStatusBatch(ids: Collection<Long>, status: String): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return jdbc.update("UPDATE table_relation SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id IN ($marks)",
            status, *ids.toTypedArray())
    }

    /** 批量删除(任意状态,误删可重新推导找回),返回实际删除行数 */
    fun deleteByIds(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return jdbc.update("DELETE FROM table_relation WHERE id IN ($marks)", *ids.toTypedArray())
    }

    /** 删除单条(任意状态,误删可重新推导找回),返回影响行数(0=记录不存在) */
    fun deleteById(id: Long): Int =
        jdbc.update("DELETE FROM table_relation WHERE id=?", id)
}
