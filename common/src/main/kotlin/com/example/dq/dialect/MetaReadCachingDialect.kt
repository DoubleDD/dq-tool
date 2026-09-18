package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.IndexMeta
import com.example.dq.model.SchemaColumn
import com.example.dq.model.TableStat
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.util.SqlLogConnection
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * 元数据读上下文:由 DataSourceService.getConnection 随连接代理携带
 * (util/SqlLogConnection 只透传成 Any?,不依赖本类型)。
 * dbName 已按 meta_* 口径归一(无库概念方言空串;多库方言为解析后的目标库)。
 */
class MetaReadContext(
    val datasourceId: Long,
    val dbName: String,
    val metaCache: MetaCacheRepository,
)

/**
 * 元数据读拦截器(DbDialect 装饰器):读到 库/schema/表/字段/索引/字段清单/DDL 等元数据时,
 * 若本地 meta_* 对应粒度**尚未缓存**,就把结果火忘回填(异步经 MetaWriteQueue 单写线程,
 * 不阻塞读取线程)。由 DialectFactory 统一装配,对所有调用方透明。
 *
 * 定位是「兜底回填」而不是刷新器:
 * - 粒度已就绪就跳过——否则 ChunkRunner 每分段 listColumns、数据预览等热路径会产生写放大;
 * - 强制刷新(refresh=true)与扫描的整粒度覆盖仍由各调用方显式写(语义各异:同步立即可读、
 *   replace vs merge、no_columns 联动等),拦截器不替代它们,命中时只是多一次同值幂等写;
 * - 无上下文(测试直连方言、预览/测试连接等非池化连接)纯透传;
 * - 拦截器自身任何异常只记日志,绝不影响元数据读取主流程。
 *
 * 本类虽在 dialect 包,但不是数据库差异:它是缓存策略装饰器,引用的 MetaCacheRepository
 * 不含任何库特定逻辑( dialect 包「收敛库差异」的边界不受此影响)。
 */
class MetaReadCachingDialect(private val delegate: DbDialect) : DbDialect by delegate {

    private val log = LoggerFactory.getLogger(MetaReadCachingDialect::class.java)

