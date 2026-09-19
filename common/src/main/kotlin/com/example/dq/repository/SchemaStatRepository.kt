package com.example.dq.repository

import java.sql.Connection

/**
 * 库列表页统计缓存(schema_stat 表):首访从业务库拉取落库,之后只读本地,扫描创建时按 schema 刷新。
 *
 * 写操作与 [MetaCacheRepository] 共用 [MetaWriteQueue](全局单线程队列):schema_stat 属元数据缓存,
 * 覆盖刷新同样是「先 DELETE 后 INSERT」,并发写 H2 会互相等锁/踩唯一键。
 */
class SchemaStatRepository(
    private val jdbc: Jdbc,
    private val writeQueue: MetaWriteQueue = MetaWriteQueue(),
) {

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

    /** 概览缓存里出现过的库名(多库方言断网推导库清单用);NULL 槽位(默认库/单库方言)不算库名 */
    fun listDistinctDbNames(datasourceId: Long): List<String> =
        jdbc.query(
            "SELECT DISTINCT db_name FROM schema_stat WHERE datasource_id=? AND db_name IS NOT NULL ORDER BY db_name",
            datasourceId
        ) { it.getString(1) }

    /** 全量替换某数据源某库的缓存(首次从业务库拉取后整体写入);单事务保证「清空 + 重写」原子 */
    fun replaceAll(datasourceId: Long, dbName: String?, stats: List<CachedStat>) {
        writeQueue.submit {
            jdbc.tx { conn ->
                conn.prepareStatement("DELETE FROM schema_stat WHERE datasource_id=? AND " + dbCond(dbName)).use { ps ->
                    ps.setLong(1, datasourceId)
                    if (!dbName.isNullOrBlank()) ps.setString(2, dbName)
                    ps.executeUpdate()
                }
                for (s in stats) {
                    insert(conn, datasourceId, dbName, s)
                }
            }
        }
    }

    /** 单 schema 刷新(扫描创建时调用);缓存未初始化时也直接写入 */
    fun upsert(datasourceId: Long, dbName: String?, stat: CachedStat) =
        writeQueue.submit { upsertTx(datasourceId, dbName, stat) }

    /** 扫描路径火忘写:不阻塞扫描线程,失败由队列记日志(语义同 [upsert]) */
    fun upsertAsync(datasourceId: Long, dbName: String?, stat: CachedStat) =
        writeQueue.submitAsync { upsertTx(datasourceId, dbName, stat) }

    private fun upsertTx(datasourceId: Long, dbName: String?, stat: CachedStat) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "DELETE FROM schema_stat WHERE datasource_id=? AND " + dbCond(dbName) + " AND schema_name=?"
            ).use { ps ->
                ps.setLong(1, datasourceId)
                if (!dbName.isNullOrBlank()) ps.setString(2, dbName)
                ps.setString(if (dbName.isNullOrBlank()) 2 else 3, stat.schemaName)
                ps.executeUpdate()
            }
            insert(conn, datasourceId, dbName, stat)
        }
    }

    /** 删除数据源时级联清理 */
    fun deleteByDatasource(datasourceId: Long) {
        writeQueue.submit {
            jdbc.update("DELETE FROM schema_stat WHERE datasource_id=?", datasourceId)
        }
    }

    private fun insert(conn: Connection, datasourceId: Long, dbName: String?, s: CachedStat) {
        conn.prepareStatement(
            "INSERT INTO schema_stat(datasource_id, db_name, schema_name, table_count, size_bytes) VALUES (?,?,?,?,?)"
        ).use { ps ->
            // db_name 空白一律落 NULL,与 dbCond 的读取/删除口径一致;原样写 '' 会产生读不到也删不掉的隐形行
            ps.setLong(1, datasourceId); ps.setString(2, dbName?.takeIf { it.isNotBlank() }); ps.setString(3, s.schemaName)
            ps.setObject(4, s.tableCount); ps.setObject(5, s.sizeBytes)
            ps.executeUpdate()
        }
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
