-- AI 用量流水:输入/输出分项费用列(统计页悬停提示展示分项金额;老数据 NULL=无分项,前端不展示)
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS prompt_cost DECIMAL(14,6);
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS completion_cost DECIMAL(14,6);
