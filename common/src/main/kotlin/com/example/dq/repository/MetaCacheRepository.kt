package com.example.dq.repository

/**
 * 结构元数据本地缓存(meta_table / meta_column / meta_index):
 * 浏览路径懒加载 + 手动刷新/扫描时同步刷新;刷新语义为整粒度覆盖(delete + insert)。
 * db_name 已由调用方 normalize:无库概念方言存空串(与 schema_doc/table_doc 口径一致)。
 */
class MetaCacheRepository(private val jdbc: Jdbc) {

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

    /** 整粒度覆盖某 schema 的表缓存(首次拉取或手动/扫描刷新) */
    fun replaceTables(datasourceId: Long, dbName: String, schema: String, tables: List<CachedTable>) {
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

    // ---------- 级联清理 ----------

    fun deleteByDatasource(datasourceId: Long) {
        jdbc.update("DELETE FROM meta_table WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_column WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_index WHERE datasource_id=?", datasourceId)
        jdbc.update("DELETE FROM meta_cache_flag WHERE datasource_id=?", datasourceId)
}
}
