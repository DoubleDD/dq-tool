-- 结构元数据本地缓存(库/表/字段/索引):浏览路径懒加载 + 手动刷新/扫描时同步刷新
-- 口径:db_name 无库概念方言存空串(与 schema_doc/table_doc 一致,保证唯一键);刷新时整粒度覆盖(delete+insert)

-- 表级元数据缓存(表清单/注释/存储引擎/估算行数/体积)
CREATE TABLE IF NOT EXISTS meta_table (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    comment       VARCHAR(1024),
    storage_info  VARCHAR(256),
    est_rows      BIGINT,
    size_bytes    BIGINT,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_table ON meta_table(datasource_id, db_name, schema_name, table_name);

-- 字段元数据缓存(ordinal 为字段顺序,与 listColumns 返回一致)
CREATE TABLE IF NOT EXISTS meta_column (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id      BIGINT NOT NULL,
    db_name            VARCHAR(256) NOT NULL DEFAULT '',
    schema_name        VARCHAR(256) NOT NULL,
    table_name         VARCHAR(256) NOT NULL,
    ordinal            INT NOT NULL,
    column_name        VARCHAR(256) NOT NULL,
    type_name          VARCHAR(64),
    display_type       VARCHAR(128),
    jdbc_type          INT,
    nullable           BOOLEAN,
    default_value      VARCHAR(1024),
    comment            VARCHAR(1024),
    primary_key        BOOLEAN,
    pk_seq             INT,
    unique_index_first BOOLEAN
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_column ON meta_column(datasource_id, db_name, schema_name, table_name, column_name);

-- 索引元数据缓存(索引列展开为多行,ordinal 为索引内列顺序)
CREATE TABLE IF NOT EXISTS meta_index (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    index_name    VARCHAR(256) NOT NULL,
    is_unique     BOOLEAN,
    ordinal       INT NOT NULL,
    column_name   VARCHAR(256) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_index ON meta_index(datasource_id, db_name, schema_name, table_name, index_name, ordinal);

-- 缓存存在标记:区分「未缓存」与「已缓存但为空」(如表无索引)。
-- table_name 空串 = schema 级(表清单);kind = TABLE / COLUMN / INDEX
CREATE TABLE IF NOT EXISTS meta_cache_flag (
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL DEFAULT '',
    kind          VARCHAR(16) NOT NULL,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (datasource_id, db_name, schema_name, table_name, kind)
);
