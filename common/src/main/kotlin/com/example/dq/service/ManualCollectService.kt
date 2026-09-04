package com.example.dq.service

import com.example.dq.model.ManualCollect
import com.example.dq.model.ManualCollectAddResult
import com.example.dq.model.ManualCollectItem
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.ManualCollectRepository

/**
 * 人工采集:把重点关注的表收藏起来(数据源+库+schema+表 四元组唯一)。
 * 异常约定与 WebServer 映射一致:IllegalArgumentException → 400。
 * 批量采集幂等:已存在的四元组跳过计数,不报错(勾选了一批已采集的表时静默去重)。
 */
class ManualCollectService(
    private val collectRepo: ManualCollectRepository,
    private val dataSourceRepo: DataSourceRepository,
) {

    /** 全部采集记录(跨数据源,采集时间倒序),带数据源名 */
    fun list(): List<ManualCollect> = collectRepo.listAll()

    /** 某库下已采集表 map:表名 -> 采集记录 id */
    fun tableCollectMap(datasourceId: Long, database: String?, schema: String): Map<String, Long> =
        collectRepo.tableCollectMap(datasourceId, normalizeDb(database), schema)

    /** 批量采集:逐项校验(数据源存在/schema/表名非空),已存在的跳过;同一数据源只校验一次 */
    fun addBatch(items: List<ManualCollectItem>): ManualCollectAddResult {
        if (items.isEmpty()) {
            throw IllegalArgumentException("采集列表不能为空")
        }
        val checkedDsIds = HashSet<Long>()
        var added = 0
        var skipped = 0
        for (item in items) {
            val schema = item.schemaName?.trim()
            val table = item.tableName?.trim()
            if (schema.isNullOrEmpty() || table.isNullOrEmpty()) {
                throw IllegalArgumentException("库名与表名不能为空")
            }
            if (checkedDsIds.add(item.datasourceId) && dataSourceRepo.findById(item.datasourceId) == null) {
                throw IllegalArgumentException("数据源不存在:${item.datasourceId}")
            }
            val db = normalizeDb(item.dbName)
            if (collectRepo.exists(item.datasourceId, db, schema, table)) {
                skipped++
            } else {
                val comment = item.tableComment?.trim()?.takeIf { it.isNotEmpty() }?.take(1024)
                collectRepo.insert(item.datasourceId, db, schema, table, comment)
                added++
            }
        }
        return ManualCollectAddResult(added, skipped)
    }

    /** 取消采集;记录不存在 400 */
    fun delete(id: Long) {
        if (collectRepo.delete(id) == 0) {
            throw IllegalArgumentException("采集记录不存在:$id")
        }
    }

    private companion object {

        /** 无库概念的方言 db 为 null,统一存空串保证唯一键(与 table_tag 口径一致) */
        fun normalizeDb(database: String?): String = database ?: ""
    }
}
