-- 比对任务批量导入:compare_job 加待处理三列 + compare_import 导入批次表(原件留档)
-- PENDING(待处理)是静止状态:不进执行器、重启不清理、删除/归档放行

ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS pending_reason VARCHAR(32);   -- DS_ERROR / MAPPING_REVIEW / IMPORT_ERROR
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS object_category VARCHAR(64);  -- 水利对象类型,取自导入表格基准行,可空
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS import_id BIGINT;             -- 来源批次,向导手工建的为 NULL

CREATE TABLE IF NOT EXISTS compare_import (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_name   VARCHAR(512) NOT NULL,          -- 原始文件名(不变)
  file_path   VARCHAR(1024) NOT NULL,         -- 原件落盘路径:数据目录/compare-imports/batch-<id>/<原文件名>
  file_size   BIGINT,
  status      VARCHAR(16)  NOT NULL,          -- DS_REVIEW / BUILDING / DONE / FAILED
  ds_report   CLOB,                           -- 数据源映射报告 JSON:[{key,名称,地址,端口,库名,action,datasourceId,error}]
  task_count  INT NOT NULL DEFAULT 0,         -- sheet 数
  job_ids     VARCHAR(2048),                  -- 建出的任务 id 清单 JSON
  error       VARCHAR(1024),
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP
);
