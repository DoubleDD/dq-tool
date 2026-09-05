-- 标记类型(用途),落库数字码:0=系统(空表等系统标记,kind=EMPTY)/ 1=可用于AI打标 / 2=仅用于人工打标
-- AI 自动打标只把类型 1 的标记发给大模型;存量 USER 标记默认 1,保持升级前「全部 USER 标记都是 AI 候选」的行为不回归

ALTER TABLE tag_def ADD COLUMN IF NOT EXISTS tag_type INT NOT NULL DEFAULT 1;
UPDATE tag_def SET tag_type = 0 WHERE kind = 'EMPTY';
