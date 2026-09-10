-- 断网降级补齐:此前每次实时的「单表建表 DDL」与「schema 字段总数」也落本地缓存
-- 浏览路径懒加载(refresh=true 回源覆盖);表/字段/索引结构覆盖刷新时按粒度失效(见 MetaCacheRepository)

CREATE TABLE IF NOT EXISTS meta_ddl (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    ddl           CLOB,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_ddl ON meta_ddl(datasource_id, db_name, schema_name, table_name);

CREATE TABLE IF NOT EXISTS meta_column_count (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    column_count  BIGINT,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_column_count ON meta_column_count(datasource_id, db_name, schema_name);
