package com.example.dq.repository

import com.example.dq.model.ManualCollect
import java.sql.ResultSet
import java.time.LocalDateTime

/** 人工采集:收藏清单的本地 H2 CRUD(只读写本地库,不连业务库) */
class ManualCollectRepository(private val jdbc: Jdbc) {

    /** 全部采集记录(跨数据源),带数据源名;按采集时间倒序 */
    fun listAll(): List<ManualCollect> =
        jdbc.query("SELECT mc.id, mc.datasource_id, COALESCE(ds.name, '') AS ds_name, " +
                "mc.db_name, mc.schema_name, mc.table_name, mc.table_comment, mc.created_at " +
                "FROM manual_collect mc LEFT JOIN data_source ds ON ds.id = mc.datasource_id " +
                "ORDER BY mc.created_at DESC, mc.id DESC") { rs -> map(rs) }

    /** 某库下已采集表 map:表名 -> 采集记录 id(表列表页行内「采集/已采集」状态用) */
    fun tableCollectMap(datasourceId: Long, dbName: String, schema: String): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        jdbc.query("SELECT table_name, id FROM manual_collect " +
                "WHERE datasource_id=? AND db_name=? AND schema_name=? ORDER BY table_name",
            datasourceId, dbName, schema) { rs ->
            result[rs.getString("table_name")] = rs.getLong("id")
        }
        return result
    }

    fun exists(datasourceId: Long, dbName: String, schema: String, table: String): Boolean =
        jdbc.queryOne("SELECT COUNT(*) FROM manual_collect " +
                "WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?",
            datasourceId, dbName, schema, table) { rs -> rs.getLong(1) }!! > 0

    fun insert(datasourceId: Long, dbName: String, schema: String, table: String, tableComment: String?): Long =
        jdbc.insert("INSERT INTO manual_collect(datasource_id, db_name, schema_name, table_name, table_comment) " +
                "VALUES (?,?,?,?,?)", datasourceId, dbName, schema, table, tableComment)

    /** 返回影响行数,0 表示记录不存在(service 层转 400) */
    fun delete(id: Long): Int =
        jdbc.update("DELETE FROM manual_collect WHERE id=?", id)

    private fun map(rs: ResultSet): ManualCollect =
        ManualCollect(rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("ds_name"),
            rs.getString("db_name"), rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("table_comment"), ts(rs, "created_at"))

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()
}
