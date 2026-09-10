package com.example.dq.service

import com.example.dq.model.ManualRelationResult
import com.example.dq.model.RelationCardinality
import com.example.dq.model.RelationGraphNode
import com.example.dq.model.RelationGraphView
import com.example.dq.model.RelationSource
import com.example.dq.model.RelationStatus
import com.example.dq.model.TableRelation
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.TableRelationRepository

/**
 * ER 表间关系管理:关系列表(status/table 过滤)、confirm/reject 互转、候选删除、
 * 手动补充(source=MANUAL 直接 CONFIRMED)、ER 图数据组装(只读本地 H2,不连业务库)
 */
class TableRelationService(
    private val relationRepo: TableRelationRepository,
    private val metaCacheRepo: MetaCacheRepository,
) {

    /** 关系列表:status/table 可选过滤;status 非法值抛 400 */
    fun list(datasourceId: Long, dbName: String?, schemaName: String, status: String?, table: String?): List<TableRelation> {
        val db = dbName?.trim().orEmpty()
        val st = status?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()?.also { validateStatus(it) }
        val t = table?.trim()?.takeIf { it.isNotEmpty() }
        return if (t != null) relationRepo.listByTable(datasourceId, db, schemaName, t, st)
        else relationRepo.listBySchema(datasourceId, db, schemaName, st)
    }

    /** 确认(候选/否决均可转确认) */
    fun confirm(id: Long) {
        if (relationRepo.updateStatus(id, RelationStatus.CONFIRMED.name) == 0) {
            throw IllegalArgumentException("关系不存在: $id")
        }
    }

    /** 否决(候选/确认均可转否决;否决对再推导时不跳过——重新验证,命中则回炉为候选) */
    fun reject(id: Long) {
        if (relationRepo.updateStatus(id, RelationStatus.REJECTED.name) == 0) {
            throw IllegalArgumentException("关系不存在: $id")
        }
    }

    /** 删除:仅候选态;确认/否决的关系只能流转状态,不能删(防误删权威关系) */
    fun delete(id: Long) {
        if (relationRepo.deleteCandidate(id) == 0) {
            throw IllegalArgumentException("仅候选状态的关系可删除: $id")
        }
    }

    /**
     * 手动补充关系:source=MANUAL 直接 CONFIRMED,cardinality 由用户传入;
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
            null, null, remark?.trim()?.takeIf { it.isNotEmpty() })!!
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

    private fun validateStatus(status: String) {
        try {
            RelationStatus.valueOf(status)
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的关系状态: $status(可选 CANDIDATE/CONFIRMED/REJECTED)")
        }
    }
}
