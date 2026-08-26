-- AI 用量流水:关联扫描任务列(扫描后表描述/自动打标产生的调用记 scan_job_id,其余为 NULL)
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS scan_job_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_ai_usage_scan_job ON ai_usage_log(scan_job_id);
