package com.example.dq.service

import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.SchemaStat
import com.example.dq.model.TableStat
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaStatRepository
import java.sql.SQLException
import java.time.LocalDateTime

/** 库/表元数据查询(同步、快速路径) */
class MetadataService(
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val scanRepository: ScanRepository,
    private val schemaStatRepo: SchemaStatRepository,
) {

    /** dbType 在数据源保存时一定已写入,此处直接解空 */
    private fun dialectOf(ds: DataSourceConfig) = dialectFactory.get(ds.dbType!!)

    @Throws(SQLException::class)
    fun listDatabases(datasourceId: Long, unfiltered: Boolean = false): List<String> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId).use { conn ->
            val databases = dialect.listDatabases(conn)
            if (unfiltered) return databases
            return applySchemaFilter(databases, ds.schemaFilter)
        }
    }

    companion object {
        /** 库过滤白名单:null/空表示不过滤;非空时只保留名单内的库(保持方言返回顺序) */
        fun applySchemaFilter(databases: List<String>, filter: List<String>?): List<String> {
            if (filter.isNullOrEmpty()) return databases
            return databases.filter { it in filter }
        }
    }

    @Throws(SQLException::class)
    fun listSchemas(datasourceId: Long, database: String?, unfiltered: Boolean = false): List<String> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId).use { conn ->
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database))
            // 多库方言(SQL Server)的 schema(dbo 等)不属于白名单语义,只过滤单库方言的 schema(即用户眼中的「库」)
            val schemas = dialect.listSchemas(conn)
            if (unfiltered || dialect.supportsMultiDatabase()) return schemas
            return applySchemaFilter(schemas, ds.schemaFilter)
        }
    }

    @Throws(SQLException::class)
    fun listTables(datasourceId: Long, database: String?, schema: String): List<TableStat> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId).use { conn ->
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database))
            return dialect.listTables(conn, schema)
        }
    }

    /** 表列表页汇总:指定 schema 下所有基表的字段总数 */
    @Throws(SQLException::class)
    fun countColumns(datasourceId: Long, database: String?, schema: String): Long {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId).use { conn ->
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database))
            return dialect.countColumns(conn, schema)
        }
    }

    /** 表列表页:每张表最近一次 DONE 扫描的信息(任务 id + 完成时间),本地查询不连业务库 */
    fun latestScanJobsByTable(datasourceId: Long, database: String?, schema: String): Map<String, ScanRepository.LatestScan> =
        scanRepository.latestDoneJobsByTable(datasourceId, database, schema)

    /** 表列表页:运行中任务里每张未完成表的分段进度,本地查询不连业务库 */
    fun runningScansByTable(datasourceId: Long, database: String?, schema: String): Map<String, ScanRepository.RunningScan> =
        scanRepository.runningScansByTable(datasourceId, database, schema)

    /**
     * 库列表页概览:schema 列表 + 表数量 + 占用空间 + 各 schema 最近一次扫描。
     * schema 列表/表数量/占用空间走本地缓存(schema_stat):首次访问从业务库元数据拉取并落库,
     * 之后只读缓存,由扫描创建时按 schema 刷新;最近扫描信息本就来自本地 H2,不参与缓存。
     */
    @Throws(SQLException::class)
    fun listSchemaStats(datasourceId: Long, database: String?): List<SchemaStat> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        var cached = schemaStatRepo.findAll(datasourceId, database)
        if (cached.isEmpty()) {
            cached = fetchAndCache(datasourceId, database)
        }
        // 白名单在读取路径同样生效:过滤规则变更后旧缓存无需重建;多库方言的 schema 层级不过滤
        if (!dialect.supportsMultiDatabase() && !ds.schemaFilter.isNullOrEmpty()) {
            cached = cached.filter { it.schemaName in ds.schemaFilter!! }
        }
        val latest = scanRepository.latestJobsBySchema(datasourceId, database)
        val stats = ArrayList<SchemaStat>(cached.size)
        for (c in cached) {
            val job = latest[c.schemaName]
            var scanAt: LocalDateTime? = null
            if (job != null) {
                scanAt = job.finishedAt ?: job.startedAt ?: job.createdAt
            }
            // 表数量为 0 时体积不是未知而是 0(聚合 SQL 对无表 schema 不产生分组,缓存为 null)
            val sizeBytes = c.sizeBytes ?: (if (c.tableCount?.toLong() == 0L) 0L else null)
            stats.add(
                SchemaStat(
                    c.schemaName, c.tableCount, sizeBytes,
                    job?.status?.name, scanAt,
                    job?.id,
                    job?.doneTables,
                    job?.totalTables,
                )
            )
        }
        return stats
    }

    /** 首次访问:从业务库元数据拉取 schema 列表/表数量/占用空间并整体落缓存 */
    @Throws(SQLException::class)
    private fun fetchAndCache(datasourceId: Long, database: String?): List<SchemaStatRepository.CachedStat> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        val stats = ArrayList<SchemaStatRepository.CachedStat>()
        dataSourceService.getConnection(datasourceId).use { conn ->
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database))
            val counts = dialect.countTablesBySchema(conn)
            val sizes = dialect.sumSizeBySchema(conn)
            // 库过滤白名单同样作用于概览缓存;多库方言的 schema 层级不过滤
            val schemas = if (dialect.supportsMultiDatabase()) dialect.listSchemas(conn)
            else applySchemaFilter(dialect.listSchemas(conn), ds.schemaFilter)
            for (schema in schemas) {
                stats.add(SchemaStatRepository.CachedStat(schema, counts[schema], sizes[schema]))
            }
        }
        schemaStatRepo.replaceAll(datasourceId, database, stats)
        return stats
    }
}
