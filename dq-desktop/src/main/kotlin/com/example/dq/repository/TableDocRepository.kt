package com.example.dq.repository

/** AI 生成的表说明 */
class TableDocRepository(private val jdbc: Jdbc) {

    /** 某 schema 下全部表说明:表名 -> 说明文字 */
    fun findBySchema(datasourceId: Long, dbName: String, schema: String): Map<String, String> {
        val map = HashMap<String, String>()
        jdbc.query("SELECT table_name, description FROM table_doc WHERE datasource_id=? AND db_name=? AND schema_name=?",
            datasourceId, dbName, schema) { rs ->
            map[rs.getString("table_name")] = rs.getString("description")
        }
        return map
    }

    fun upsert(datasourceId: Long, dbName: String, schema: String, table: String, description: String, model: String) {
        jdbc.update("MERGE INTO table_doc(datasource_id, db_name, schema_name, table_name, description, model, updated_at) " +
                "KEY(datasource_id, db_name, schema_name, table_name) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            datasourceId, dbName, schema, table, description, model)
    }

    /** 手动编辑:只改描述文字,保留原生成模型标记 */
    fun updateDescription(datasourceId: Long, dbName: String, schema: String, table: String, description: String) {
        jdbc.update("UPDATE table_doc SET description=?, updated_at=CURRENT_TIMESTAMP " +
                "WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?",
            description, datasourceId, dbName, schema, table)
    }
}
