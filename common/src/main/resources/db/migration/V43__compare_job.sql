-- 数据比对:以基准表为准,与多个目标系统的对应表按主键逐字段比对
-- compare_job 任务表 / compare_target 目标指标表 / compare_diff 差异明细表
-- 无外键(同 object_dir 惯例),删除任务由 service 层 tx 级联删三表

CREATE TABLE IF NOT EXISTS compare_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(256) NOT NULL,                 -- 任务名称(用户填写)
    base_datasource_id BIGINT NOT NULL,         -- 基准数据源
    base_db VARCHAR(256) NOT NULL DEFAULT '',   -- 无库概念方言存空串(与 meta_* 缓存口径一致)
    base_schema VARCHAR(256),
    base_table VARCHAR(256) NOT NULL,
    key_field VARCHAR(256) NOT NULL,            -- 比对主键(基准表字段,单选)
    fields_json CLOB,                           -- 比对字段名 JSON 数组(含主键)
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',  -- RUNNING/DONE/FAILED/CANCELED
    stage VARCHAR(256),
    total_units INT NOT NULL DEFAULT 0,         -- 进度单元总数 = 1(基准读取) + 目标数
    done_units INT NOT NULL DEFAULT 0,
    error CLOB,
    archived BOOLEAN NOT NULL DEFAULT FALSE,    -- 归档标记(列表默认不展示)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS compare_target (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    ds_name VARCHAR(256),                       -- 数据源名称快照(数据源改名/删除后仍可展示)
    db_name VARCHAR(256) NOT NULL DEFAULT '',
    schema_name VARCHAR(256),
    table_name VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED
    base_count INT,                             -- 基准表行数
    matched_count INT,                          -- 双侧都存在的对象数(SAME+DIFF)
    missing_count INT,                          -- 基准有目标无
    extra_count INT,                            -- 目标有基准无
    field_mismatch_count INT,                   -- 不一致字段单元格总数
    coverage DOUBLE,                            -- 对象覆盖率 = matched/base_count
    field_consistency DOUBLE,                   -- 字段一致率 = 1 - mismatch/(matched×比对字段数)
    completeness DOUBLE,                        -- 数据完整率 = 目标侧已比对单元格中非空占比
    score DOUBLE,                               -- 综合评分 = 覆盖率×0.4 + 字段一致率×0.4 + 完整率×0.2
    error CLOB
);
CREATE INDEX IF NOT EXISTS idx_compare_target_job ON compare_target(job_id);

CREATE TABLE IF NOT EXISTS compare_diff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    object_key VARCHAR(512),                    -- 比对主键值
    object_name VARCHAR(512),                   -- 显示名(第一个文本型非主键比对字段的值,无则空串)
    diff_type VARCHAR(8) NOT NULL,              -- SAME/DIFF/MISSING/EXTRA
    diff_json CLOB                              -- DIFF 行为 [{field, base, value}] JSON,其余 NULL
);
CREATE INDEX IF NOT EXISTS idx_compare_diff_job ON compare_diff(job_id, target_id, diff_type);