    override fun listDatabasesOrSchemas(conn: Connection): List<String> =
        delegate.listDatabasesOrSchemas(conn).also { names ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isDatabaseListReady(ctx.datasourceId)) {
                    ctx.metaCache.replaceDatabasesAsync(ctx.datasourceId, names)
                }
            }
        }

    override fun listSchemas(conn: Connection): List<String> =
        delegate.listSchemas(conn).also { names ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isSchemaListReady(ctx.datasourceId, ctx.dbName)) {
                    ctx.metaCache.replaceSchemasAsync(ctx.datasourceId, ctx.dbName, names)
                }
            }
        }

    override fun listTables(conn: Connection, schema: String): List<TableStat> =
        delegate.listTables(conn, schema).also { tables ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isTableCacheReady(ctx.datasourceId, ctx.dbName, schema)) {
                    ctx.metaCache.replaceTablesAsync(ctx.datasourceId, ctx.dbName, schema, tables.map { it.toCached() })
                }
            }
        }

    /** 过滤清单不是全量:只能按表合并,不能整粒度覆盖(与 ScanService 过滤扫描同一口径) */
    override fun listTables(conn: Connection, schema: String, tableNames: Collection<String>): List<TableStat> =
        delegate.listTables(conn, schema, tableNames).also { tables ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isTableCacheReady(ctx.datasourceId, ctx.dbName, schema)) {
                    ctx.metaCache.mergeTablesAsync(ctx.datasourceId, ctx.dbName, schema, tables.map { it.toCached() })
                }
            }
        }

    override fun countColumns(conn: Connection, schema: String): Long =
        delegate.countColumns(conn, schema).also { count ->
            backfill(conn) { ctx ->
                if (ctx.metaCache.getColumnCount(ctx.datasourceId, ctx.dbName, schema) == null) {
                    ctx.metaCache.replaceColumnCountAsync(ctx.datasourceId, ctx.dbName, schema, count)
                }
            }
        }

    override fun listColumns(conn: Connection, schema: String, table: String): List<ColumnMeta> =
        delegate.listColumns(conn, schema, table).also { cols ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isColumnCacheReady(ctx.datasourceId, ctx.dbName, schema, table)) {
                    ctx.metaCache.replaceColumnsAsync(ctx.datasourceId, ctx.dbName, schema, table,
                        cols.mapIndexed { i, c ->
                            MetaCacheRepository.CachedColumn(
                                i, c.name, c.typeName, c.displayType, c.jdbcType, c.nullable,
                                c.defaultValue, c.comment, c.primaryKey, c.pkSeq, c.uniqueIndexFirst)
                        })
                }
            }
        }

    override fun listSchemaColumns(conn: Connection, schema: String): List<SchemaColumn> =
        delegate.listSchemaColumns(conn, schema).also { rows ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isSchemaColumnsReady(ctx.datasourceId, ctx.dbName, schema)) {
                    ctx.metaCache.replaceSchemaColumnsAsync(ctx.datasourceId, ctx.dbName, schema, rows.toCachedSchemaColumns())
                }
            }
        }

    override fun listSchemaColumns(conn: Connection, schema: String, table: String): List<SchemaColumn> =
        delegate.listSchemaColumns(conn, schema, table).also { rows ->
            backfill(conn) { ctx ->
                // schema 级整库缓存已就绪视为全部表已缓存(与 MetadataService 分批路径同口径)
                if (!ctx.metaCache.isSchemaColumnsReady(ctx.datasourceId, ctx.dbName, schema) &&
                    table !in ctx.metaCache.schemaColumnCachedTables(ctx.datasourceId, ctx.dbName, schema)) {
                    ctx.metaCache.replaceSchemaTableColumnsAsync(ctx.datasourceId, ctx.dbName, schema, table, rows.toCachedSchemaColumns())
                }
            }
        }

    override fun listIndexes(conn: Connection, schema: String, table: String): List<IndexMeta> =
        delegate.listIndexes(conn, schema, table).also { indexes ->
            backfill(conn) { ctx ->
                if (!ctx.metaCache.isIndexCacheReady(ctx.datasourceId, ctx.dbName, schema, table)) {
                    ctx.metaCache.replaceIndexesAsync(ctx.datasourceId, ctx.dbName, schema, table,
                        indexes.flatMap { ix ->
                            ix.columns.mapIndexed { i, col -> MetaCacheRepository.CachedIndex(ix.name, ix.unique, i, col) }
                        })
                }
            }
        }

    override fun tableDdl(conn: Connection, schema: String, table: String): String =
        delegate.tableDdl(conn, schema, table).also { ddl ->
            backfill(conn) { ctx ->
                if (ctx.metaCache.getDdl(ctx.datasourceId, ctx.dbName, schema, table) == null) {
                    ctx.metaCache.replaceDdlAsync(ctx.datasourceId, ctx.dbName, schema, table, ddl)
                }
            }
        }

    /** 无上下文纯透传;回填异常只记日志,绝不影响读取 */
    private inline fun backfill(conn: Connection, action: (MetaReadContext) -> Unit) {
        val ctx = SqlLogConnection.metaContextOf(conn) as? MetaReadContext ?: return
        try {
            action(ctx)
        } catch (e: Exception) {
            log.debug("元数据缓存回填失败(不影响读取): {}", e.message)
        }
    }

    private fun TableStat.toCached() =
        MetaCacheRepository.CachedTable(name ?: "", comment, storageInfo, estRows, sizeBytes)

    /** SchemaColumn 列表 → 字段清单缓存行:按表分组保持返回顺序生成表内 ordinal(与 MetadataService 同口径) */
    private fun List<SchemaColumn>.toCachedSchemaColumns(): List<MetaCacheRepository.CachedSchemaColumn> {
        val ordinals = HashMap<String, Int>()
        return map { c ->
            val ord = ordinals[c.table] ?: 0
            ordinals[c.table] = ord + 1
            MetaCacheRepository.CachedSchemaColumn(c.table, ord, c.name, c.type, c.comment)
        }
    }
}
