-- 系统全局设置(单行,id 固定 1):扫描全局参数;列为 NULL 表示该项使用配置文件(config.properties / application.yml)默认值
CREATE TABLE IF NOT EXISTS system_settings (
    id                             INT PRIMARY KEY,
    scan_workers                   INT,     -- 全局扫描工作线程数
    scan_chunks_per_table          INT,     -- 每张表的分段数
    scan_row_threshold             BIGINT,  -- 超过该估算行数默认采样
    scan_size_threshold_bytes      BIGINT,  -- 超过该体积(字节)默认采样
    scan_sample_rows               BIGINT,  -- 采样行数
    scan_statement_timeout_seconds INT,     -- 单条统计 SQL 超时(秒)
    updated_at                     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
