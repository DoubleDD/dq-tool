package com.example.dq.repository

/** 表所属系统:一张表最多归属一个系统,与标记体系独立(只读本地 H2,不连业务库) */
class TableSystemRepository(private val jdbc: Jdbc) {

    /** 某 schema 下全部表的所属系统:表名 -> 系统名 */
    fun findBySchema(datasourceId: Long, dbName: String, schema: String): Map<String, String> {
        val map = HashMap<String, String>()
        jdbc.query("SELECT table_name, system_name FROM table_system WHERE datasource_id=? AND db_name=? AND schema_name=?",
            datasourceId, dbName, schema) { rs ->
            map[rs.getString("table_name")] = rs.getString("system_name")
        }
        return map
    }

    /** 导出用:全量所属系统行 */
    data class TableSystemEntry(val datasourceId: Long, val dbName: String, val schemaName: String,
                                val tableName: String, val systemName: String)

    fun findAll(): List<TableSystemEntry> =
        jdbc.query("SELECT datasource_id, db_name, schema_name, table_name, system_name FROM table_system " +
                "ORDER BY datasource_id, db_name, schema_name, table_name") { rs ->
            TableSystemEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
        }

    /** 设置/覆盖单表所属系统(幂等,唯一键兜底) */
    fun upsert(datasourceId: Long, dbName: String, schema: String, table: String, systemName: String) {
        jdbc.update("MERGE INTO table_system(datasource_id, db_name, schema_name, table_name, system_name, updated_at) " +
                "KEY(datasource_id, db_name, schema_name, table_name) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
            datasourceId, dbName, schema, table, systemName)
    }

    /** 批量清除指定表的所属系统,返回删除数 */
    fun delete(datasourceId: Long, dbName: String, schema: String, tables: Collection<String>): Int {
        if (tables.isEmpty()) {
            return 0
        }
        val placeholders = tables.joinToString(",") { "?" }
        return jdbc.update("DELETE FROM table_system WHERE datasource_id=? AND db_name=? AND schema_name=? " +
                "AND table_name IN ($placeholders)",
            datasourceId, dbName, schema, *tables.toTypedArray())
    }
}
