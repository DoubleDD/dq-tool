-- 表标记:全局标记定义 + 表级打标关系;db_name 空串兜底口径与 table_doc 一致(无库概念的方言存空串,保证唯一键)

CREATE TABLE IF NOT EXISTS tag_def (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,             -- 全局唯一,不允许重名
    color      VARCHAR(16)  NOT NULL DEFAULT '#409EFF',
    kind       VARCHAR(8)   NOT NULL DEFAULT 'USER', -- USER 用户标记 / EMPTY 系统空表标记
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tag_def_name ON tag_def(name);

CREATE TABLE IF NOT EXISTS table_tag (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_id        BIGINT NOT NULL REFERENCES tag_def(id) ON DELETE CASCADE, -- 删除标记自动解除打标关系
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_tag ON table_tag(tag_id, datasource_id, db_name, schema_name, table_name);
CREATE INDEX IF NOT EXISTS idx_table_tag_table ON table_tag(datasource_id, db_name, schema_name);

-- 系统「空表」标记:扫描完成后按行数自动打/摘,用户不可编辑;按名称幂等插入,老库升级同样生效
MERGE INTO tag_def(name, color, kind) KEY(name) VALUES ('空表', '#909399', 'EMPTY');
