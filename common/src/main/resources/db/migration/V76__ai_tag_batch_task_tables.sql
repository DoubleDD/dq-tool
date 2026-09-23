-- 批量 AI 打标后台任务:表名清单落库,active 接口带出让表列表页对「打标中」的表在标记列显示 loading
-- 老库列补齐:IF NOT EXISTS 幂等,新库(V75 之后)执行为无操作;不改已发布的 V75

ALTER TABLE ai_tag_batch_task ADD COLUMN IF NOT EXISTS table_names CLOB;  -- 打标表名 JSON 数组,active 视图带出
