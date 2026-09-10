package com.example.dq.repository

import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * 对象管理(数据目录):object_dir / object_table / object_table_rel 三表的本地 H2 CRUD(只读写本地库,不连业务库)。
 * 目录按 datasource_id 隔离;挂载/关系表经 dir 归属数据源(object_table/object_table_rel 不冗余存 datasource_id,
 * 按数据源过滤时 JOIN object_dir)。
 */
class ObjectCatalogRepository(private val jdbc: Jdbc) {

    /** 目录行 */
    data class DirRow(val id: Long, val datasourceId: Long, val parentId: Long, val name: String,
                      val createdAt: LocalDateTime?, val updatedAt: LocalDateTime?)

    /** 挂载表行;relKind=与目录的关系类型(INCLUDE/ASSOC,null=未指定) */
    data class TableRow(val id: Long, val dirId: Long, val dbName: String, val schemaName: String,
                        val tableName: String, val remark: String?, val relKind: String?, val createdAt: LocalDateTime?)

    /** 关系表行;relKind=与挂载表的关系类型(INCLUDE/ASSOC,null=未指定) */
    data class RelRow(val id: Long, val objectTableId: Long, val dbName: String, val schemaName: String,
                      val tableName: String, val remark: String?, val relKind: String?, val createdAt: LocalDateTime?)

    // ---------- 目录 ----------

    /** 某数据源全部目录(组树用,量小直接拉回内存) */
    fun listDirs(datasourceId: Long): List<DirRow> =
        jdbc.query("SELECT id, datasource_id, parent_id, name, created_at, updated_at " +
                "FROM object_dir WHERE datasource_id=? ORDER BY name, id", datasourceId) { rs -> mapDir(rs) }

    fun findDir(id: Long): DirRow? =
        jdbc.queryOne("SELECT id, datasource_id, parent_id, name, created_at, updated_at " +
                "FROM object_dir WHERE id=?", id) { rs -> mapDir(rs) }

    /** 同级(同数据源同父目录)是否已存在同名目录;excludeId 用于重命名时排除自己 */
    fun existsSiblingDir(datasourceId: Long, parentId: Long, name: String, excludeId: Long? = null): Boolean =
        if (excludeId == null) {
            jdbc.queryOne("SELECT COUNT(*) FROM object_dir WHERE datasource_id=? AND parent_id=? AND name=?",
                datasourceId, parentId, name) { rs -> rs.getLong(1) }!! > 0
        } else {
            jdbc.queryOne("SELECT COUNT(*) FROM object_dir WHERE datasource_id=? AND parent_id=? AND name=? AND id<>?",
                datasourceId, parentId, name, excludeId) { rs -> rs.getLong(1) }!! > 0
        }

    fun insertDir(datasourceId: Long, parentId: Long, name: String): Long =
        jdbc.insert("INSERT INTO object_dir(datasource_id, parent_id, name) VALUES (?,?,?)",
            datasourceId, parentId, name)

    /** 返回影响行数,0 表示目录不存在 */
    fun renameDir(id: Long, name: String): Int =
        jdbc.update("UPDATE object_dir SET name=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", name, id)

    /**
     * 级联删除一组目录(调用方已收集好含自身的全部子孙 id):tx 内按依赖序删 关系→挂载→目录。
     * 返回 [IntArray] [删除挂载数, 删除关系数];目录删除数恒等于入参个数(目录已确认存在)。
     */
    fun deleteDirCascade(dirIds: List<Long>): IntArray {
        require(dirIds.isNotEmpty()) { "目录 id 列表不能为空" }
        val dirPh = dirIds.joinToString(",") { "?" }
        return jdbc.tx { conn ->
            val tableIds = ArrayList<Long>()
            conn.prepareStatement("SELECT id FROM object_table WHERE dir_id IN ($dirPh)").use { ps ->
                dirIds.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
                ps.executeQuery().use { rs -> while (rs.next()) tableIds.add(rs.getLong(1)) }
            }
            var rels = 0
            if (tableIds.isNotEmpty()) {
                val tblPh = tableIds.joinToString(",") { "?" }
                conn.prepareStatement("DELETE FROM object_table_rel WHERE object_table_id IN ($tblPh)").use { ps ->
                    tableIds.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
                    rels = ps.executeUpdate()
                }
            }
            val tables = conn.prepareStatement("DELETE FROM object_table WHERE dir_id IN ($dirPh)").use { ps ->
                dirIds.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM object_dir WHERE id IN ($dirPh)").use { ps ->
                dirIds.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
                ps.executeUpdate()
            }
            intArrayOf(tables, rels)
        }
    }

    // ---------- 挂载表 ----------

