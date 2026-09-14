-- 数据比对:对象对齐(匹配)逻辑,解决「对比表字段与基准表不一致」下同一条数据的身份判定问题。
-- compare_job.match_mode:一任务一套匹配逻辑(新建向导第二步选择),取值
--   EXACT(1)          = 对象编码(code)与对象名称(name)两个都完全相等才算同一对象(逐行严格对齐)
--   CODE_THEN_NAME(2) = 有编码先用编码配,编码没配上的再用对象名称配
--   CODE_NAME_LLM(3)  = 在 1/2 都没配上的残余数据上交由大模型归一化再认一轮
--   NULL/空 = 老任务,按 EXACT(仅编码)解读,与历史口径完全一致
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS match_mode VARCHAR(32);

-- compare_target 三列:匹配来源计数(编码命中的对象数,即新旧口径共有的基线)
--   code_matched_count = 编码对齐上的对象数;
--   name_matched_count = 编码没配上、靠对象名称配上的对象数(仅匹配逻辑 2/3);
--   ai_matched_count   = 名称也没配上、由大模型归一化配上的对象数(仅匹配逻辑 3)。
-- 老任务 NULL = 未采集(展示按空处理);matched_count 仍为三者之和(SAME+DIFF),口径不变。
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS code_matched_count INT;
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS name_matched_count INT;
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS ai_matched_count INT;

-- compare_diff.match_by:该差异行的对象是靠哪一路对齐上的(CODE=编码 / NAME=对象名称 / LLM=大模型归一化),
-- 便于人工复核「名称/大模型配上的对象对不对」;NULL = 老数据(未记录,按编码对齐解读)。
ALTER TABLE compare_diff ADD COLUMN IF NOT EXISTS match_by VARCHAR(8);
