-- 人工采集:重点关注的表收藏清单;db_name 空串兜底口径与 table_tag 一致(无库概念的方言存空串,保证唯一键)

CREATE TABLE IF NOT EXISTS manual_collect (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    table_comment VARCHAR(1024),             -- 采集时的表注释快照(业务库注释可能随后变化,列表展示用)
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_manual_collect ON manual_collect(datasource_id, db_name, schema_name, table_name);
