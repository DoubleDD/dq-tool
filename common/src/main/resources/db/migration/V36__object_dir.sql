-- 对象管理(数据目录):按数据源隔离的目录树 + 表挂载 + 挂载表的关系表清单
-- 无外键(同 manual_collect 惯例,数据源删除后记录保留);db_name 空串兜底口径与 manual_collect 一致(无库概念方言存空串,保证唯一键)

CREATE TABLE IF NOT EXISTS object_dir (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    parent_id     BIGINT NOT NULL DEFAULT 0,   -- 父目录 id,0=根(根不是真实行,保证唯一索引可用)
    name          VARCHAR(256) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_object_dir ON object_dir(datasource_id, parent_id, name);

CREATE TABLE IF NOT EXISTS object_table (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    dir_id      BIGINT NOT NULL,               -- 所属目录 object_dir.id
    db_name     VARCHAR(256) NOT NULL DEFAULT '',
    schema_name VARCHAR(256) NOT NULL,
    table_name  VARCHAR(256) NOT NULL,
    remark      VARCHAR(1024),                 -- 挂载备注(自由文本)
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_object_table ON object_table(dir_id, db_name, schema_name, table_name);

CREATE TABLE IF NOT EXISTS object_table_rel (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    object_table_id BIGINT NOT NULL,           -- 所属挂载记录 object_table.id
    db_name         VARCHAR(256) NOT NULL DEFAULT '',
    schema_name     VARCHAR(256) NOT NULL,
    table_name      VARCHAR(256) NOT NULL,
    remark          VARCHAR(1024),             -- 关系备注(如关联字段/业务含义)
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_object_table_rel ON object_table_rel(object_table_id, db_name, schema_name, table_name);
