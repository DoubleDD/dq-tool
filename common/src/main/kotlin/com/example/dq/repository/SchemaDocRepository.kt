package com.example.dq.repository

/** 库级描述(库列表页可编辑,供 Word 报告「实例描述」列) */
class SchemaDocRepository(private val jdbc: Jdbc) {

    /** 某数据源(指定库)下全部库描述:schema 名 -> 描述 */
    fun findByDatasource(datasourceId: Long, dbName: String): Map<String, String> {
        val map = HashMap<String, String>()
        jdbc.query("SELECT schema_name, description FROM schema_doc WHERE datasource_id=? AND db_name=?",
            datasourceId, dbName) { rs ->
            map[rs.getString("schema_name")] = rs.getString("description")
        }
        return map
    }

    fun upsert(datasourceId: Long, dbName: String, schema: String, description: String) {
        jdbc.update("MERGE INTO schema_doc(datasource_id, db_name, schema_name, description, updated_at) " +
                "KEY(datasource_id, db_name, schema_name) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
            datasourceId, dbName, schema, description)
    }

    fun delete(datasourceId: Long, dbName: String, schema: String) {
        jdbc.update("DELETE FROM schema_doc WHERE datasource_id=? AND db_name=? AND schema_name=?",
            datasourceId, dbName, schema)
    }
}
