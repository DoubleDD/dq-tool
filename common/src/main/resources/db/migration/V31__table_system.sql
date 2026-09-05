-- 表所属系统:一张表最多归属一个系统,与标记体系独立;db_name 空串兜底口径与 table_doc 一致(无库概念的方言存空串,保证唯一键)

CREATE TABLE IF NOT EXISTS table_system (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    system_name   VARCHAR(200) NOT NULL,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_system ON table_system(datasource_id, db_name, schema_name, table_name);
