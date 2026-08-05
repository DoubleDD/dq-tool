package com.example.dq.repository

/** 库列表页统计缓存(schema_stat 表):首访从业务库拉取落库,之后只读本地,扫描创建时按 schema 刷新 */
class SchemaStatRepository(private val jdbc: Jdbc) {

    /** 缓存行:schema 名 + 表数量 + 数据索引总字节(后两者可空,方言不支持时为 null) */
    data class CachedStat(val schemaName: String, val tableCount: Int?, val sizeBytes: Long?)

    /** dbName 为空匹配 NULL,与 scan_job 的落库口径一致 */
    fun findAll(datasourceId: Long, dbName: String?): List<CachedStat> {
        val sql = "SELECT schema_name, table_count, size_bytes FROM schema_stat WHERE datasource_id=? AND " +
                dbCond(dbName) + " ORDER BY schema_name"
        return jdbc.query(sql, *queryArgs(datasourceId, dbName)) { rs ->
            val count = rs.getInt(2)
            val countNull = rs.wasNull()
            val bytes = rs.getLong(3)
            val bytesNull = rs.wasNull()
            CachedStat(rs.getString(1), if (countNull) null else count, if (bytesNull) null else bytes)
        }
    }

    /** 全量替换某数据源某库的缓存(首次从业务库拉取后整体写入) */
    fun replaceAll(datasourceId: Long, dbName: String?, stats: List<CachedStat>) {
        jdbc.update("DELETE FROM schema_stat WHERE datasource_id=? AND " + dbCond(dbName),
            *queryArgs(datasourceId, dbName))
        for (s in stats) {
            insert(datasourceId, dbName, s)
        }
    }

    /** 单 schema 刷新(扫描创建时调用);缓存未初始化时也直接写入 */
    fun upsert(datasourceId: Long, dbName: String?, stat: CachedStat) {
        jdbc.update("DELETE FROM schema_stat WHERE datasource_id=? AND " + dbCond(dbName) + " AND schema_name=?",
            *queryArgs(datasourceId, dbName, stat.schemaName))
        insert(datasourceId, dbName, stat)
    }

    /** 删除数据源时级联清理 */
    fun deleteByDatasource(datasourceId: Long) {
        jdbc.update("DELETE FROM schema_stat WHERE datasource_id=?", datasourceId)
    }

    private fun insert(datasourceId: Long, dbName: String?, s: CachedStat) {
        jdbc.update("INSERT INTO schema_stat(datasource_id, db_name, schema_name, table_count, size_bytes) " +
                "VALUES (?,?,?,?,?)",
            datasourceId, dbName, s.schemaName, s.tableCount, s.sizeBytes)
    }

    private fun dbCond(dbName: String?): String =
        if (!dbName.isNullOrBlank()) "db_name=?" else "db_name IS NULL"

    /** 按 dbCond 是否带占位符组装参数 */
    private fun queryArgs(datasourceId: Long, dbName: String?, vararg extra: Any?): Array<Any?> {
        val withDb = !dbName.isNullOrBlank()
        val args = arrayOfNulls<Any>((if (withDb) 2 else 1) + extra.size)
        args[0] = datasourceId
        if (withDb) {
            args[1] = dbName
        }
        extra.copyInto(args, if (withDb) 2 else 1)
        return args
    }
}
