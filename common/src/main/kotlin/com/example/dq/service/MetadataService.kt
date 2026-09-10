package com.example.dq.service

import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColumnMeta
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.IndexMeta
import com.example.dq.model.SchemaColumn
import com.example.dq.model.SchemaStat
import com.example.dq.model.TableStat
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaStatRepository
import java.sql.SQLException
import java.time.LocalDateTime

/** 库/表元数据查询(同步、快速路径) */
class MetadataService(
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val scanRepository: ScanRepository,
    private val schemaStatRepo: SchemaStatRepository,
    private val schemaDocRepo: SchemaDocRepository,
    private val metaCacheRepo: MetaCacheRepository,
) {

    /**
     * 回源失败降级本地缓存编排:连接不可达时写数据源标记并返回已有缓存,回源成功则让标记自愈。
     * 库/schema/表/字段/索引/库概览的浏览与刷新路径共用
     */
    private val cacheFallback = CacheFallback(
        onFailure = { id, message, kind -> dataSourceService.markConnFailure(id, message, kind) },
        onSuccess = { id -> dataSourceService.markConnRecovered(id) },
    )

    /** 本次调用是否因数据源连接失败降级读了本地缓存(壳层写响应头用) */
    fun consumeCacheFallback(): Boolean = cacheFallback.consumeFallback()

    /** dbType 在数据源保存时一定已写入,此处直接解空 */
    private fun dialectOf(ds: DataSourceConfig) = dialectFactory.get(ds.dbType!!)

    /**
     * 数据源级库清单(多库方言的 database 列表;单库方言恒为空列表):本地缓存优先。
     * 缓存存全量,白名单在读取路径过滤(与 schema_stat 一致);refresh=true 从业务库拉最新并覆盖缓存。
     * 回源遇网络不可达且本地有缓存时降级返回缓存(见 [cacheFallback])
     */
    @Throws(SQLException::class)
    fun listDatabases(datasourceId: Long, unfiltered: Boolean = false, refresh: Boolean = false): List<String> {
        val ds = dataSourceService.get(datasourceId)
        val cacheReady = metaCacheRepo.isDatabaseListReady(datasourceId)
        val all = if (!refresh && cacheReady) {
            metaCacheRepo.listNames(datasourceId, "")
        } else {
            cacheFallback.fetch(
                datasourceId,
                hasCache = { cacheReady },
                readCache = { metaCacheRepo.listNames(datasourceId, "") },
            ) {
                val fresh = fetchDatabases(datasourceId)
                metaCacheRepo.replaceDatabases(datasourceId, fresh)
                fresh
            }
        }
        if (unfiltered) return all
        return applySchemaFilter(all, ds.schemaFilter)
    }

    /** 实时库清单(不经缓存) */
    @Throws(SQLException::class)
    private fun fetchDatabases(datasourceId: Long): List<String> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId).use { conn ->
            return dialect.listDatabases(conn)
        }
    }

    companion object {
        /** 库过滤白名单:null/空表示不过滤;非空时只保留名单内的库(保持方言返回顺序) */
        fun applySchemaFilter(databases: List<String>, filter: List<String>?): List<String> {
            if (filter.isNullOrEmpty()) return databases
            return databases.filter { it in filter }
        }
    }

    /**
     * 某库的 schema 清单(单库方言 schema 即用户眼中的库):本地缓存优先,断网时有缓存即可正常浏览。
     * 缓存存全量,白名单在读取路径过滤;refresh=true 从业务库拉最新并覆盖缓存;
     * 回源遇网络不可达且本地有缓存时降级返回缓存(见 [cacheFallback])
     */
    @Throws(SQLException::class)
    fun listSchemas(datasourceId: Long, database: String?, unfiltered: Boolean = false, refresh: Boolean = false): List<String> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        val db = normalizeDb(database)
        val cacheReady = metaCacheRepo.isSchemaListReady(datasourceId, db)
        val all = if (!refresh && cacheReady) {
            metaCacheRepo.listNames(datasourceId, db)
        } else {
            cacheFallback.fetch(
                datasourceId,
                hasCache = { cacheReady },
                readCache = { metaCacheRepo.listNames(datasourceId, db) },
            ) {
                val fresh = fetchSchemas(datasourceId, database)
                metaCacheRepo.replaceSchemas(datasourceId, db, fresh)
                fresh
            }
        }
        // 多库方言(SQL Server)的 schema(dbo 等)不属于白名单语义,只过滤单库方言的 schema(即用户眼中的「库」)
        if (unfiltered || dialect.supportsMultiDatabase()) return all
        return applySchemaFilter(all, ds.schemaFilter)
    }

    /** 实时 schema 清单(不经缓存) */
    @Throws(SQLException::class)
    private fun fetchSchemas(datasourceId: Long, database: String?): List<String> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            return dialect.listSchemas(conn)
        }
    }

    @Throws(SQLException::class)
    fun listTables(datasourceId: Long, database: String?, schema: String, refresh: Boolean = false): List<TableStat> {
        val db = normalizeDb(database)
        val cacheReady = metaCacheRepo.isTableCacheReady(datasourceId, db, schema)
        if (!refresh && cacheReady) {
            return metaCacheRepo.listTables(datasourceId, db, schema).map { it.toTableStat() }
        }
        // 缓存未就绪或强制刷新:从业务库拉最新结构并覆盖本地缓存;回源失败且有缓存则降级返回缓存
        return cacheFallback.fetch(
            datasourceId,
            hasCache = { cacheReady },
            readCache = { metaCacheRepo.listTables(datasourceId, db, schema).map { it.toTableStat() } },
        ) {
            val fresh = fetchTables(datasourceId, database, schema)
            metaCacheRepo.replaceTables(datasourceId, db, schema, fresh.map { it.toCached() })
            fresh
        }
    }

    /** 实时表清单(不经缓存) */
    @Throws(SQLException::class)
    private fun fetchTables(datasourceId: Long, database: String?, schema: String): List<TableStat> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            return dialect.listTables(conn, schema)
        }
    }

    /** 表列表页汇总:schema 字段总数;本地缓存优先,refresh=true 回源覆盖;回源失败且有缓存则降级 */
    @Throws(SQLException::class)
    fun countColumns(datasourceId: Long, database: String?, schema: String, refresh: Boolean = false): Long {
        val db = normalizeDb(database)
        val cached = metaCacheRepo.getColumnCount(datasourceId, db, schema)
        if (!refresh && cached != null) return cached
        return cacheFallback.fetch(
            datasourceId,
            hasCache = { cached != null },
            readCache = { cached ?: 0L },
        ) {
            val ds = dataSourceService.get(datasourceId)
            val dialect = dialectOf(ds)
            val count = dataSourceService.getConnection(datasourceId, database).use { conn ->
                dialect.countColumns(conn, schema)
            }
            metaCacheRepo.replaceColumnCount(datasourceId, db, schema, count)
            count
        }
    }

    /** 整库字段清单(表名+字段名+类型;SQL 控制台智能提示用):本地缓存优先;refresh=true 从业务库拉最新并覆盖缓存;回源失败且有缓存则降级 */
    @Throws(SQLException::class)
    fun listSchemaColumns(datasourceId: Long, database: String?, schema: String, refresh: Boolean = false): List<SchemaColumn> {
        val db = normalizeDb(database)
        val cacheReady = metaCacheRepo.isSchemaColumnsReady(datasourceId, db, schema)
        if (!refresh && cacheReady) {
            return metaCacheRepo.listSchemaColumns(datasourceId, db, schema).map { it.toSchemaColumn() }
        }
        // 缓存未就绪或强制刷新:从业务库拉最新并整粒度覆盖本地缓存;回源失败且有缓存则降级
        return cacheFallback.fetch(
            datasourceId,
            hasCache = { cacheReady },
            readCache = { metaCacheRepo.listSchemaColumns(datasourceId, db, schema).map { it.toSchemaColumn() } },
        ) {
            val fresh = fetchSchemaColumns(datasourceId, database, schema)
            metaCacheRepo.replaceSchemaColumns(datasourceId, db, schema, fresh.toCachedSchemaColumns())
            fresh
        }
    }

    /** 实时整库字段清单(不经缓存) */
    @Throws(SQLException::class)
    private fun fetchSchemaColumns(datasourceId: Long, database: String?, schema: String): List<SchemaColumn> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            return dialect.listSchemaColumns(conn, schema)
        }
    }

    /** 分批字段清单(指定表子集):已缓存的表读本地;未缓存或 refresh 的表逐表回源并落缓存;返回请求表集合的并集 */
    @Throws(SQLException::class)
    fun listSchemaColumns(datasourceId: Long, database: String?, schema: String, tables: List<String>, refresh: Boolean): List<SchemaColumn> {
        if (tables.isEmpty()) return emptyList()
        val db = normalizeDb(database)
        // schema 级整库缓存已就绪则视为全部表已缓存;否则看 per-table 分批标记
        val schemaReady = metaCacheRepo.isSchemaColumnsReady(datasourceId, db, schema)
        val cachedTables = if (schemaReady) tables.toSet() else metaCacheRepo.schemaColumnCachedTables(datasourceId, db, schema)
        val toFetch = when {
            refresh -> tables
            schemaReady -> emptyList()
            else -> tables - cachedTables
        }
        if (toFetch.isNotEmpty()) {
            // 回源中途断连时:已有分批缓存的表照常返回(降级),一张都没缓存才把异常抛给前端
            cacheFallback.fetch<Unit>(
                datasourceId,
                hasCache = { cachedTables.isNotEmpty() },
                readCache = { },
            ) {
                val ds = dataSourceService.get(datasourceId)
                val dialect = dialectOf(ds)
                dataSourceService.getConnection(datasourceId, database).use { conn ->
                    // 同一条业务库连接逐表拉取,每张表单独落缓存(成功部分不丢)
                    for (table in toFetch) {
                        val fresh = dialect.listSchemaColumns(conn, schema, table)
                        metaCacheRepo.replaceSchemaTableColumns(datasourceId, db, schema, table, fresh.toCachedSchemaColumns())
                    }
                }
            }
        }
        return metaCacheRepo.listSchemaColumns(datasourceId, db, schema, tables).map { it.toSchemaColumn() }
    }

    /** 单表字段元数据(结构明细:字段名/类型/注释/约束),未扫描的表也可查看;不含扫描统计;回源失败且有缓存则降级 */
    @Throws(SQLException::class)
    fun listTableColumns(datasourceId: Long, database: String?, schema: String, table: String, refresh: Boolean = false): List<ColumnMeta> {
        val db = normalizeDb(database)
        val cacheReady = metaCacheRepo.isColumnCacheReady(datasourceId, db, schema, table)
        if (!refresh && cacheReady) {
            return metaCacheRepo.listColumns(datasourceId, db, schema, table).map { it.toColumnMeta() }
        }
        return cacheFallback.fetch(
            datasourceId,
            hasCache = { cacheReady },
            readCache = { metaCacheRepo.listColumns(datasourceId, db, schema, table).map { it.toColumnMeta() } },
        ) {
            val fresh = fetchColumns(datasourceId, database, schema, table)
            metaCacheRepo.replaceColumns(datasourceId, db, schema, table, fresh.mapIndexed { i, c -> c.toCached(i) })
            // 无字段标记联动:访问到没有字段的表(不存在/IOT 溢出段等)打标记供扫描跳过;
            // 有字段则清除残留标记。强制刷新表结构(replaceTables 覆盖)也会还原标记,此处按实测结果重新标定。
            // 降级路径不写该标记(没真正读到业务库,不能据失败结果判定表无字段)
            metaCacheRepo.setNoColumns(datasourceId, db, schema, table, fresh.isEmpty())
            fresh
        }
    }

    /** 实时单表字段(不经缓存) */
    @Throws(SQLException::class)
    private fun fetchColumns(datasourceId: Long, database: String?, schema: String, table: String): List<ColumnMeta> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            return dialect.listColumns(conn, schema, table)
        }
    }

    /** 单表索引结构(索引名/唯一性/索引列),未扫描的表也可查看;回源失败且有缓存则降级 */
    @Throws(SQLException::class)
    fun listTableIndexes(datasourceId: Long, database: String?, schema: String, table: String, refresh: Boolean = false): List<IndexMeta> {
        val db = normalizeDb(database)
        val cacheReady = metaCacheRepo.isIndexCacheReady(datasourceId, db, schema, table)
        if (!refresh && cacheReady) {
            return readCachedIndexes(datasourceId, db, schema, table)
        }
        return cacheFallback.fetch(
            datasourceId,
            hasCache = { cacheReady },
            readCache = { readCachedIndexes(datasourceId, db, schema, table) },
        ) {
            val fresh = fetchIndexes(datasourceId, database, schema, table)
            metaCacheRepo.replaceIndexes(datasourceId, db, schema, table,
                fresh.flatMap { idx -> idx.columns.mapIndexed { i, col ->
                    MetaCacheRepository.CachedIndex(idx.name, idx.unique, i, col) } })
            fresh
        }
    }

    /** 本地索引缓存读成 IndexMeta(索引列展开行按索引名聚合,保持列序) */
    private fun readCachedIndexes(datasourceId: Long, db: String, schema: String, table: String): List<IndexMeta> =
        metaCacheRepo.listIndexes(datasourceId, db, schema, table)
            .groupBy { it.indexName }
            .map { (name, rows) -> IndexMeta(name, rows.first().unique, rows.map { it.columnName }) }

    /** 实时单表索引(不经缓存) */
    @Throws(SQLException::class)
    private fun fetchIndexes(datasourceId: Long, database: String?, schema: String, table: String): List<IndexMeta> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            return dialect.listIndexes(conn, schema, table)
        }
    }

    /** 单表建表 DDL(含索引):本地缓存优先,refresh=true 回源覆盖;回源失败且有缓存则降级 */
    @Throws(SQLException::class)
    fun tableDdl(datasourceId: Long, database: String?, schema: String, table: String, refresh: Boolean = false): String {
        val db = normalizeDb(database)
        val cached = metaCacheRepo.getDdl(datasourceId, db, schema, table)
        if (!refresh && cached != null) return cached
        return cacheFallback.fetch(
            datasourceId,
            hasCache = { cached != null },
            readCache = { cached.orEmpty() },
        ) {
            val ds = dataSourceService.get(datasourceId)
            val dialect = dialectOf(ds)
            val ddl = dataSourceService.getConnection(datasourceId, database).use { conn ->
                dialect.tableDdl(conn, schema, table)
            }
            metaCacheRepo.replaceDdl(datasourceId, db, schema, table, ddl)
            ddl
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
    fun listSchemaStats(datasourceId: Long, database: String?, refresh: Boolean = false): List<SchemaStat> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        var cached = schemaStatRepo.findAll(datasourceId, database)
        if (cached.isEmpty() || refresh) {
            // 首次访问或强制刷新回源;断网时若有旧概览缓存则降级返回(库名列表此前已从 meta_database 缓存拿到)
            cached = cacheFallback.fetch(
                datasourceId,
                hasCache = { schemaStatRepo.findAll(datasourceId, database).isNotEmpty() },
                readCache = { schemaStatRepo.findAll(datasourceId, database) },
            ) { fetchAndCache(datasourceId, database) }
        }
        // 白名单在读取路径同样生效:过滤规则变更后旧缓存无需重建;多库方言的 schema 层级不过滤
        if (!dialect.supportsMultiDatabase() && !ds.schemaFilter.isNullOrEmpty()) {
            cached = cached.filter { it.schemaName in ds.schemaFilter!! }
        }
        val latest = scanRepository.latestJobsBySchema(datasourceId, database)
        val docs = schemaDocRepo.findByDatasource(datasourceId, database ?: "")
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
                    docs[c.schemaName],
                )
            )
        }
        return stats
    }

    /** 库列表页编辑库描述(Word 报告「实例描述」列);空白表示清除 */
    fun updateSchemaDescription(datasourceId: Long, database: String?, schema: String, description: String?) {
        dataSourceService.get(datasourceId) // 数据源不存在时抛异常
        val text = description?.trim().orEmpty()
        if (text.length > 512) {
            throw IllegalArgumentException("描述长度不能超过 512 字")
        }
        if (text.isEmpty()) {
            schemaDocRepo.delete(datasourceId, database ?: "", schema)
        } else {
            schemaDocRepo.upsert(datasourceId, database ?: "", schema, text)
        }
    }

    /** 无库概念方言的 database 归一为空串,与 schema_doc/table_doc/meta_* 缓存口径一致 */
    private fun normalizeDb(database: String?): String = database ?: ""

    // ---------- 结构缓存模型转换 ----------

    private fun TableStat.toCached() = MetaCacheRepository.CachedTable(name ?: "", comment, storageInfo, estRows, sizeBytes)

    private fun MetaCacheRepository.CachedTable.toTableStat() = TableStat(tableName, estRows, sizeBytes, comment, storageInfo)

    private fun ColumnMeta.toCached(ordinal: Int) = MetaCacheRepository.CachedColumn(
        ordinal, name, typeName, displayType, jdbcType, nullable, defaultValue, comment, primaryKey, pkSeq, uniqueIndexFirst
    )

    private fun MetaCacheRepository.CachedColumn.toColumnMeta() = ColumnMeta(
        columnName, typeName, displayType, jdbcType, nullable, defaultValue, comment, primaryKey, pkSeq, uniqueIndexFirst
    )

    /** SchemaColumn 列表 → 字段清单缓存行:按表分组保持返回顺序生成表内 ordinal */
    private fun List<SchemaColumn>.toCachedSchemaColumns(): List<MetaCacheRepository.CachedSchemaColumn> {
        val ordinals = HashMap<String, Int>()
        return map { c ->
            val ord = ordinals[c.table] ?: 0
            ordinals[c.table] = ord + 1
            MetaCacheRepository.CachedSchemaColumn(c.table, ord, c.name, c.type, c.comment)
        }
    }

    private fun MetaCacheRepository.CachedSchemaColumn.toSchemaColumn() =
        SchemaColumn(tableName, columnName, colType ?: "", comment ?: "")

    /** 首次访问:从业务库元数据拉取 schema 列表/表数量/占用空间并整体落缓存;顺带刷新 schema 清单缓存 */
    @Throws(SQLException::class)
    private fun fetchAndCache(datasourceId: Long, database: String?): List<SchemaStatRepository.CachedStat> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectOf(ds)
        val stats = ArrayList<SchemaStatRepository.CachedStat>()
        val schemasAll: List<String>
        dataSourceService.getConnection(datasourceId, database).use { conn ->
            val counts = dialect.countTablesBySchema(conn)
            val sizes = dialect.sumSizeBySchema(conn)
            schemasAll = dialect.listSchemas(conn)
            // 库过滤白名单同样作用于概览缓存;多库方言的 schema 层级不过滤
            val schemas = if (dialect.supportsMultiDatabase()) schemasAll
            else applySchemaFilter(schemasAll, ds.schemaFilter)
            for (schema in schemas) {
                stats.add(SchemaStatRepository.CachedStat(schema, counts[schema], sizes[schema]))
            }
        }
        // schema 清单缓存存全量(白名单在读取路径过滤),与概览缓存同次回源保持一致
        metaCacheRepo.replaceSchemas(datasourceId, normalizeDb(database), schemasAll)
        schemaStatRepo.replaceAll(datasourceId, database, stats)
        return stats
    }
}
