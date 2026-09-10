package com.example.dq.service

import com.example.dq.model.ObjectBatchResult
import com.example.dq.model.ObjectDirDeleteResult
import com.example.dq.model.ObjectDirNode
import com.example.dq.model.ObjectMountResult
import com.example.dq.model.ObjectTableMountItem
import com.example.dq.model.ObjectTableRelView
import com.example.dq.model.ObjectTableView
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ObjectCatalogRepository

/**
 * 对象管理(数据目录):按数据源隔离的目录树,目录下挂载业务表(四元组定位),挂载表可登记关系表。
 * 异常约定与 WebServer 映射一致:IllegalArgumentException → 400。
 * 挂载与登记关系幂等:命中唯一键返回已存在记录 id(existing=true),不报错。
 */
class ObjectCatalogService(
    private val catalogRepo: ObjectCatalogRepository,
    private val dataSourceRepo: DataSourceRepository,
    private val metaCacheRepo: MetaCacheRepository,
) {

    /**
     * 整棵目录树:根为虚拟节点(id=0,name 空串,tables 恒空),真实顶层目录在其 children 下。
     * 目录按 name 排序;挂载表/关系表的 comment 从 meta_table 缓存按四元组批量补齐(缓存未覆盖给空串)。
     */
    fun loadTree(datasourceId: Long): ObjectDirNode {
        if (dataSourceRepo.findById(datasourceId) == null) {
            throw IllegalArgumentException("数据源不存在:$datasourceId")
        }
        val dirs = catalogRepo.listDirs(datasourceId)
        val tables = catalogRepo.listTables(datasourceId)
        val rels = catalogRepo.listRels(datasourceId)

        // 表注释:按 (db, schema) 分组批量查 meta_table 缓存,四元组匹配
        val comments = HashMap<String, String>()
        val tuples = (tables.map { Triple(it.dbName, it.schemaName, it.tableName) } +
                rels.map { Triple(it.dbName, it.schemaName, it.tableName) }).distinct()
        tuples.groupBy({ it.first }, { it.second to it.third }).forEach { (db, schemaTables) ->
            schemaTables.groupBy({ it.first }, { it.second }).forEach { (schema, _) ->
                metaCacheRepo.listTables(datasourceId, db, schema).forEach { ct ->
                    if (ct.comment != null) comments["$db\u0000$schema\u0000${ct.tableName}"] = ct.comment
                }
            }
        }

        val relsByTable = rels.groupBy({ it.objectTableId }) { r ->
            ObjectTableRelView(r.id, r.dbName, r.schemaName, r.tableName,
                comments["${r.dbName}\u0000${r.schemaName}\u0000${r.tableName}"] ?: "", r.remark, r.relKind)
        }
        val tablesByDir = tables.groupBy({ it.dirId }) { t ->
            ObjectTableView(t.id, t.dirId, t.dbName, t.schemaName, t.tableName,
                comments["${t.dbName}\u0000${t.schemaName}\u0000${t.tableName}"] ?: "", t.remark, t.relKind,
                relsByTable[t.id] ?: emptyList())
        }

        // 内存组树:先建全部节点,再按 parentId 挂到父节点;parent 指向不存在的目录视为顶层(防御脏数据)
        val nodes = dirs.associate { it.id to MutableNode(it) }
        val roots = ArrayList<MutableNode>()
        for (dir in dirs) {
            val node = nodes.getValue(dir.id)
            val parent = nodes[dir.parentId]
            if (dir.parentId != 0L && parent != null) parent.children.add(node) else roots.add(node)
        }
        fun MutableNode.build(): ObjectDirNode =
            ObjectDirNode(row.id, row.datasourceId, row.parentId, row.name,
                children.map { it.build() }, tablesByDir[row.id] ?: emptyList())
        return ObjectDirNode(0, datasourceId, 0, "", roots.map { it.build() }, emptyList())
    }

    /** 新建目录:同级重名 400;parentId 为空(或 0)=根下的顶层目录,>0 时父目录必须存在且同属该数据源 */
    fun createDir(datasourceId: Long, parentId: Long?, name: String): Long {
        if (dataSourceRepo.findById(datasourceId) == null) {
            throw IllegalArgumentException("数据源不存在:$datasourceId")
        }
        val trimmed = name.trim()
        // 请求端 root 目录用 null 表达(根是虚拟节点不落库,parent_id=0 表示根下顶层目录)
        val pid = parentId ?: 0L
        if (pid > 0) {
            val parent = catalogRepo.findDir(pid)
            if (parent == null || parent.datasourceId != datasourceId) {
                throw IllegalArgumentException("父目录不存在:$pid")
            }
        }
        if (catalogRepo.existsSiblingDir(datasourceId, pid, trimmed)) {
            throw IllegalArgumentException("同级目录已存在:$trimmed")
        }
        return catalogRepo.insertDir(datasourceId, pid, trimmed)
    }

    /** 重命名目录:目录不存在 400,同级重名 400 */
    fun renameDir(id: Long, name: String) {
        val dir = catalogRepo.findDir(id) ?: throw IllegalArgumentException("目录不存在:$id")
        val trimmed = name.trim()
        if (trimmed == dir.name) {
            return
        }
        if (catalogRepo.existsSiblingDir(dir.datasourceId, dir.parentId, trimmed, excludeId = id)) {
            throw IllegalArgumentException("同级目录已存在:$trimmed")
        }
        catalogRepo.renameDir(id, trimmed)
    }

    /** 删除目录:级联删除全部子孙目录及其挂载/关系记录,返回级联统计 */
    fun deleteDir(id: Long): ObjectDirDeleteResult {
        val dir = catalogRepo.findDir(id) ?: throw IllegalArgumentException("目录不存在:$id")
        // 目录量小,拉回内存按 parentId 收集子孙
        val all = catalogRepo.listDirs(dir.datasourceId)
        val byParent = all.groupBy { it.parentId }
        val ids = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            ids.add(cur)
            byParent[cur]?.forEach { queue.add(it.id) }
        }
        val (tables, rels) = catalogRepo.deleteDirCascade(ids)
        return ObjectDirDeleteResult(ids.size, tables, rels)
    }

    /**
     * 挂载表到目录:目录不存在 400;同目录同四元组重复挂载幂等返回已存在记录;
     * relKind 为挂载表与目录的关系类型(INCLUDE=包含 / ASSOC=关联,null=未指定),
     * 重复挂载时 relKind 非空且与现值不同则更新(提供修正入口),空不覆盖现值
     */
    fun mountTable(dirId: Long, dbName: String?, schemaName: String, tableName: String, remark: String?,
                   relKind: String? = null): ObjectMountResult {
        catalogRepo.findDir(dirId) ?: throw IllegalArgumentException("目录不存在:$dirId")
        val db = normalizeDb(dbName)
        val schema = schemaName.trim()
        val table = tableName.trim()
        val kind = normalizeRelKind(relKind)
        catalogRepo.findTable(dirId, db, schema, table)?.let {
            if (kind != null && kind != it.relKind) catalogRepo.updateRelKind(it.id, kind)
            return ObjectMountResult(it.id, true)
        }
        val id = catalogRepo.insertTable(dirId, db, schema, table, remark?.trim()?.takeIf { it.isNotEmpty() }, kind)
        return ObjectMountResult(id, false)
    }

    /** 批量挂载:逐个走单张挂载逻辑(幂等),返回新插/跳过统计 */
    fun mountTables(dirId: Long, dbName: String?, schemaName: String, items: List<ObjectTableMountItem>, remark: String?): ObjectBatchResult {
        var mounted = 0
        var existing = 0
        items.distinctBy { it.tableName }.forEach {
            if (mountTable(dirId, dbName, schemaName, it.tableName!!, remark, it.relKind).existing) existing++ else mounted++
        }
        return ObjectBatchResult(mounted, existing)
    }

    /** 取消挂载:级联删除其关系记录;挂载不存在 400 */
    fun unmount(id: Long) {
        catalogRepo.findTableById(id) ?: throw IllegalArgumentException("挂载记录不存在:$id")
        catalogRepo.deleteTableWithRels(id)
    }

    /** 登记关系表:挂载记录不存在 400;不允许指向挂载表自身(同四元组);重复登记幂等返回已存在记录;
     * relKind 口径同挂载——重复登记时非空且与现值不同则更新,空不覆盖现值 */
    fun addRelation(objectTableId: Long, dbName: String?, schemaName: String, tableName: String, remark: String?,
                    relKind: String? = null): ObjectMountResult {
        val mount = catalogRepo.findTableById(objectTableId)
            ?: throw IllegalArgumentException("挂载记录不存在:$objectTableId")
        val db = normalizeDb(dbName)
        val schema = schemaName.trim()
        val table = tableName.trim()
        if (db == mount.dbName && schema == mount.schemaName && table == mount.tableName) {
            throw IllegalArgumentException("关系表不能指向挂载表自身")
        }
        val kind = normalizeRelKind(relKind)
        catalogRepo.findRel(objectTableId, db, schema, table)?.let {
            if (kind != null && kind != it.relKind) catalogRepo.updateRelRelKind(it.id, kind)
            return ObjectMountResult(it.id, true)
        }
        val id = catalogRepo.insertRel(objectTableId, db, schema, table, remark?.trim()?.takeIf { it.isNotEmpty() }, kind)
        return ObjectMountResult(id, false)
    }

    /** 移除关系表;记录不存在 400 */
    fun removeRelation(id: Long) {
        if (catalogRepo.deleteRel(id) == 0) {
            throw IllegalArgumentException("关系记录不存在:$id")
        }
    }

    /** 批量登记关系表:逐个走单张逻辑(幂等、禁指向自身),逐表可选关系类型 relKind,返回新插/跳过统计 */
    fun addRelations(objectTableId: Long, dbName: String?, schemaName: String, items: List<ObjectTableMountItem>, remark: String?): ObjectBatchResult {
        var mounted = 0
        var existing = 0
        items.distinctBy { it.tableName }.forEach {
            if (addRelation(objectTableId, dbName, schemaName, it.tableName!!, remark, it.relKind).existing) existing++ else mounted++
        }
        return ObjectBatchResult(mounted, existing)
    }

    /** 组树用的可变中间节点 */
    private class MutableNode(val row: ObjectCatalogRepository.DirRow) {
        val children = ArrayList<MutableNode>()
    }

    private companion object {

        /** 合法关系类型:INCLUDE=包含 / ASSOC=关联 */
        val REL_KINDS = setOf("INCLUDE", "ASSOC")

        /** 无库概念的方言 db 为 null,统一存空串保证唯一键(与 manual_collect 口径一致) */
        fun normalizeDb(database: String?): String = database ?: ""

        /** 关系类型归一:空白=null;非法值 400 */
        fun normalizeRelKind(relKind: String?): String? {
            val k = relKind?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: return null
            if (k !in REL_KINDS) throw IllegalArgumentException("关系类型仅支持 INCLUDE(包含)/ASSOC(关联):$relKind")
            return k
        }
    }
}
