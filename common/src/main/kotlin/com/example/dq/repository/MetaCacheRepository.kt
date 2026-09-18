package com.example.dq.repository

/**
 * 结构元数据本地缓存(meta_database / meta_table / meta_column / meta_index / meta_schema_column /
 * meta_ddl / meta_column_count):
 * 浏览路径懒加载 + 手动刷新/扫描时同步刷新;刷新语义为整粒度覆盖(delete + insert)。
 * db_name 已由调用方 normalize:无库概念方言存空串(与 schema_doc/table_doc 口径一致)。
 *
 * meta_database 是库/schema 清单的缓存(V29):db_name 空串 = 数据源级库清单(多库方言的 database 列表),
 * db_name=库名 = 该库的 schema 清单;缓存一律存全量,白名单过滤在读取路径生效(改白名单无需重建缓存)。
 *
 * meta_schema_column 是整库字段清单的 lite 缓存(SQL 控制台智能提示用,kind=SCOLUMN):
 * 与 meta_column(单表结构明细)互不干扰;meta_cache_flag 中 table_name 空串 = 整 schema 字段清单已缓存,
 * table_name=具体表 = 该表字段已分批缓存;整库覆盖时同步清掉 per-table 标记。
 *
 * 并发安全:覆盖刷新是「先 DELETE 后 INSERT」,多 worker 并发刷不同粒度会互相等锁/踩唯一键(23505)。
 * 本仓储的**写操作**统一走 [MetaWriteQueue] 的全局单线程队列串行执行,对 H2 元数据表禁止并发写;
 * 读操作不排队(缓存优先路径要快)。
 *
 * 扫描口径:**扫描只写不读本缓存**——结构真源永远是原始库,扫描拿到最新元数据后整粒度覆盖到这里
 * (见 ScanService)。浏览/导出/推导等非扫描功能才走「缓存优先 + 回源覆盖 + 不可达降级」。
 */
