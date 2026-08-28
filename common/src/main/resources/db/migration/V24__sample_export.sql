-- 表格批量导入数据源 + 表数据抽样导出:任务表/明细表 + data_source 连接状态标记
-- data_source 连接状态标记(批量导入用;conn_error 修复后保留作历史)
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS conn_status VARCHAR(16);      -- NULL/OK/ERROR
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS conn_error VARCHAR(2048);
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS conn_checked_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS sample_export (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name VARCHAR(512) NOT NULL,            -- 上传的 Excel 文件名
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED
    stage VARCHAR(256),
    total_items INT NOT NULL DEFAULT 0,
    done_items INT NOT NULL DEFAULT 0,
    ds_total INT NOT NULL DEFAULT 0,
    ds_added INT NOT NULL DEFAULT 0,
    ds_skipped INT NOT NULL DEFAULT 0,
    ds_fixed INT NOT NULL DEFAULT 0,
    ds_error INT NOT NULL DEFAULT 0,
    ds_report CLOB,                              -- JSON 数组,数据源导入明细
    zip_path VARCHAR(1024),
    zip_size BIGINT,
    error VARCHAR(2048),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sample_export_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES sample_export(id) ON DELETE CASCADE,
    seq INT NOT NULL,                            -- 在 Excel 中的行序(从 1)
    category VARCHAR(128),                       -- 所属水利对象类别名称
    sys_no VARCHAR(32),
    sys_desc VARCHAR(512),                       -- 实际系统或模式描述
    db_type VARCHAR(32), host VARCHAR(256), port INT, username VARCHAR(256),
    database_name VARCHAR(256), schema_name VARCHAR(256),
    table_name VARCHAR(256), table_cn_name VARCHAR(512),
    ds_key VARCHAR(1024),                        -- 数据源身份 key(type|host小写|port|username|database)
    datasource_id BIGINT,                        -- 导入后匹配到的数据源
    sheet_name VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED
    row_count INT,
    error VARCHAR(2048),
    excel_file VARCHAR(512)                      -- 生成的 xlsx 相对路径(类别目录/文件名)
);
CREATE INDEX IF NOT EXISTS idx_sample_export_item_task ON sample_export_item(task_id, seq);
