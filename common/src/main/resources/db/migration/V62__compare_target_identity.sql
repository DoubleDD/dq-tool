-- 比对目标级身份字段:身份配置下沉到目标级,目标侧列由既有 field_mapping_json 推导(单一事实源)。
-- compare_job.key_fields_json:任务级身份字段数组(基准字段名 JSON array,向导第一步多选);
--   NULL = 老任务,由 key_field 单列退化 [keyField],口径不变;key_field 旧列继续写 keys 第一项。
-- compare_target.identity_json:人工显式覆盖 {"keys":["code"]};NULL = 按「任务级 keyFields ∩ 该目标映射中
--   已连线的基准字段」推导(无映射 = keyFields 全体,走按名称自动匹配老路径);一个都没连提交/审核时拦下。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS key_fields_json CLOB;      -- 任务级身份字段数组(基准字段名)
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS identity_json CLOB;     -- 目标级人工覆盖 {"keys":[...]},NULL=推导
