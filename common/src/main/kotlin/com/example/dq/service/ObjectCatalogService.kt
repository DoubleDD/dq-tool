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
     * 排序口径(决定左树、列表页与目录图同层节点的先后):目录按同级手工排序序号(默认顺序 = 创建时间顺序,
     * 经重排接口改写),挂载表按挂载时间、关系表按登记时间(同刻回退 id);
     * 挂载表/关系表的 comment 从 meta_table 缓存按四元组批量补齐(缓存未覆盖给空串)。
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

    /**
     * 移动目录到其它目录下(变更所属目录):目标父目录必须存在且同属该数据源,不能是自身或其子孙
     * (从目标父目录沿祖先链上溯查环),新同级下重名 400;移动到原父目录(含根)为 no-op;
     * 落库追加为新同级末尾,之后可经同级重排再调整顺序
     */
    fun moveDir(id: Long, parentId: Long?) {
        val dir = catalogRepo.findDir(id) ?: throw IllegalArgumentException("目录不存在:$id")
        val pid = parentId ?: 0L
        if (pid == id) throw IllegalArgumentException("不能移动到自身目录下")
        if (pid > 0) {
            val parent = catalogRepo.findDir(pid)
                ?: throw IllegalArgumentException("目标父目录不存在:$pid")
            if (parent.datasourceId != dir.datasourceId) {
                throw IllegalArgumentException("目标父目录不属于该数据源:$pid")
            }
            var cursor: ObjectCatalogRepository.DirRow? = parent
            while (cursor != null) {
                if (cursor.id == id) throw IllegalArgumentException("不能移动到自身或其子目录下")
                cursor = if (cursor.parentId == 0L) null else catalogRepo.findDir(cursor.parentId)
            }
        }
        if (pid == dir.parentId) return
        if (catalogRepo.existsSiblingDir(dir.datasourceId, pid, dir.name, excludeId = id)) {
            throw IllegalArgumentException("目标目录下已存在同名目录:${dir.name}")
        }
        catalogRepo.moveDir(id, pid, dir.datasourceId)
    }

    /** 移动挂载表到其它目录(变更所属目录):目标目录必须存在且与挂载表同属一个数据源;目标已挂载同四元组表 400;同目录 no-op */
    fun moveTable(id: Long, dirId: Long) {
        val table = catalogRepo.findTableById(id) ?: throw IllegalArgumentException("挂载记录不存在:$id")
        if (table.dirId == dirId) return
        val target = catalogRepo.findDir(dirId) ?: throw IllegalArgumentException("目标目录不存在:$dirId")
        val curDir = catalogRepo.findDir(table.dirId)
            ?: throw IllegalArgumentException("挂载记录所在目录不存在:${table.dirId}")
        if (target.datasourceId != curDir.datasourceId) {
            throw IllegalArgumentException("目标目录不属于该数据源:$dirId")
        }
        if (catalogRepo.findTable(dirId, table.dbName, table.schemaName, table.tableName) != null) {
            throw IllegalArgumentException("目标目录已挂载该表:${table.tableName}")
        }
        catalogRepo.moveTable(id, dirId)
    }

    /** 移动关系表到其它挂载表下(变更所属挂载表):目标挂载表必须存在且与关系表同属一个数据源(经各自目录比对);
     * 不能指向目标挂载表自身(同四元组,与登记口径一致),目标已登记同四元组关系 400;同挂载表 no-op */
    fun moveRelation(id: Long, objectTableId: Long) {
        val rel = catalogRepo.findRelById(id) ?: throw IllegalArgumentException("关系记录不存在:$id")
        if (rel.objectTableId == objectTableId) return
        val target = catalogRepo.findTableById(objectTableId)
            ?: throw IllegalArgumentException("目标挂载表不存在:$objectTableId")
        val curMount = catalogRepo.findTableById(rel.objectTableId)
            ?: throw IllegalArgumentException("关系记录所属挂载表不存在:${rel.objectTableId}")
        val curDir = catalogRepo.findDir(curMount.dirId)
        val targetDir = catalogRepo.findDir(target.dirId)
        if (curDir?.datasourceId != targetDir?.datasourceId) {
            throw IllegalArgumentException("目标挂载表与关系表不属于同一数据源")
        }
        if (rel.dbName == target.dbName && rel.schemaName == target.schemaName && rel.tableName == target.tableName) {
            throw IllegalArgumentException("关系表不能指向挂载表自身")
        }
        if (catalogRepo.findRel(objectTableId, rel.dbName, rel.schemaName, rel.tableName) != null) {
            throw IllegalArgumentException("目标挂载表已登记该关系表:${rel.tableName}")
        }
        catalogRepo.moveRel(id, objectTableId)
    }

    /**
     * 同级目录重排(页面拖动排序):orderedIds 为该数据源同一父目录下同级目录按期望顺序排列的 id 列表。
     * 列表内不允许重复,且必须全部属于该父级(否则 400);未列出的同级目录按当前顺序追加在末尾
     * (并发新增兜底),最终把同级 sort_order 连续重写为 0..n-1。
     */
    fun reorderDirs(datasourceId: Long, parentId: Long?, orderedIds: List<Long>) {
        if (dataSourceRepo.findById(datasourceId) == null) {
            throw IllegalArgumentException("数据源不存在:$datasourceId")
        }
        if (orderedIds.size != orderedIds.distinct().size) {
            throw IllegalArgumentException("排序列表存在重复目录:$orderedIds")
        }
        // 根是虚拟节点:id 用 0 表达(与 createDir 口径一致)
        val pid = parentId ?: 0L
        val siblings = catalogRepo.listDirs(datasourceId).filter { it.parentId == pid }
        val siblingIds = siblings.map { it.id }.toSet()
        val unknown = orderedIds.filter { it !in siblingIds }
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("目录不属于该父级:$unknown")
        }
        if (siblings.isEmpty()) {
            return
        }
        val listed = orderedIds.toSet()
        catalogRepo.reorderDirs(orderedIds + siblings.map { it.id }.filter { it !in listed })
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
