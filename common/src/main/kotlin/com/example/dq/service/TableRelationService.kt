package com.example.dq.service

import com.example.dq.model.ManualRelationResult
import com.example.dq.model.RelationAuditView
import com.example.dq.model.RelationCardinality
import com.example.dq.model.RelationChangeField
import com.example.dq.model.RelationChangeType
import com.example.dq.model.RelationGraphNode
import com.example.dq.model.RelationGraphView
import com.example.dq.model.RelationSnapshot
import com.example.dq.model.RelationSource
import com.example.dq.model.RelationStatus
import com.example.dq.model.TableRelation
import com.example.dq.model.TableRelationChange
import com.example.dq.model.TableRelationOriginal
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.TableRelationRepository

/**
 * ER 表间关系管理:关系列表(status/table 过滤)、confirm/reject 互转、候选删除、
 * 手动补充(source=MANUAL 直接 CONFIRMED)、ER 图数据组装(只读本地 H2,不连业务库),
 * 以及「原始关系 → 最终关系」的审核数据组装(导出 3 个 sheet 用)。
 */
class TableRelationService(
    private val relationRepo: TableRelationRepository,
    private val metaCacheRepo: MetaCacheRepository,
) {

    companion object {
        /** 批量操作单次上限(防误传超大 id 列表拖垮本地 H2) */
        private const val MAX_BATCH = 1000

        /** 方向无关的字段对 key:两端「表.字段」按字典序拼接(与推导侧 pairKey 同口径) */
        private fun pairKey(oneTable: String, oneColumn: String, manyTable: String, manyColumn: String): String {
            val x = "$oneTable.$oneColumn"
            val y = "$manyTable.$manyColumn"
            return if (x <= y) "$x|$y" else "$y|$x"
        }
    }

    /** 关系列表:status/table 可选过滤;status 非法值抛 400 */
    fun list(datasourceId: Long, dbName: String?, schemaName: String, status: String?, table: String?): List<TableRelation> {
        val db = dbName?.trim().orEmpty()
        val st = status?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()?.also { validateStatus(it) }
        val t = table?.trim()?.takeIf { it.isNotEmpty() }
        return if (t != null) relationRepo.listByTable(datasourceId, db, schemaName, t, st)
        else relationRepo.listBySchema(datasourceId, db, schemaName, st)
    }

    /** 确认(候选/否决均可转确认);人工审核结论落库后重新推导不再覆盖 */
    fun confirm(id: Long) {
        if (relationRepo.updateStatus(id, RelationStatus.CONFIRMED.name) == 0) {
            throw IllegalArgumentException("关系不存在: $id")
        }
    }

    /** 否决(候选/确认均可转否决);人工审核结论落库后重新推导不再覆盖 */
    fun reject(id: Long) {
        if (relationRepo.updateStatus(id, RelationStatus.REJECTED.name) == 0) {
            throw IllegalArgumentException("关系不存在: $id")
        }
    }

    /** 删除(任意状态;误删的关系重新推导即可找回);原始关系快照保留,导出「关系变化」时体现为人工删除 */
    fun delete(id: Long) {
        if (relationRepo.deleteById(id) == 0) {
            throw IllegalArgumentException("关系不存在: $id")
        }
    }

    /**
     * 批量确认(同单条口径:候选/否决均可转确认),返回实际更新数(不存在的 id 忽略);
     * remarks 为逐条人工备注(键为关系 id 字符串,只有本次修改过的行才带)。
     */
    fun confirmBatch(ids: List<Long>, remarks: Map<String, String>? = null): Int =
        relationRepo.reviewBatch(validateBatchIds(ids), RelationStatus.CONFIRMED.name, parseRemarks(remarks))

    /** 批量否决(同单条口径:候选/确认均可转否决),返回实际更新数;remarks 口径同上 */
    fun rejectBatch(ids: List<Long>, remarks: Map<String, String>? = null): Int =
        relationRepo.reviewBatch(validateBatchIds(ids), RelationStatus.REJECTED.name, parseRemarks(remarks))

    /** 批量删除(任意状态,误删可重新推导找回),返回实际删除数 */
    fun deleteBatch(ids: List<Long>): Int = relationRepo.deleteByIds(validateBatchIds(ids))

    /** 批量入参校验:非空、数量上限、去重(保持原顺序) */
    private fun validateBatchIds(ids: List<Long>): List<Long> {
        require(ids.isNotEmpty()) { "ids 不能为空" }
        require(ids.size <= MAX_BATCH) { "单次最多处理 $MAX_BATCH 条关系" }
        return ids.distinct()
    }

    /** 备注 map 的键是 JSON 对象键(字符串),转成关系 id;非数字键忽略 */
    private fun parseRemarks(remarks: Map<String, String>?): Map<Long, String> =
        remarks.orEmpty().mapNotNull { (k, v) -> k.trim().toLongOrNull()?.let { it to v } }.toMap()

    /**
     * 手动补充关系:source=MANUAL 直接 CONFIRMED(视为人工已审核),cardinality 由用户传入;
     * ONE_TO_ONE/SUSPECT_MANY_TO_MANY 方向无语义,按 (表名,字段名) 字典序归一化;
     * ONE_TO_MANY 方向由用户指定(one 侧=唯一方),原样保留。
     * 命中唯一键的已存在关系:人工判定权威,直接转 CONFIRMED 返回原 id(不重复插入),existing=true
     */
    fun addManual(datasourceId: Long, dbName: String?, schemaName: String,
                  oneTable: String, oneColumn: String, manyTable: String, manyColumn: String,
                  cardinality: String, remark: String?): ManualRelationResult {
        val card = try {
            RelationCardinality.valueOf(cardinality.trim().uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的基数: $cardinality(可选 ONE_TO_ONE/ONE_TO_MANY/SUSPECT_MANY_TO_MANY)")
        }
        val db = dbName?.trim().orEmpty()
        var one = RelationInferService.ColRef(oneTable.trim(), oneColumn.trim())
        var many = RelationInferService.ColRef(manyTable.trim(), manyColumn.trim())
        if (one == many) {
            throw IllegalArgumentException("关系两端不能是同一字段")
        }
        if (card != RelationCardinality.ONE_TO_MANY) {
            val (o, m) = RelationInferService.normalizeDirection(one, many)
            one = o; many = m
        }
        val existing = relationRepo.findByKey(datasourceId, db, schemaName,
            one.table, one.column, many.table, many.column)
        if (existing != null) {
            relationRepo.updateStatus(existing.id, RelationStatus.CONFIRMED.name)
            return ManualRelationResult(existing.id, true)
        }
        val id = relationRepo.insertIfAbsent(datasourceId, db, schemaName,
            one.table, one.column, many.table, many.column,
            card.name, RelationStatus.CONFIRMED.name, RelationSource.MANUAL.name,
            null, null, remark?.trim()?.takeIf { it.isNotEmpty() }, reviewed = true)!!
        return ManualRelationResult(id, false)
    }

    /**
     * ER 图数据组装:
     * table 为空 = 全库总图——CONFIRMED 边(+includeCandidate 时含候选边)+ 库内全部表节点(含孤儿表);
     * table 非空 = 该表星型图——该表参与的 CONFIRMED+CANDIDATE 边(不含 REJECTED),节点为边两端表+锚点表。
     * 节点注释取自 meta_table 缓存;边涉及但缓存缺失的表补无注释节点。
     */
    fun graph(datasourceId: Long, dbName: String?, schemaName: String,
              table: String?, includeCandidate: Boolean): RelationGraphView {
        val db = dbName?.trim().orEmpty()
        val t = table?.trim()?.takeIf { it.isNotEmpty() }
        val edges = if (t == null) {
            val list = ArrayList<TableRelation>()
            list.addAll(relationRepo.listBySchema(datasourceId, db, schemaName, RelationStatus.CONFIRMED.name))
            if (includeCandidate) {
                list.addAll(relationRepo.listBySchema(datasourceId, db, schemaName, RelationStatus.CANDIDATE.name))
            }
            list
        } else {
            val list = ArrayList<TableRelation>()
            list.addAll(relationRepo.listByTable(datasourceId, db, schemaName, t, RelationStatus.CONFIRMED.name))
            list.addAll(relationRepo.listByTable(datasourceId, db, schemaName, t, RelationStatus.CANDIDATE.name))
            list
        }
        val comments = metaCacheRepo.listTables(datasourceId, db, schemaName)
            .associate { it.tableName to it.comment }
        val names = LinkedHashSet<String>()
        if (t == null) {
            // 全库总图:库内全部表节点(含孤儿表)
            names.addAll(comments.keys)
        } else {
            // 星型图:锚点表 + 边两端表
            names.add(t)
        }
        for (e in edges) {
            names.add(e.oneTable)
            names.add(e.manyTable)
        }
        val nodes = names.map { RelationGraphNode(it, comments[it]) }.sortedBy { it.name }
        return RelationGraphView(nodes, edges.sortedBy { it.id })
    }

    /**
     * 审核数据组装(导出 ER 关系 3 个 sheet 用):
     * finals=最终关系,originals=原始关系,changes=两者相差得到的关系变化(含人工新增/删除)。
     * table 非空时三份数据都只保留该表参与的关系(两端任一命中;已删除的关系仍能从原始快照里按表捞到)。
     */
    fun audit(datasourceId: Long, dbName: String?, schemaName: String, table: String?): RelationAuditView {
        val db = dbName?.trim().orEmpty()
        val t = table?.trim()?.takeIf { it.isNotEmpty() }
        val finals = if (t != null) relationRepo.listByTable(datasourceId, db, schemaName, t)
        else relationRepo.listBySchema(datasourceId, db, schemaName)
        val originals = if (t != null) relationRepo.listOriginalsByTable(datasourceId, db, schemaName, t)
        else relationRepo.listOriginalsBySchema(datasourceId, db, schemaName)
        return RelationAuditView(finals, originals, diff(finals, originals))
    }

    /** 原始关系 → 最终关系的差异:同一字段对(方向无关)匹配,剩余原始=人工删除、剩余最终=人工新增 */
    private fun diff(finals: List<TableRelation>, originals: List<TableRelationOriginal>): List<TableRelationChange> {
        val pending = originals.groupBy { pairKey(it.oneTable, it.oneColumn, it.manyTable, it.manyColumn) }
            .mapValues { (_, v) -> v.toMutableList() }
        val changes = ArrayList<TableRelationChange>()
        for (f in finals) {
            val bucket = pending[pairKey(f.oneTable, f.oneColumn, f.manyTable, f.manyColumn)] ?: mutableListOf()
            // 同字段对优先匹配方向一致的快照(方向翻转只是同一关系的另一种存储口径)
            val matched = bucket.firstOrNull {
                it.oneTable == f.oneTable && it.oneColumn == f.oneColumn && it.manyTable == f.manyTable && it.manyColumn == f.manyColumn
            } ?: bucket.firstOrNull()
            if (matched == null) {
                changes.add(TableRelationChange(RelationChangeType.ADDED.name, emptyList(), null, snapshotOf(f)))
                continue
            }
            bucket.remove(matched)
            val fields = changedFields(matched, f)
            if (fields.isNotEmpty()) {
                changes.add(TableRelationChange(RelationChangeType.MODIFIED.name, fields, snapshotOf(matched), snapshotOf(f)))
            }
        }
        for (bucket in pending.values) {
            for (o in bucket) {
                changes.add(TableRelationChange(RelationChangeType.REMOVED.name, emptyList(), snapshotOf(o), null))
            }
        }
        return changes.sortedWith(compareBy({ it.changeType }, { it.after?.oneTable ?: it.before?.oneTable ?: "" }))
    }

    /** 变更项:状态/备注/方向/基数(与模型产出相比) */
    private fun changedFields(o: TableRelationOriginal, f: TableRelation): List<String> {
        val fields = ArrayList<String>(4)
        if (o.status != f.status) fields.add(RelationChangeField.STATUS.name)
        if ((o.remark ?: "") != (f.remark ?: "")) fields.add(RelationChangeField.REMARK.name)
        if (o.oneTable != f.oneTable || o.oneColumn != f.oneColumn ||
            o.manyTable != f.manyTable || o.manyColumn != f.manyColumn) {
            fields.add(RelationChangeField.DIRECTION.name)
        }
        if (o.cardinality != f.cardinality) fields.add(RelationChangeField.CARDINALITY.name)
        return fields
    }

    private fun snapshotOf(r: TableRelation) = RelationSnapshot(
        r.oneTable, r.oneColumn, r.manyTable, r.manyColumn,
        r.cardinality, r.status, r.source, r.confidence, r.overlapRatio, r.remark)

    private fun snapshotOf(r: TableRelationOriginal) = RelationSnapshot(
        r.oneTable, r.oneColumn, r.manyTable, r.manyColumn,
        r.cardinality, r.status, r.source, r.confidence, r.overlapRatio, r.remark)

    private fun validateStatus(status: String) {
        try {
            RelationStatus.valueOf(status)
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的关系状态: $status(可选 CANDIDATE/CONFIRMED/REJECTED)")
        }
    }
}
