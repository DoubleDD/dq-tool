-- 表级打标关系的来源:MANUAL 人工打标 / AI 自动打标 / SYSTEM 系统联动(空表标记)
-- 存量关系无法回溯区分 AI 与人工,默认 MANUAL;空表标记的关系一定是系统联动,修正为 SYSTEM

ALTER TABLE table_tag ADD COLUMN IF NOT EXISTS source VARCHAR(10) NOT NULL DEFAULT 'MANUAL';
UPDATE table_tag SET source = 'SYSTEM' WHERE tag_id IN (SELECT id FROM tag_def WHERE kind = 'EMPTY');
