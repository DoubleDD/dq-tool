-- 库级描述:库列表页可编辑,供 Word 报告「实例描述」列;db_name 空串兜底口径与 table_doc 一致(无库概念的方言存空串,保证唯一键)

CREATE TABLE IF NOT EXISTS schema_doc (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    description   VARCHAR(512) NOT NULL DEFAULT '',
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_schema_doc ON schema_doc(datasource_id, db_name, schema_name);
