package com.example.dq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/** AI 生成的表说明 */
@Repository
public class TableDocRepository {

    private final JdbcTemplate jdbc;

    public TableDocRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 某 schema 下全部表说明:表名 -> 说明文字 */
    public Map<String, String> findBySchema(long datasourceId, String dbName, String schema) {
        Map<String, String> map = new HashMap<>();
        jdbc.query("SELECT table_name, description FROM table_doc WHERE datasource_id=? AND db_name=? AND schema_name=?",
                rs -> {
                    map.put(rs.getString("table_name"), rs.getString("description"));
                }, datasourceId, dbName, schema);
        return map;
    }

    public void upsert(long datasourceId, String dbName, String schema, String table, String description, String model) {
        jdbc.update("MERGE INTO table_doc(datasource_id, db_name, schema_name, table_name, description, model, updated_at) "
                        + "KEY(datasource_id, db_name, schema_name, table_name) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                datasourceId, dbName, schema, table, description, model);
    }

    /** 手动编辑:只改描述文字,保留原生成模型标记 */
    public void updateDescription(long datasourceId, String dbName, String schema, String table, String description) {
        jdbc.update("UPDATE table_doc SET description=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE datasource_id=? AND db_name=? AND schema_name=? AND table_name=?",
                description, datasourceId, dbName, schema, table);
    }
}
