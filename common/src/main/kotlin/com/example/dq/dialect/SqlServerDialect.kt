package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.DbType
import com.example.dq.model.TableStat

import java.sql.Connection
import java.sql.SQLException

/**
 * SQL Server 方言。
 * "库"按 schema 处理(dbo 等);数据库(DATABASE)在界面上选择,
 * 通过 [useDatabase] 切换连接 catalog,jdbcUrl 中的 databaseName 仅作默认库。
 * 注释走扩展属性 MS_Description。
 * 按主版本号选择语法特性:
 * - 分页:2012(11)起 OFFSET/FETCH;2008/2008R2(10)用 ROW_NUMBER/TOP
 */
class SqlServerDialect : AbstractDialect() {

    companion object {
        /** SQL Server 主版本号(10=2008/2008R2、11=2012、15=2019、16=2022),决定可用语法特性 */
        @Throws(SQLException::class)
        internal fun sqlServerMajor(conn: Connection): Int {
            return conn.metaData.databaseMajorVersion
        }
    }

    override fun type(): DbType {
        return DbType.SQLSERVER
    }

    override fun driverClassName(): String {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    }

    override fun quote(identifier: String): String {
        return "[" + identifier.replace("]", "]]") + "]"
    }

    /** SQL Server 先选库再选 schema,库过滤白名单只作用于 listDatabases 层级 */
    override fun supportsMultiDatabase(): Boolean = true

