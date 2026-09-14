package com.example.dq.repository

import java.sql.ResultSet
import java.sql.Statement
import java.time.LocalDateTime

/**
 * 对象管理(数据目录):object_dir / object_table / object_table_rel 三表的本地 H2 CRUD(只读写本地库,不连业务库)。
 * 目录按 datasource_id 隔离;挂载/关系表经 dir 归属数据源(object_table/object_table_rel 不冗余存 datasource_id,
 * 按数据源过滤时 JOIN object_dir)。
 */
class ObjectCatalogRepository(private val jdbc: Jdbc) {

    /** 目录行;sortOrder=同级手工排序序号(越小越靠前,新建取同级 max+1 追加末尾,故初值顺序=创建时间顺序) */
    data class DirRow(val id: Long, val datasourceId: Long, val parentId: Long, val name: String,
                      val sortOrder: Int, val createdAt: LocalDateTime?, val updatedAt: LocalDateTime?)

    /** 挂载表行;relKind=与目录的关系类型(INCLUDE/ASSOC,null=未指定) */
    data class TableRow(val id: Long, val dirId: Long, val dbName: String, val schemaName: String,
                        val tableName: String, val remark: String?, val relKind: String?, val createdAt: LocalDateTime?)

    /** 关系表行;relKind=与挂载表的关系类型(INCLUDE/ASSOC,null=未指定) */
    data class RelRow(val id: Long, val objectTableId: Long, val dbName: String, val schemaName: String,
                      val tableName: String, val remark: String?, val relKind: String?, val createdAt: LocalDateTime?)

    // ---------- 目录 ----------

    /**
     * 某数据源全部目录(组树用,量小直接拉回内存)。
     * 按同级手工排序序号升序,同级序号相同回退 id(创建顺序);子节点在各自父节点下按此顺序排列
     */
    fun listDirs(datasourceId: Long): List<DirRow> =
        jdbc.query("SELECT id, datasource_id, parent_id, name, sort_order, created_at, updated_at " +
                "FROM object_dir WHERE datasource_id=? ORDER BY sort_order, id", datasourceId) { rs -> mapDir(rs) }

    fun findDir(id: Long): DirRow? =
        jdbc.queryOne("SELECT id, datasource_id, parent_id, name, sort_order, created_at, updated_at " +
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

    /**
     * 新建目录:sortOrder 为空时取同级(同数据源同父目录)当前最大序号 +1,即追加到同级末尾
     * (默认顺序 = 创建时间顺序);元数据导入保序时显式传 sortOrder。
     */
    fun insertDir(datasourceId: Long, parentId: Long, name: String, sortOrder: Int? = null): Long =
        jdbc.tx { conn ->
            val order = sortOrder ?: conn.prepareStatement(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM object_dir WHERE datasource_id=? AND parent_id=?",
            ).use { ps ->
                ps.setLong(1, datasourceId)
                ps.setLong(2, parentId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            conn.prepareStatement(
                "INSERT INTO object_dir(datasource_id, parent_id, name, sort_order) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, datasourceId)
                ps.setLong(2, parentId)
                ps.setString(3, name)
                ps.setInt(4, order)
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    check(rs.next()) { "插入未返回自增主键" }
                    rs.getLong(1)
                }
            }
        }

    /**
     * 同级目录整体重排:按 orderedIds 的顺序把 sort_order 连续写为 0..n-1;
     * 调用方已校验 id 均属同一父级,并已把未列出的同级目录追加在末尾。
     */
    fun reorderDirs(orderedIds: List<Long>) {
        if (orderedIds.isEmpty()) return
        jdbc.tx { conn ->
            conn.prepareStatement("UPDATE object_dir SET sort_order=?, updated_at=CURRENT_TIMESTAMP WHERE id=?").use { ps ->
                orderedIds.forEachIndexed { i, id ->
                    ps.setInt(1, i)
                    ps.setLong(2, id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

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

    /**
     * 某数据源全部挂载记录(JOIN 目录按数据源过滤)。
     * 按挂载时间升序,同一时刻(批量挂载)回退 id(插入顺序),与目录图/列表页的展示顺序一致
     */
    fun listTables(datasourceId: Long): List<TableRow> =
        jdbc.query("SELECT t.id, t.dir_id, t.db_name, t.schema_name, t.table_name, t.remark, t.rel_kind, t.created_at " +
                "FROM object_table t JOIN object_dir d ON d.id = t.dir_id " +
                "WHERE d.datasource_id=? ORDER BY t.created_at, t.id", datasourceId) { rs -> mapTable(rs) }

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

    /** 某数据源全部关系记录(经挂载 JOIN 目录按数据源过滤);按登记时间升序,同刻回退 id(插入顺序) */
    fun listRels(datasourceId: Long): List<RelRow> =
        jdbc.query("SELECT r.id, r.object_table_id, r.db_name, r.schema_name, r.table_name, r.remark, r.rel_kind, r.created_at " +
                "FROM object_table_rel r JOIN object_table t ON t.id = r.object_table_id " +
                "JOIN object_dir d ON d.id = t.dir_id " +
                "WHERE d.datasource_id=? ORDER BY r.created_at, r.id", datasourceId) { rs -> mapRel(rs) }

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
            rs.getInt("sort_order"), ts(rs, "created_at"), ts(rs, "updated_at"))

    private fun mapTable(rs: ResultSet): TableRow =
        TableRow(rs.getLong("id"), rs.getLong("dir_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("table_name"), rs.getString("remark"), rs.getString("rel_kind"), ts(rs, "created_at"))

    private fun mapRel(rs: ResultSet): RelRow =
        RelRow(rs.getLong("id"), rs.getLong("object_table_id"), rs.getString("db_name"), rs.getString("schema_name"),
            rs.getString("table_name"), rs.getString("remark"), rs.getString("rel_kind"), ts(rs, "created_at"))

    private fun ts(rs: ResultSet, col: String): LocalDateTime? = rs.getTimestamp(col)?.toLocalDateTime()
}
