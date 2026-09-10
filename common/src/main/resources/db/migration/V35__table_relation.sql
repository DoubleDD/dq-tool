-- ER 关系推导:表间关系(候选/确认/否决三态)+ 推导任务
-- 无外键(同 manual_collect 惯例,数据源删除后关系保留);db_name 空串兜底口径与 manual_collect 一致(无库概念方言存空串,保证唯一键)

CREATE TABLE IF NOT EXISTS table_relation (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    schema_name   VARCHAR(256) NOT NULL,
    one_table     VARCHAR(256) NOT NULL,      -- 唯一侧表(一的一方;ONE_TO_ONE/SUSPECT_MANY_TO_MANY 时按 (表名,字段名) 字典序小的一侧,方向归一化保证唯一键幂等)
    one_column    VARCHAR(256) NOT NULL,      -- 唯一侧字段
    many_table    VARCHAR(256) NOT NULL,      -- 重复侧表(多的一方)
    many_column   VARCHAR(256) NOT NULL,      -- 重复侧字段
    cardinality   VARCHAR(32) NOT NULL,       -- ONE_TO_ONE/ONE_TO_MANY/SUSPECT_MANY_TO_MANY
    status        VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',  -- CANDIDATE/CONFIRMED/REJECTED
    source        VARCHAR(16) NOT NULL,       -- NAME_MATCH/SEMANTIC/MANUAL
    confidence    VARCHAR(8),                 -- HIGH/MEDIUM/LOW,可空(手动补充的关系无置信度)
    overlap_ratio DOUBLE,                     -- 值交集率(0~1),可空
    remark        VARCHAR(1024),              -- 备注(如「疑似多对多」原因说明)
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_relation ON table_relation(datasource_id, db_name, schema_name, one_table, one_column, many_table, many_column);
-- 星型图/按表过滤:关系两端各一条索引
CREATE INDEX IF NOT EXISTS idx_table_relation_one ON table_relation(datasource_id, db_name, schema_name, one_table);
CREATE INDEX IF NOT EXISTS idx_table_relation_many ON table_relation(datasource_id, db_name, schema_name, many_table);

CREATE TABLE IF NOT EXISTS relation_infer_job (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id  BIGINT NOT NULL,
    db_name        VARCHAR(256) NOT NULL DEFAULT '',
    schema_name    VARCHAR(256) NOT NULL,
    anchor_table   VARCHAR(256) NOT NULL,
    anchor_columns VARCHAR(2048) NOT NULL,    -- 逗号分隔的锚点字段
    use_semantic   BOOLEAN NOT NULL DEFAULT FALSE,
    status         VARCHAR(16) NOT NULL DEFAULT 'RUNNING',  -- RUNNING/DONE/FAILED
    stage          VARCHAR(32),               -- NAME_MATCH/SEMANTIC_TABLE/SEMANTIC_COLUMN/VERIFY
    total_steps    INT NOT NULL DEFAULT 0,
    done_steps     INT NOT NULL DEFAULT 0,
    found_count    INT NOT NULL DEFAULT 0,
    error          VARCHAR(2048),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_relation_infer_job_schema ON relation_infer_job(datasource_id, db_name, schema_name);