class MetaCacheRepository(
    private val jdbc: Jdbc,
    private val writeQueue: MetaWriteQueue = MetaWriteQueue(),
) {

    /** 表级缓存行 */
    data class CachedTable(
        val tableName: String,
        val comment: String?,
        val storageInfo: String?,
        val estRows: Long?,
        val sizeBytes: Long?
    )

    /** 字段缓存行;ordinal 为字段顺序(与 listColumns 返回一致) */
    data class CachedColumn(
        val ordinal: Int,
        val columnName: String,
        val typeName: String,
        val displayType: String,
        val jdbcType: Int,
        val nullable: Boolean,
        val defaultValue: String?,
        val comment: String?,
        val primaryKey: Boolean,
        val pkSeq: Int,
        val uniqueIndexFirst: Boolean
    )

    /** 索引缓存行:索引列展开为多行,ordinal 为索引内列顺序 */
    data class CachedIndex(
        val indexName: String,
        val unique: Boolean,
        val ordinal: Int,
        val columnName: String
    )

    /** 整库字段清单缓存行(lite:表名+字段名+展示类型+注释;ordinal 为表内字段顺序,与方言返回一致) */
    data class CachedSchemaColumn(
        val tableName: String,
        val ordinal: Int,
        val columnName: String,
        val colType: String?,
        val comment: String?
    )

    // ---------- 缓存存在标记(区分「未缓存」与「已缓存但为空」,如表无索引) ----------

    /** schema 表清单缓存是否已就绪(tableName 空串 = schema 级) */
    fun isTableCacheReady(datasourceId: Long, dbName: String, schema: String): Boolean =
        flagExists(datasourceId, dbName, schema, "", KIND_TABLE)

    /** 单表字段缓存是否已就绪 */
    fun isColumnCacheReady(datasourceId: Long, dbName: String, schema: String, table: String): Boolean =
        flagExists(datasourceId, dbName, schema, table, KIND_COLUMN)

    /** 单表索引缓存是否已就绪 */
    fun isIndexCacheReady(datasourceId: Long, dbName: String, schema: String, table: String): Boolean =
        flagExists(datasourceId, dbName, schema, table, KIND_INDEX)

    /** 整库字段清单缓存是否已就绪(tableName 空串 = schema 级) */
    fun isSchemaColumnsReady(datasourceId: Long, dbName: String, schema: String): Boolean =
        flagExists(datasourceId, dbName, schema, "", KIND_SCOLUMN)

    /** 数据源级库清单缓存是否已就绪(db/schema/table 均空串) */
    fun isDatabaseListReady(datasourceId: Long): Boolean =
        flagExists(datasourceId, "", "", "", KIND_DATABASE)

    /** 某库的 schema 清单缓存是否已就绪(单库方言 dbName 空串) */
    fun isSchemaListReady(datasourceId: Long, dbName: String): Boolean =
        flagExists(datasourceId, dbName, "", "", KIND_SCHEMA)

    /** 已分批缓存字段的表名集合(per-table SCOLUMN 标记) */
    fun schemaColumnCachedTables(datasourceId: Long, dbName: String, schema: String): Set<String> =
        jdbc.query(
            "SELECT table_name FROM meta_cache_flag " +
                    "WHERE datasource_id=? AND db_name=? AND schema_name=? AND kind=? AND table_name<>''",
            datasourceId, dbName, schema, KIND_SCOLUMN
        ) { it.getString(1) }.toSet()

    private fun flagExists(datasourceId: Long, dbName: String, schema: String, table: String, kind: String): Boolean =
        jdbc.queryOne(
            "SELECT 1 FROM meta_cache_flag WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=? AND kind=?",
            datasourceId, dbName, schema, table, kind
        ) { it.getInt(1) } != null

    private fun writeFlag(conn: java.sql.Connection, datasourceId: Long, dbName: String, schema: String, table: String, kind: String) {
        conn.prepareStatement(
            "DELETE FROM meta_cache_flag WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=? AND kind=?"
        ).use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            ps.setString(4, table); ps.setString(5, kind)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO meta_cache_flag(datasource_id, db_name, schema_name, table_name, kind) VALUES (?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            ps.setString(4, table); ps.setString(5, kind)
            ps.executeUpdate()
        }
    }

    companion object {
        private const val KIND_TABLE = "TABLE"
        private const val KIND_COLUMN = "COLUMN"
        private const val KIND_INDEX = "INDEX"
        private const val KIND_SCOLUMN = "SCOLUMN"
        private const val KIND_DATABASE = "DATABASE"
        private const val KIND_SCHEMA = "SCHEMA"

        /** 表清单覆盖后需要按「已删表」清理的子级缓存表 */
        private val STALE_CHILD_TABLES = listOf("meta_column", "meta_index", "meta_schema_column")

        /** 删数据源时需级联清空的全部缓存表 */
        private val ALL_CACHE_TABLES = listOf(
            "meta_database", "meta_table", "meta_column", "meta_index",
            "meta_schema_column", "meta_ddl", "meta_column_count", "meta_cache_flag",
        )
    }

    // ---------- 库/schema 清单(meta_database,缓存存全量,白名单在读取路径过滤) ----------

    /** 读清单缓存(库清单 dbName 传空串;schema 清单传所属库名),按方言返回顺序(ordinal)排序 */
    fun listNames(datasourceId: Long, dbName: String): List<String> =
        jdbc.query(
            "SELECT name FROM meta_database WHERE datasource_id=? AND db_name=? ORDER BY ordinal",
            datasourceId, dbName
        ) { it.getString(1) }

    /** 整粒度覆盖数据源级库清单缓存 */
    fun replaceDatabases(datasourceId: Long, names: List<String>) =
        writeQueue.submit { replaceDatabasesTx(datasourceId, names) }

    /** 火忘写(方言层拦截器回填用):不阻塞读取线程,失败由队列记日志(语义同 [replaceDatabases]) */
    fun replaceDatabasesAsync(datasourceId: Long, names: List<String>) =
        writeQueue.submitAsync { replaceDatabasesTx(datasourceId, names) }

    private fun replaceDatabasesTx(datasourceId: Long, names: List<String>) {
        jdbc.tx { conn ->
            deleteNames(conn, datasourceId, "")
            insertNames(conn, datasourceId, "", names)
            writeFlag(conn, datasourceId, "", "", "", KIND_DATABASE)
        }
    }

    /** 整粒度覆盖某库的 schema 清单缓存(单库方言 dbName 空串) */
    fun replaceSchemas(datasourceId: Long, dbName: String, names: List<String>) =
        writeQueue.submit { replaceSchemasTx(datasourceId, dbName, names) }

    /** 火忘写(方言层拦截器回填用):不阻塞读取线程,失败由队列记日志(语义同 [replaceSchemas]) */
    fun replaceSchemasAsync(datasourceId: Long, dbName: String, names: List<String>) =
        writeQueue.submitAsync { replaceSchemasTx(datasourceId, dbName, names) }

    private fun replaceSchemasTx(datasourceId: Long, dbName: String, names: List<String>) {
        jdbc.tx { conn ->
            deleteNames(conn, datasourceId, dbName)
            insertNames(conn, datasourceId, dbName, names)
            writeFlag(conn, datasourceId, dbName, "", "", KIND_SCHEMA)
        }
    }

    private fun deleteNames(conn: java.sql.Connection, datasourceId: Long, dbName: String) {
        conn.prepareStatement("DELETE FROM meta_database WHERE datasource_id=? AND db_name=?").use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName)
            ps.executeUpdate()
        }
    }

    private fun insertNames(conn: java.sql.Connection, datasourceId: Long, dbName: String, names: List<String>) {
        conn.prepareStatement(
            "INSERT INTO meta_database(datasource_id, db_name, name, ordinal) VALUES (?,?,?,?)"
        ).use { ps ->
            names.forEachIndexed { i, name ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName)
                ps.setString(3, name); ps.setInt(4, i)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    // ---------- 表 ----------
    fun listTables(datasourceId: Long, dbName: String, schema: String): List<CachedTable> =
        jdbc.query(
            "SELECT table_name, comment, storage_info, est_rows, size_bytes FROM meta_table " +
                    "WHERE datasource_id=? AND db_name=? AND schema_name=? ORDER BY table_name",
            datasourceId, dbName, schema
        ) { rs ->
            val rows = rs.getLong(4)
            val bytes = rs.getLong(5)
            CachedTable(
                rs.getString(1), rs.getString(2), rs.getString(3),
                if (rs.wasNull()) null else rows,
                if (rs.wasNull()) null else bytes
            )
        }

    /** 无字段标记:表不存在或没有字段时置 TRUE,扫描/续扫按空表跳过;表不存在于缓存时无操作 */
    fun setNoColumns(datasourceId: Long, dbName: String, schema: String, table: String, noColumns: Boolean) =
        writeQueue.submit { setNoColumnsTx(datasourceId, dbName, schema, table, noColumns) }

    /** 扫描路径火忘写:不阻塞扫描 worker,失败由队列记日志(语义同 [setNoColumns]) */
    fun setNoColumnsAsync(datasourceId: Long, dbName: String, schema: String, table: String, noColumns: Boolean) =
        writeQueue.submitAsync { setNoColumnsTx(datasourceId, dbName, schema, table, noColumns) }

    private fun setNoColumnsTx(datasourceId: Long, dbName: String, schema: String, table: String, noColumns: Boolean) {
        jdbc.update(
            "UPDATE meta_table SET no_columns=? WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?",
            noColumns, datasourceId, dbName, schema, table
        )
    }

    /** 表是否带无字段标记(缓存里没有该表时视为 false) */
    fun isNoColumns(datasourceId: Long, dbName: String, schema: String, table: String): Boolean =
        jdbc.queryOne(
            "SELECT no_columns FROM meta_table WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?",
            datasourceId, dbName, schema, table
        ) { it.getBoolean(1) } ?: false

    /** 整粒度覆盖某 schema 的表缓存(首次拉取或手动/扫描刷新);覆盖后无字段标记随旧行清除(重新同步后字段有无未知) */
    fun replaceTables(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) =
        writeQueue.submit { replaceTablesTx(datasourceId, dbName, schema, tables) }

    /** 扫描路径火忘写:不阻塞扫描 worker,失败由队列记日志(语义同 [replaceTables]) */
    fun replaceTablesAsync(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) =
        writeQueue.submitAsync { replaceTablesTx(datasourceId, dbName, schema, tables) }

    private fun replaceTablesTx(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) {
        jdbc.tx { conn ->
            conn.prepareStatement("DELETE FROM meta_table WHERE datasource_id=? AND db_name=? AND schema_name=?")
                .use { ps ->
                    ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                    ps.executeUpdate()
                }
            conn.prepareStatement(
                "INSERT INTO meta_table(datasource_id, db_name, schema_name, table_name, comment, storage_info, est_rows, size_bytes) " +
                        "VALUES (?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (t in tables) {
                    ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                    ps.setString(4, t.tableName); ps.setString(5, t.comment); ps.setString(6, t.storageInfo)
                    ps.setObject(7, t.estRows); ps.setObject(8, t.sizeBytes)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            writeFlag(conn, datasourceId, dbName, schema, "", KIND_TABLE)
            // 表集合变化 → 已不存在表的子级缓存(字段/索引/整库字段清单/就绪标记)随覆盖清掉,
            // 避免离线浏览或智能提示读到已删表的陈旧结构
            deleteStaleTableChildren(conn, datasourceId, dbName, schema)
            // 表集合变化 → 该 schema 的字段总数与全部单表 DDL 可能过时,按粒度失效
            deleteColumnCount(conn, datasourceId, dbName, schema)
            deleteDdl(conn, datasourceId, dbName, schema, null)
        }
    }

    /**
     * 清理已不存在表的子级缓存:用 NOT IN 子查询比对刚写入的 meta_table(避免大 schema 拼超长 IN 列表)。
     * flags 只清 table_name<>'' 的行,不碰 schema 级 TABLE/SCOLUMN 标记。
     */
    private fun deleteStaleTableChildren(conn: java.sql.Connection, datasourceId: Long, dbName: String, schema: String) {
        val stale = "datasource_id=? AND db_name=? AND schema_name=? " +
                "AND table_name NOT IN (SELECT table_name FROM meta_table WHERE datasource_id=? AND db_name=? AND schema_name=?)"
        for (table in STALE_CHILD_TABLES) {
            conn.prepareStatement("DELETE FROM $table WHERE $stale").use { ps ->
                bindStale(ps, datasourceId, dbName, schema)
                ps.executeUpdate()
            }
        }
        conn.prepareStatement("DELETE FROM meta_cache_flag WHERE $stale AND table_name<>''").use { ps ->
            bindStale(ps, datasourceId, dbName, schema)
            ps.executeUpdate()
        }
    }

    /** stale 子查询共 6 个占位符(datasource_id/db_name/schema 各两遍) */
    private fun bindStale(ps: java.sql.PreparedStatement, datasourceId: Long, dbName: String, schema: String) {
        ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
        ps.setLong(4, datasourceId); ps.setString(5, dbName); ps.setString(6, schema)
    }

    /**
     * 局部合并某 schema 的表缓存(单事务 + H2 MERGE,按唯一键 datasource_id/db_name/schema_name/table_name 命中):
     * 只覆盖本次命中的表,不动其他行、不动缓存就绪标记、不失效字段总数/DDL。
     *
     * 用于「只扫若干张表」的扫描:此时拿到的不是全量表清单,不能走 [replaceTables] 的整粒度覆盖
     * (会把本地缓存冲成部分清单);MERGE 只更新列出的列,no_columns 等既有标记保留。
     */
    fun mergeTables(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) =
        writeQueue.submit { mergeTablesTx(datasourceId, dbName, schema, tables) }

    /** 扫描路径火忘写:不阻塞扫描 worker,失败由队列记日志(语义同 [mergeTables]) */
    fun mergeTablesAsync(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) =
        writeQueue.submitAsync { mergeTablesTx(datasourceId, dbName, schema, tables) }

    private fun mergeTablesTx(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) {
        if (tables.isEmpty()) return
        jdbc.tx { conn ->
            conn.prepareStatement(
                "MERGE INTO meta_table(datasource_id, db_name, schema_name, table_name, comment, storage_info, est_rows, size_bytes) " +
                        "KEY(datasource_id, db_name, schema_name, table_name) VALUES (?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (t in tables) {
                    ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                    ps.setString(4, t.tableName); ps.setString(5, t.comment); ps.setString(6, t.storageInfo)
                    ps.setObject(7, t.estRows); ps.setObject(8, t.sizeBytes)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    // ---------- 字段 ----------

    fun listColumns(datasourceId: Long, dbName: String, schema: String, table: String): List<CachedColumn> =
        jdbc.query(
            "SELECT ordinal, column_name, type_name, display_type, jdbc_type, nullable, default_value, comment, " +
                    "primary_key, pk_seq, unique_index_first FROM meta_column " +
                    "WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=? ORDER BY ordinal",
            datasourceId, dbName, schema, table
        ) { rs ->
            CachedColumn(
                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getBoolean(6), rs.getString(7), rs.getString(8),
                rs.getBoolean(9), rs.getInt(10), rs.getBoolean(11)
            )
        }

    /** 整粒度覆盖单表字段缓存 */
    fun replaceColumns(datasourceId: Long, dbName: String, schema: String, table: String, columns: List<CachedColumn>) =
        writeQueue.submit { replaceColumnsTx(datasourceId, dbName, schema, table, columns) }

    /** 扫描路径火忘写:不阻塞扫描 worker,失败由队列记日志(语义同 [replaceColumns]) */
    fun replaceColumnsAsync(datasourceId: Long, dbName: String, schema: String, table: String, columns: List<CachedColumn>) =
        writeQueue.submitAsync { replaceColumnsTx(datasourceId, dbName, schema, table, columns) }

    private fun replaceColumnsTx(datasourceId: Long, dbName: String, schema: String, table: String, columns: List<CachedColumn>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "DELETE FROM meta_column WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?"
            ).use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema); ps.setString(4, table)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO meta_column(datasource_id, db_name, schema_name, table_name, ordinal, column_name, " +
                        "type_name, display_type, jdbc_type, nullable, default_value, comment, primary_key, pk_seq, unique_index_first) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (c in columns) {
                    ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema); ps.setString(4, table)
                    ps.setInt(5, c.ordinal); ps.setString(6, c.columnName)
                    ps.setString(7, c.typeName); ps.setString(8, c.displayType); ps.setInt(9, c.jdbcType)
                    ps.setBoolean(10, c.nullable); ps.setString(11, c.defaultValue); ps.setString(12, c.comment)
                    ps.setBoolean(13, c.primaryKey); ps.setInt(14, c.pkSeq); ps.setBoolean(15, c.uniqueIndexFirst)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            writeFlag(conn, datasourceId, dbName, schema, table, KIND_COLUMN)
            // 字段变化 → 该表 DDL 与该 schema 字段总数过时
            deleteDdl(conn, datasourceId, dbName, schema, table)
            deleteColumnCount(conn, datasourceId, dbName, schema)
        }
    }

    // ---------- 索引 ----------

    fun listIndexes(datasourceId: Long, dbName: String, schema: String, table: String): List<CachedIndex> =
        jdbc.query(
            "SELECT index_name, is_unique, ordinal, column_name FROM meta_index " +
                    "WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=? ORDER BY index_name, ordinal",
            datasourceId, dbName, schema, table
        ) { rs ->
            CachedIndex(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4))
        }

    /** 整粒度覆盖单表索引缓存 */
    fun replaceIndexes(datasourceId: Long, dbName: String, schema: String, table: String, indexes: List<CachedIndex>) =
        writeQueue.submit { replaceIndexesTx(datasourceId, dbName, schema, table, indexes) }

    /** 扫描路径火忘写:不阻塞扫描 worker,失败由队列记日志(语义同 [replaceIndexes]) */
    fun replaceIndexesAsync(datasourceId: Long, dbName: String, schema: String, table: String, indexes: List<CachedIndex>) =
        writeQueue.submitAsync { replaceIndexesTx(datasourceId, dbName, schema, table, indexes) }

    private fun replaceIndexesTx(datasourceId: Long, dbName: String, schema: String, table: String, indexes: List<CachedIndex>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "DELETE FROM meta_index WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?"
            ).use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema); ps.setString(4, table)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO meta_index(datasource_id, db_name, schema_name, table_name, index_name, is_unique, ordinal, column_name) " +
                        "VALUES (?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (i in indexes) {
                    ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema); ps.setString(4, table)
                    ps.setString(5, i.indexName); ps.setBoolean(6, i.unique); ps.setInt(7, i.ordinal)
                    ps.setString(8, i.columnName)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            writeFlag(conn, datasourceId, dbName, schema, table, KIND_INDEX)
            // 索引变化 → 该表 DDL(含索引)过时
            deleteDdl(conn, datasourceId, dbName, schema, table)
        }
    }

    // ---------- 整库字段清单(SQL 控制台智能提示,lite 缓存) ----------

    /** 读整 schema 字段清单缓存,按 表名, ordinal 排序 */
    fun listSchemaColumns(datasourceId: Long, dbName: String, schema: String): List<CachedSchemaColumn> =
        jdbc.query(
            "SELECT table_name, ordinal, column_name, col_type, comment FROM meta_schema_column " +
                    "WHERE datasource_id=? AND db_name=? AND schema_name=? ORDER BY table_name, ordinal",
            datasourceId, dbName, schema
        ) { rs -> CachedSchemaColumn(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)) }

    /** 读指定表子集的字段清单缓存(批量 ≤50,占位符拼接),按 表名, ordinal 排序 */
    fun listSchemaColumns(datasourceId: Long, dbName: String, schema: String, tables: Collection<String>): List<CachedSchemaColumn> {
        if (tables.isEmpty()) return emptyList()
        val placeholders = tables.joinToString(",") { "?" }
        return jdbc.query(
            "SELECT table_name, ordinal, column_name, col_type, comment FROM meta_schema_column " +
                    "WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name IN ($placeholders) " +
                    "ORDER BY table_name, ordinal",
            datasourceId, dbName, schema, *tables.toTypedArray()
        ) { rs -> CachedSchemaColumn(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
    }

    /** 整粒度覆盖某 schema 的字段清单缓存;同时清掉 per-table SCOLUMN 标记(schema 级就绪后不再需要) */
    fun replaceSchemaColumns(datasourceId: Long, dbName: String, schema: String, rows: List<CachedSchemaColumn>) =
        writeQueue.submit { replaceSchemaColumnsTx(datasourceId, dbName, schema, rows) }

    /** 火忘写(方言层拦截器回填用):不阻塞读取线程,失败由队列记日志(语义同 [replaceSchemaColumns]) */
    fun replaceSchemaColumnsAsync(datasourceId: Long, dbName: String, schema: String, rows: List<CachedSchemaColumn>) =
        writeQueue.submitAsync { replaceSchemaColumnsTx(datasourceId, dbName, schema, rows) }

    private fun replaceSchemaColumnsTx(datasourceId: Long, dbName: String, schema: String, rows: List<CachedSchemaColumn>) {
        jdbc.tx { conn ->
            conn.prepareStatement("DELETE FROM meta_schema_column WHERE datasource_id=? AND db_name=? AND schema_name=?")
                .use { ps ->
                    ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                    ps.executeUpdate()
                }
            conn.prepareStatement(
                "DELETE FROM meta_cache_flag " +
                        "WHERE datasource_id=? AND db_name=? AND schema_name=? AND kind=? AND table_name<>''"
            ).use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, KIND_SCOLUMN)
                ps.executeUpdate()
            }
            insertSchemaColumns(conn, datasourceId, dbName, schema, rows)
            writeFlag(conn, datasourceId, dbName, schema, "", KIND_SCOLUMN)
            // 整库字段清单覆盖 → schema 字段总数可能过时
            deleteColumnCount(conn, datasourceId, dbName, schema)
        }
    }

    /** 单表粒度覆盖字段清单缓存(分批拉取时每批落库) */
    fun replaceSchemaTableColumns(datasourceId: Long, dbName: String, schema: String, table: String, rows: List<CachedSchemaColumn>) =
        writeQueue.submit { replaceSchemaTableColumnsTx(datasourceId, dbName, schema, table, rows) }

    /** 火忘写(方言层拦截器回填用):不阻塞读取线程,失败由队列记日志(语义同 [replaceSchemaTableColumns]) */
    fun replaceSchemaTableColumnsAsync(datasourceId: Long, dbName: String, schema: String, table: String, rows: List<CachedSchemaColumn>) =
        writeQueue.submitAsync { replaceSchemaTableColumnsTx(datasourceId, dbName, schema, table, rows) }

    private fun replaceSchemaTableColumnsTx(datasourceId: Long, dbName: String, schema: String, table: String, rows: List<CachedSchemaColumn>) {
        jdbc.tx { conn ->
            conn.prepareStatement(
                "DELETE FROM meta_schema_column WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?"
            ).use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema); ps.setString(4, table)
                ps.executeUpdate()
            }
            insertSchemaColumns(conn, datasourceId, dbName, schema, rows)
            writeFlag(conn, datasourceId, dbName, schema, table, KIND_SCOLUMN)
        }
    }

    /** 批量插入字段清单缓存行(rows 可跨多张表,表名随行携带) */
    private fun insertSchemaColumns(conn: java.sql.Connection, datasourceId: Long, dbName: String, schema: String, rows: List<CachedSchemaColumn>) {
        conn.prepareStatement(
            "INSERT INTO meta_schema_column(datasource_id, db_name, schema_name, table_name, ordinal, column_name, col_type, comment) " +
                    "VALUES (?,?,?,?,?,?,?,?)"
        ).use { ps ->
            for (c in rows) {
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, c.tableName); ps.setInt(5, c.ordinal); ps.setString(6, c.columnName)
                ps.setString(7, c.colType); ps.setString(8, c.comment)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    // ---------- 单表 DDL 与 schema 字段总数(断网降级补齐;行存在即就绪,不写 flag) ----------

    /** 读单表 DDL 缓存;无缓存返回 null(与「缓存了空 DDL」区分) */
    fun getDdl(datasourceId: Long, dbName: String, schema: String, table: String): String? =
        jdbc.queryOne(
            "SELECT ddl FROM meta_ddl WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?",
            datasourceId, dbName, schema, table
        ) { it.getString(1) }

    /** 整粒度覆盖单表 DDL 缓存 */
    fun replaceDdl(datasourceId: Long, dbName: String, schema: String, table: String, ddl: String) =
        writeQueue.submit { replaceDdlTx(datasourceId, dbName, schema, table, ddl) }

    /** 火忘写(方言层拦截器回填用):不阻塞读取线程,失败由队列记日志(语义同 [replaceDdl]) */
    fun replaceDdlAsync(datasourceId: Long, dbName: String, schema: String, table: String, ddl: String) =
        writeQueue.submitAsync { replaceDdlTx(datasourceId, dbName, schema, table, ddl) }

    private fun replaceDdlTx(datasourceId: Long, dbName: String, schema: String, table: String, ddl: String) {
        jdbc.tx { conn ->
            deleteDdl(conn, datasourceId, dbName, schema, table)
            conn.prepareStatement(
                "INSERT INTO meta_ddl(datasource_id, db_name, schema_name, table_name, ddl) VALUES (?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setString(4, table); ps.setString(5, ddl)
                ps.executeUpdate()
            }
        }
    }

    /** 读 schema 字段总数缓存;无缓存返回 null(0 是合法值) */
    fun getColumnCount(datasourceId: Long, dbName: String, schema: String): Long? =
        jdbc.queryOne(
            "SELECT column_count FROM meta_column_count WHERE datasource_id=? AND db_name=? AND schema_name=?",
            datasourceId, dbName, schema
        ) { rs -> val v = rs.getLong(1); if (rs.wasNull()) null else v }

    /** 整粒度覆盖 schema 字段总数缓存 */
    fun replaceColumnCount(datasourceId: Long, dbName: String, schema: String, count: Long) =
        writeQueue.submit { replaceColumnCountTx(datasourceId, dbName, schema, count) }

    /** 火忘写(方言层拦截器回填用):不阻塞读取线程,失败由队列记日志(语义同 [replaceColumnCount]) */
    fun replaceColumnCountAsync(datasourceId: Long, dbName: String, schema: String, count: Long) =
        writeQueue.submitAsync { replaceColumnCountTx(datasourceId, dbName, schema, count) }

    private fun replaceColumnCountTx(datasourceId: Long, dbName: String, schema: String, count: Long) {
        jdbc.tx { conn ->
            deleteColumnCount(conn, datasourceId, dbName, schema)
            conn.prepareStatement(
                "INSERT INTO meta_column_count(datasource_id, db_name, schema_name, column_count) VALUES (?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
                ps.setLong(4, count)
                ps.executeUpdate()
            }
        }
    }

    /** 失效单表 DDL(table=null 表示整 schema) */
    private fun deleteDdl(conn: java.sql.Connection, datasourceId: Long, dbName: String, schema: String, table: String?) {
        val sql = if (table == null) {
            "DELETE FROM meta_ddl WHERE datasource_id=? AND db_name=? AND schema_name=?"
        } else {
            "DELETE FROM meta_ddl WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            if (table != null) ps.setString(4, table)
            ps.executeUpdate()
        }
    }

    /** 失效 schema 字段总数 */
    private fun deleteColumnCount(conn: java.sql.Connection, datasourceId: Long, dbName: String, schema: String) {
        conn.prepareStatement(
            "DELETE FROM meta_column_count WHERE datasource_id=? AND db_name=? AND schema_name=?"
        ).use { ps ->
            ps.setLong(1, datasourceId); ps.setString(2, dbName); ps.setString(3, schema)
            ps.executeUpdate()
        }
    }

    // ---------- 级联清理 ----------

    fun deleteByDatasource(datasourceId: Long) {
        writeQueue.submit {
            jdbc.tx { conn ->
                for (table in ALL_CACHE_TABLES) {
                    conn.prepareStatement("DELETE FROM $table WHERE datasource_id=?").use { ps ->
                        ps.setLong(1, datasourceId)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }
}
