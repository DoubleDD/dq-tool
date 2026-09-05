package com.example.dq.repository

/**
 * 结构元数据本地缓存(meta_database / meta_table / meta_column / meta_index / meta_schema_column):
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
 * 并发安全:覆盖刷新是「先 DELETE 后 INSERT」,两个线程并发刷同一粒度
 * (如导出表结构文档与扫描 planTable 并发回源同一表)会互相踩唯一键(23505)。
 * 内嵌 H2 单进程,用条纹锁按粒度键串行化三个 replace* 方法即可。
 */
class MetaCacheRepository(private val jdbc: Jdbc) {

    /** 条纹锁:按 粒度键 hash 取锁,串行化同粒度并发覆盖刷新 */
    private val stripes = Array(STRIPE_COUNT) { Any() }

    /** 粒度键:kind 前缀区分表清单(schema 级)与单表字段/索引,避免无关刷新互斥 */
    private fun lockKey(kind: String, datasourceId: Long, dbName: String, schema: String, table: String): Any {
        val key = "$kind|$datasourceId|$dbName|$schema|$table"
        return stripes[(key.hashCode() and Int.MAX_VALUE) % STRIPE_COUNT]
    }

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
        private const val STRIPE_COUNT = 64
    }

    // ---------- 库/schema 清单(meta_database,缓存存全量,白名单在读取路径过滤) ----------

    /** 读清单缓存(库清单 dbName 传空串;schema 清单传所属库名),按方言返回顺序(ordinal)排序 */
    fun listNames(datasourceId: Long, dbName: String): List<String> =
        jdbc.query(
            "SELECT name FROM meta_database WHERE datasource_id=? AND db_name=? ORDER BY ordinal",
            datasourceId, dbName
        ) { it.getString(1) }

    /** 整粒度覆盖数据源级库清单缓存 */
    fun replaceDatabases(datasourceId: Long, names: List<String>) {
        synchronized(lockKey(KIND_DATABASE, datasourceId, "", "", "")) {
            jdbc.tx { conn ->
                deleteNames(conn, datasourceId, "")
                insertNames(conn, datasourceId, "", names)
                writeFlag(conn, datasourceId, "", "", "", KIND_DATABASE)
            }
        }
    }

    /** 整粒度覆盖某库的 schema 清单缓存(单库方言 dbName 空串) */
    fun replaceSchemas(datasourceId: Long, dbName: String, names: List<String>) {
        synchronized(lockKey(KIND_SCHEMA, datasourceId, dbName, "", "")) {
            jdbc.tx { conn ->
                deleteNames(conn, datasourceId, dbName)
                insertNames(conn, datasourceId, dbName, names)
                writeFlag(conn, datasourceId, dbName, "", "", KIND_SCHEMA)
            }
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
    fun setNoColumns(datasourceId: Long, dbName: String, schema: String, table: String, noColumns: Boolean) {
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
    fun replaceTables(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) {
        synchronized(lockKey(KIND_TABLE, datasourceId, dbName, schema, "")) {
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
    fun replaceColumns(datasourceId: Long, dbName: String, schema: String, table: String, columns: List<CachedColumn>) {
        synchronized(lockKey(KIND_COLUMN, datasourceId, dbName, schema, table)) {
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
            }
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
    fun replaceIndexes(datasourceId: Long, dbName: String, schema: String, table: String, indexes: List<CachedIndex>) {
        synchronized(lockKey(KIND_INDEX, datasourceId, dbName, schema, table)) {
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
            }
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
    fun replaceSchemaColumns(datasourceId: Long, dbName: String, schema: String, rows: List<CachedSchemaColumn>) {
        synchronized(lockKey(KIND_SCOLUMN, datasourceId, dbName, schema, "")) {
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
            }
        }
    }

    /** 单表粒度覆盖字段清单缓存(分批拉取时每批落库) */
    fun replaceSchemaTableColumns(datasourceId: Long, dbName: String, schema: String, table: String, rows: List<CachedSchemaColumn>) {
        synchronized(lockKey(KIND_SCOLUMN, datasourceId, dbName, schema, table)) {
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

    // ---------- 级联清理 ----------

    fun deleteByDatasource(datasourceId: Long) {
        jdbc.update("DELETE FROM meta_database WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_table WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_column WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_index WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_schema_column WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_cache_flag WHERE datasource_id=?", datasourceId)
}
}
