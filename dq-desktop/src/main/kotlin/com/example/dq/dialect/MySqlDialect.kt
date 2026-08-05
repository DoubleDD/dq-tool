package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.DbType
import com.example.dq.model.TableStat

import java.sql.Connection
import java.sql.SQLException

/** MySQL 方言 */
open class MySqlDialect : AbstractDialect() {

    override fun type(): DbType {
        return DbType.MYSQL
    }

    override fun driverClassName(): String {
        return "com.mysql.cj.jdbc.Driver"
    }

    override fun quote(identifier: String): String {
        return "`" + identifier.replace("`", "``") + "`"
    }

    override fun catalogBased(): Boolean {
        return true
    }

    @Throws(SQLException::class)
    override fun listSchemas(conn: Connection): List<String> {
        val schemas = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                    "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME").use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        return schemas
    }

    @Throws(SQLException::class)
    override fun listTables(conn: Connection, schema: String): List<TableStat> {
        val tables = ArrayList<TableStat>()
        conn.prepareStatement(
                "SELECT TABLE_NAME, TABLE_ROWS, COALESCE(DATA_LENGTH,0) + COALESCE(INDEX_LENGTH,0), " +
                        "COALESCE(TABLE_COMMENT,''), COALESCE(ENGINE,'') " +
                        "FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME").use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    tables.add(TableStat(rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getString(4), rs.getString(5)))
                }
            }
        }
        return tables
    }

    @Throws(SQLException::class)
    override fun countTablesBySchema(conn: Connection): Map<String, Int> {
        return queryCountByGroup(conn,
                "SELECT TABLE_SCHEMA, COUNT(*) FROM information_schema.TABLES " +
                        "WHERE TABLE_TYPE = 'BASE TABLE' GROUP BY TABLE_SCHEMA")
    }

    @Throws(SQLException::class)
    override fun sumSizeBySchema(conn: Connection): Map<String, Long> {
        return queryLongByGroup(conn,
                "SELECT TABLE_SCHEMA, SUM(COALESCE(DATA_LENGTH,0) + COALESCE(INDEX_LENGTH,0)) " +
                        "FROM information_schema.TABLES WHERE TABLE_TYPE = 'BASE TABLE' GROUP BY TABLE_SCHEMA")
    }

    /** MySQL 走 information_schema 拿准确的字段注释与完整类型(含长度) */
    @Throws(SQLException::class)
    override fun listColumns(conn: Connection, schema: String, table: String): List<ColumnMeta> {
        val base = LinkedHashMap<String, ColumnMeta>()
        for (c in super.listColumns(conn, schema, table)) {
            base[c.name] = c
        }
        conn.prepareStatement(
                "SELECT COLUMN_NAME, COLUMN_TYPE, COALESCE(COLUMN_COMMENT,''), IS_NULLABLE, COLUMN_DEFAULT " +
                        "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?").use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val c = base[rs.getString(1)]
                    if (c == null) continue
                    base[c.name] = ColumnMeta(
                            c.name, c.typeName, rs.getString(2), c.jdbcType,
                            "YES".equals(rs.getString(4), ignoreCase = true), rs.getString(5),
                            rs.getString(3), c.primaryKey, c.pkSeq, c.uniqueIndexFirst)
                }
            }
        }
        return ArrayList(base.values)
    }
}
