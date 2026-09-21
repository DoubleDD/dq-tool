-- 比对 AI 判定留痕加调用耗时列(毫秒;老数据 NULL = 未记录)
ALTER TABLE compare_ai_trace ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
