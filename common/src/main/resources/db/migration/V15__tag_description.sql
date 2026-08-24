-- 标记描述:解释标记含义,AI 自动打标时随候选标记清单一并发给大模型,提升打标准确度
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE tag_def ADD COLUMN IF NOT EXISTS description VARCHAR(500);
