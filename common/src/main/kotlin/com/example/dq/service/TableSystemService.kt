package com.example.dq.service

import com.example.dq.repository.TableSystemRepository

/** 表所属系统:查询(本地 H2)与批量设置/清除;一张表最多归属一个系统,与标记体系独立 */
class TableSystemService(private val repository: TableSystemRepository) {

    /** 批量设置结果计数:updated=设置的表数,cleared=清除的表数(一次调用只会有其一非 0) */
    data class BatchResult(val updated: Int, val cleared: Int)

    /** 表列表页展示用:表名 -> 所属系统,本地查询不连业务库 */
    fun list(datasourceId: Long, database: String?, schema: String): Map<String, String> =
        repository.findBySchema(datasourceId, normalizeDb(database), schema)

    /** 批量设置所属系统;systemName trim 后空白表示清除(返回清除数),否则逐表 upsert(返回设置数) */
    fun batchSet(datasourceId: Long, database: String?, schema: String,
                 tableNames: List<String>, systemName: String?): BatchResult {
        val tables = tableNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val name = systemName?.trim()
        if (name.isNullOrEmpty()) {
            return BatchResult(0, repository.delete(datasourceId, normalizeDb(database), schema, tables))
        }
        val db = normalizeDb(database)
        for (table in tables) {
            repository.upsert(datasourceId, db, schema, table, name)
        }
        return BatchResult(tables.size, 0)
    }

    private companion object {
        /** 无库概念的方言 db 为 null,统一存空串保证唯一键(与 table_doc/table_tag 口径一致) */
        fun normalizeDb(database: String?): String = database ?: ""
    }
}
