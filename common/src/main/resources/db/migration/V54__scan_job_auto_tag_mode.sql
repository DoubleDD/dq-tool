-- 扫描任务级 AI 自动打标对「已有标记的表」的处理模式:SKIP 跳过(默认,老行为)/ APPEND 增量追加(保留旧标记)/ OVERWRITE 全量覆盖(只替换 AI 旧标记,人工标记保留)
-- 存量任务默认 SKIP,保持升级前行为
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS auto_tag_mode VARCHAR(16) DEFAULT 'SKIP';
