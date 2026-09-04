package com.example.dq.repository

import com.example.dq.model.ManualCollect
import com.example.dq.model.Tag
import com.example.dq.model.TagKind
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

    /** 各采集记录对应表的标记:采集记录 id -> 标记列表(按四元组关联 table_tag,含系统空表标记) */
    fun tagsByCollectId(): Map<Long, List<Tag>> {
        val result = LinkedHashMap<Long, MutableList<Tag>>()
        jdbc.query("SELECT mc.id AS mc_id, d.id, d.name, d.color, d.kind, d.description FROM manual_collect mc " +
                "JOIN table_tag tt ON tt.datasource_id=mc.datasource_id AND tt.db_name=mc.db_name " +
                "AND tt.schema_name=mc.schema_name AND tt.table_name=mc.table_name " +
                "JOIN tag_def d ON d.id = tt.tag_id ORDER BY mc.id, d.id") { rs ->
            result.getOrPut(rs.getLong("mc_id")) { ArrayList() }
                .add(Tag(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                    TagKind.valueOf(rs.getString("kind")), rs.getString("description")))
        }
        return result
    }

    /** 各采集记录对应表的 AI 表说明:采集记录 id -> 描述文字(按四元组关联 table_doc) */
    fun descriptionByCollectId(): Map<Long, String> {
        val result = HashMap<Long, String>()
        jdbc.query("SELECT mc.id AS mc_id, td.description FROM manual_collect mc " +
                "JOIN table_doc td ON td.datasource_id=mc.datasource_id AND td.db_name=mc.db_name " +
                "AND td.schema_name=mc.schema_name AND td.table_name=mc.table_name") { rs ->
            result[rs.getLong("mc_id")] = rs.getString("description")
        }
        return result
    }

    private fun map(rs: ResultSet): ManualCollect =
        ManualCollect(rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("ds_name"),
            rs.getString("db_name"), rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("table_comment"), ts(rs, "created_at"))

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()
}
