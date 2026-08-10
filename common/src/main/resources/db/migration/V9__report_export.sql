-- Word 报告异步导出任务:导出生成耗时(逐库聚合 + 逐节 LLM 分析),改后台执行,前端任务列表轮询进度

CREATE TABLE IF NOT EXISTS report_export (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id  BIGINT NOT NULL,
    db_name        VARCHAR(256) NOT NULL DEFAULT '',   -- 空串兜底口径与 table_doc 一致
    schema_names   CLOB,                               -- 逗号分隔的库名;null 表示全部库
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/DONE/FAILED
    stage          VARCHAR(256),                       -- 当前阶段描述(如「生成字段填充率分析文字」)
    progress_done  INT NOT NULL DEFAULT 0,
    progress_total INT NOT NULL DEFAULT 0,
    file_path      VARCHAR(1024),
    file_size      BIGINT,
    error          VARCHAR(2048),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_export_ds ON report_export(datasource_id, id);
