-- 扫描任务级「AI 自动打标」开关:表 DONE 后由大模型从 USER 标记中选择并自动打标(幂等,只增不删)
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS auto_tag BOOLEAN NOT NULL DEFAULT FALSE;
