-- SQL 控制台整库字段清单本地缓存(listSchemaColumns 的 lite 版:表名/字段名/展示类型/注释)
-- 与 meta_column(单表结构明细)互不干扰;缓存存在标记复用 meta_cache_flag,kind=SCOLUMN
CREATE TABLE IF NOT EXISTS meta_schema_column (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    ordinal       INT NOT NULL,
    column_name   VARCHAR(256) NOT NULL,
    col_type      VARCHAR(128),
    comment       VARCHAR(1024)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_schema_column ON meta_schema_column(datasource_id, db_name, schema_name, table_name, column_name);