    /** 某数据源全部挂载记录(JOIN 目录按数据源过滤) */
    fun listTables(datasourceId: Long): List<TableRow> =
        jdbc.query("SELECT t.id, t.dir_id, t.db_name, t.schema_name, t.table_name, t.remark, t.rel_kind, t.created_at " +
                "FROM object_table t JOIN object_dir d ON d.id = t.dir_id " +
                "WHERE d.datasource_id=? ORDER BY t.table_name, t.id", datasourceId) { rs -> mapTable(rs) }

    fun findTable(dirId: Long, dbName: String, schema: String, table: String): TableRow? =
        jdbc.queryOne("SELECT id, dir_id, db_name, schema_name, table_name, remark, rel_kind, created_at " +
                "FROM object_table WHERE dir_id=? AND db_name=? AND schema_name=? AND table_name=?",
            dirId, dbName, schema, table) { rs -> mapTable(rs) }

    fun findTableById(id: Long): TableRow? =
        jdbc.queryOne("SELECT id, dir_id, db_name, schema_name, table_name, remark, rel_kind, created_at " +
                "FROM object_table WHERE id=?", id) { rs -> mapTable(rs) }

    fun insertTable(dirId: Long, dbName: String, schema: String, table: String, remark: String?, relKind: String?): Long =
        jdbc.insert("INSERT INTO object_table(dir_id, db_name, schema_name, table_name, remark, rel_kind) VALUES (?,?,?,?,?,?)",
            dirId, dbName, schema, table, remark, relKind)

    /** 更新挂载表的关系类型(重复挂载时修正用) */
    fun updateRelKind(id: Long, relKind: String): Int =
        jdbc.update("UPDATE object_table SET rel_kind=? WHERE id=?", relKind, id)

    /** 取消挂载:tx 内先删其关系记录再删挂载;返回删除的关系数。挂载不存在时抛 IllegalStateException(调用方先校验) */
    fun deleteTableWithRels(id: Long): Int =
        jdbc.tx { conn ->
            val rels = conn.prepareStatement("DELETE FROM object_table_rel WHERE object_table_id=?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
            val tables = conn.prepareStatement("DELETE FROM object_table WHERE id=?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
            check(tables == 1) { "挂载记录不存在:$id" }
            rels
        }

    // ---------- 关系表 ----------

    /** 某数据源全部关系记录(经挂载 JOIN 目录按数据源过滤) */
    fun listRels(datasourceId: Long): List<RelRow> =
        jdbc.query("SELECT r.id, r.object_table_id, r.db_name, r.schema_name, r.table_name, r.remark, r.rel_kind, r.created_at " +
                "FROM object_table_rel r JOIN object_table t ON t.id = r.object_table_id " +
                "JOIN object_dir d ON d.id = t.dir_id " +
                "WHERE d.datasource_id=? ORDER BY r.table_name, r.id", datasourceId) { rs -> mapRel(rs) }

    fun findRel(objectTableId: Long, dbName: String, schema: String, table: String): RelRow? =
        jdbc.queryOne("SELECT id, object_table_id, db_name, schema_name, table_name, remark, rel_kind, created_at " +
                "FROM object_table_rel WHERE object_table_id=? AND db_name=? AND schema_name=? AND table_name=?",
            objectTableId, dbName, schema, table) { rs -> mapRel(rs) }

    fun insertRel(objectTableId: Long, dbName: String, schema: String, table: String, remark: String?, relKind: String?): Long =
        jdbc.insert("INSERT INTO object_table_rel(object_table_id, db_name, schema_name, table_name, remark, rel_kind) " +
                "VALUES (?,?,?,?,?,?)", objectTableId, dbName, schema, table, remark, relKind)

    /** 更新关系表的关系类型(重复登记时修正用) */
    fun updateRelRelKind(id: Long, relKind: String): Int =
        jdbc.update("UPDATE object_table_rel SET rel_kind=? WHERE id=?", relKind, id)

    /** 返回影响行数,0 表示记录不存在(service 层转 400) */
    fun deleteRel(id: Long): Int =
        jdbc.update("DELETE FROM object_table_rel WHERE id=?", id)

    private fun mapDir(rs: ResultSet): DirRow =
        DirRow(rs.getLong("id"), rs.getLong("datasource_id"), rs.getLong("parent_id"), rs.getString("name"),
            ts(rs, "created_at"), ts(rs, "updated_at"))

    private fun mapTable(rs: ResultSet): TableRow =
        TableRow(rs.getLong("id"), rs.getLong("dir_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("table_name"), rs.getString("remark"), rs.getString("rel_kind"), ts(rs, "created_at"))

    private fun mapRel(rs: ResultSet): RelRow =
        RelRow(rs.getLong("id"), rs.getLong("object_table_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("table_name"), rs.getString("remark"), rs.getString("rel_kind"), ts(rs, "created_at"))

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()
}
