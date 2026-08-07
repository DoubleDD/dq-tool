-- H2 本地存储:数据源配置 + 扫描任务/表/分段/字段结果

CREATE TABLE IF NOT EXISTS data_source (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    db_type       VARCHAR(32)  NOT NULL,          -- MYSQL / POSTGRESQL / DM / KINGBASE / OCEANBASE
    jdbc_url      VARCHAR(1024) NOT NULL,
    username      VARCHAR(128),
    password_enc  VARCHAR(2048),                  -- 对称加密后的密码
    row_threshold BIGINT,                         -- 可空,覆盖全局采样行数阈值
    size_threshold_bytes BIGINT,                  -- 可空,覆盖全局采样体积阈值
    db_mode       VARCHAR(32),                    -- 可空,保存时探测的数据库兼容模式(如 Kingbase 的 pg/oracle/mysql)
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scan_job (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256),                     -- 目标数据库(仅 SQL Server 等多库方言,可空)
    schema_name   VARCHAR(256) NOT NULL,
    status        VARCHAR(16)  NOT NULL,          -- PENDING/RUNNING/DONE/FAILED/CANCELED/INTERRUPTED
    force_full    BOOLEAN DEFAULT FALSE,
    null_rules    CLOB,                           -- JSON:[{column,values[]}]
    total_tables  INT DEFAULT 0,
    done_tables   INT DEFAULT 0,
    error         VARCHAR(4000),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_scan_job_ds ON scan_job(datasource_id);

-- 任务状态变更事件:创建/开始/继续/完成/取消/中断等,供历史列表展示时间线
CREATE TABLE IF NOT EXISTS scan_job_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id     BIGINT NOT NULL,
    status     VARCHAR(16) NOT NULL,                -- 事件发生后的任务状态
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_scan_job_event_job ON scan_job_event(job_id);

-- 库列表页统计缓存:表数量/占用空间,首次进页面从业务库元数据拉取落库,之后只读本地,扫描创建时刷新对应 schema
CREATE TABLE IF NOT EXISTS schema_stat (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256),                   -- 与 scan_job 口径一致,可空
    schema_name   VARCHAR(256) NOT NULL,
    table_count   INT,
    size_bytes    BIGINT,                          -- 数据+索引总字节
    refreshed_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_schema_stat_ds ON schema_stat(datasource_id, db_name, schema_name);

CREATE TABLE IF NOT EXISTS scan_table (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    status        VARCHAR(16)  NOT NULL,          -- PENDING/RUNNING/DONE/FAILED/CANCELED
    sampled       BOOLEAN DEFAULT FALSE,
    sample_rows   BIGINT,
    est_rows      BIGINT,                          -- 元数据估算行数
    size_bytes    BIGINT,                          -- 数据+索引总字节
    chunk_key     VARCHAR(256),                    -- 分段键列名,可空(无键表)
    comment       VARCHAR(1024),                   -- 表注释
    storage_info  VARCHAR(128),                    -- 引擎(MySQL/OB)或表空间(PG/金仓/达梦)
    total_chunks  INT DEFAULT 0,
    done_chunks   INT DEFAULT 0,
    scanned_rows  BIGINT DEFAULT 0,
    total_rows    BIGINT,                          -- 统计得到的精确总行数(完成后)
    error         VARCHAR(4000),
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_scan_table_job ON scan_table(job_id);

CREATE TABLE IF NOT EXISTS scan_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_table_id BIGINT NOT NULL,
    seq           INT NOT NULL,                    -- 段序号
    range_start   VARCHAR(512),                    -- 分段键起点(含),字符串化存储
    range_end     VARCHAR(512),                    -- 分段键终点(不含,末段为 NULL 表示无上界)
    null_chunk    BOOLEAN DEFAULT FALSE,           -- 是否为"分段键 IS NULL"补充分段
    status        VARCHAR(16) NOT NULL,            -- PENDING/RUNNING/DONE/FAILED/CANCELED
    row_count     BIGINT DEFAULT 0,
    col_stats     CLOB,                            -- JSON:[{column,nullCount,emptyCount,ruleHitCount}]
    attempts      INT DEFAULT 0,
    error         VARCHAR(4000)
);
CREATE INDEX IF NOT EXISTS idx_scan_chunk_table ON scan_chunk(scan_table_id);

CREATE TABLE IF NOT EXISTS scan_column (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_table_id BIGINT NOT NULL,
    column_name   VARCHAR(256) NOT NULL,
    column_type   VARCHAR(256),                      -- 带长度/精度的展示类型
    column_comment VARCHAR(1024),                    -- 字段注释
    nullable      BOOLEAN,
    default_value VARCHAR(1024),
    key_label     VARCHAR(8),                        -- PK / UNI / 空
    total_rows    BIGINT DEFAULT 0,
    null_count    BIGINT DEFAULT 0,
    empty_count   BIGINT DEFAULT 0,
    rule_hit_count BIGINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_scan_column_table ON scan_column(scan_table_id);

-- AI 表说明:大模型接口配置(单行,id 固定 1)+ 已生成的表说明
CREATE TABLE IF NOT EXISTS ai_config (
    id           BIGINT PRIMARY KEY,
    base_url     VARCHAR(1024),                   -- OpenAI 兼容接口地址,如 https://api.deepseek.com
    api_key_enc  VARCHAR(2048),                   -- 对称加密后的 API key
    model        VARCHAR(128),
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS table_doc (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '', -- 无库概念的方言存空串,保证唯一键
    schema_name   VARCHAR(256) NOT NULL,
    table_name    VARCHAR(256) NOT NULL,
    description   CLOB,                             -- 大模型生成的表说明
    model         VARCHAR(128),                     -- 生成时用的模型
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_doc ON table_doc(datasource_id, db_name, schema_name, table_name);