    /** 在线且有访问权限的数据库(保留 master,用户表可能建在里面) */
    @Throws(SQLException::class)
    override fun listDatabases(conn: Connection): List<String> {
        val databases = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                    "SELECT name FROM sys.databases WHERE state = 0 AND name <> 'tempdb' " +
                            "AND HAS_DBACCESS(name) = 1 ORDER BY name").use { rs ->
                while (rs.next()) {
                    databases.add(rs.getString(1))
                }
            }
        }
        return databases
    }

    /** 切换当前库;连接池不重置 catalog,每条使用路径都要在借出后显式调用 */
    @Throws(SQLException::class)
    override fun useDatabase(conn: Connection, database: String?) {
        if (!database.isNullOrBlank()) {
            conn.catalog = database
        }
    }

    @Throws(SQLException::class)
    override fun listSchemas(conn: Connection): List<String> {
        val schemas = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                    "SELECT name FROM sys.schemas WHERE name NOT IN " +
                            "('sys','INFORMATION_SCHEMA','guest','db_owner','db_accessadmin','db_securityadmin'," +
                            "'db_ddladmin','db_backupoperator','db_datareader','db_datawriter','db_denydatareader'," +
                            "'db_denydatawriter') ORDER BY name").use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        return schemas
    }

    @Throws(SQLException::class)
    override fun countTablesBySchema(conn: Connection): Map<String, Int> {
        return queryCountByGroup(conn,
                "SELECT s.name, COUNT(*) FROM sys.tables t " +
                        "JOIN sys.schemas s ON s.schema_id = t.schema_id GROUP BY s.name")
    }

    /** 与 listTables 同口径:全部(含索引)分区的已用页 × 8KB */
    @Throws(SQLException::class)
    override fun sumSizeBySchema(conn: Connection): Map<String, Long> {
        return queryLongByGroup(conn,
                "SELECT s.name, SUM(d.used_page_count) * 8192 FROM sys.dm_db_partition_stats d " +
                        "JOIN sys.tables t ON t.object_id = d.object_id " +
                        "JOIN sys.schemas s ON s.schema_id = t.schema_id GROUP BY s.name")
    }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> {
        val tables = ArrayList<TableStat>()
        val sql = "SELECT t.name, " +
                "(SELECT SUM(d.row_count) FROM sys.dm_db_partition_stats d " +
                "  WHERE d.object_id = t.object_id AND d.index_id IN (0,1)), " +
                "(SELECT SUM(d.used_page_count) * 8192 FROM sys.dm_db_partition_stats d " +
                "  WHERE d.object_id = t.object_id), " +
                "CAST((SELECT ep.value FROM sys.extended_properties ep " +
                "  WHERE ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description') AS NVARCHAR(4000)) " +
                "FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id " +
                "WHERE s.name = ? ORDER BY t.name"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val rows = rs.getLong(2)
                    val estRows: Long? = if (rs.wasNull()) null else rows
                    val bytes = rs.getLong(3)
                    val sizeBytes: Long? = if (rs.wasNull()) null else bytes
                    val comment = rs.getString(4)
                    tables.add(TableStat(rs.getString(1), estRows, sizeBytes,
                            comment ?: "", ""))
                }
            }
        }
        return tables
    }

    /** 字段注释走扩展属性 MS_Description(minor_id = column_id) */
    @Throws(SQLException::class)
    override fun listColumns(conn: Connection, schema: String, table: String): List<ColumnMeta> {
        val base = LinkedHashMap<String, ColumnMeta>()
        for (c in super.listColumns(conn, schema, table)) {
            base[c.name] = c
        }
        val sql = "SELECT c.name, CAST(ep.value AS NVARCHAR(4000)) " +
                "FROM sys.columns c " +
                "JOIN sys.tables t ON t.object_id = c.object_id " +
                "JOIN sys.schemas s ON s.schema_id = t.schema_id " +
                "LEFT JOIN sys.extended_properties ep ON ep.major_id = c.object_id " +
                "  AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' " +
                "WHERE s.name = ? AND t.name = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val c = base[rs.getString(1)]
                    if (c == null) continue
                    val comment = rs.getString(2)
                    base[c.name] = ColumnMeta(c.name, c.typeName, c.displayType, c.jdbcType,
                            c.nullable, c.defaultValue, comment ?: "",
                            c.primaryKey, c.pkSeq, c.uniqueIndexFirst)
                }
            }
        }
        return ArrayList(base.values)
    }

    override fun limitClause(n: Long): String {
        return " OFFSET 0 ROWS FETCH NEXT $n ROWS ONLY"
    }

    override fun boundarySuffix(offset: Long): String {
        return " OFFSET $offset ROWS FETCH NEXT 1 ROWS ONLY"
    }

    @Throws(SQLException::class)
    override fun boundaryQuery(conn: Connection, qTable: String, qKey: String,
                               prev: String?, offset: Long): String {
        return boundaryQuerySql(qTable, qKey, prev, offset, sqlServerMajor(conn))
    }

    /** 2012(11)起 OFFSET/FETCH;2008/2008R2 用 ROW_NUMBER 包装取第 offset 行。prev 非空时为 seek 定位(见 AbstractDialect.boundaryQuery) */
    internal fun boundaryQuerySql(qTable: String, qKey: String, prev: String?, offset: Long, major: Int): String {
        val seek = if (prev == null) "" else " AND " + qKey + " > " + quoteString(prev)
        if (major >= 11) {
            return "SELECT " + qKey + " FROM " + qTable +
                    " WHERE " + qKey + " IS NOT NULL" + seek +
                    " ORDER BY " + qKey +
                    boundarySuffix(offset)
        }
        return "SELECT " + qKey + " FROM (SELECT " + qKey + ", " +
                "ROW_NUMBER() OVER (ORDER BY " + qKey + ") dq_rn FROM " + qTable +
                " WHERE " + qKey + " IS NOT NULL" + seek + ") dq_inner WHERE dq_rn = " + (offset + 1)
    }

    @Throws(SQLException::class)
    override fun nullChunkProbeQuery(conn: Connection, qTable: String, qKey: String): String {
        return nullChunkProbeSql(qTable, qKey, sqlServerMajor(conn))
    }

    /** 2012(11)起 OFFSET/FETCH;2008/2008R2 用 TOP */
    internal fun nullChunkProbeSql(qTable: String, qKey: String, major: Int): String {
        if (major >= 11) {
            return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL" +
                    " ORDER BY " + qKey + limitClause(1)
        }
        return "SELECT TOP 1 1 FROM " + qTable + " WHERE " + qKey + " IS NULL"
    }

    /** SQL Server 原生 TABLESAMPLE(按行数,近似) */
    override fun sampledFrom(qualifiedTable: String, sampleRows: Long, estRows: Long?): String {
        return qualifiedTable + " TABLESAMPLE (" + maxOf(1L, sampleRows) + " ROWS)"
    }

    override fun sampledLimit(sampleRows: Long): String {
        return ""
    }

    /** TOP 语法全版本可用;limitClause 的 OFFSET/FETCH 必须配 ORDER BY,不适合无排序抽样 */
    override fun sampleRowsSql(schema: String, table: String, columns: List<String>, limit: Int): String {
        val cols = if (columns.isEmpty()) "*" else columns.joinToString(", ") { quote(it) }
        return "SELECT TOP $limit $cols FROM " + qualifiedTable(schema, table)
    }
}
