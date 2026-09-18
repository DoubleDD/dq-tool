-- 比对目标「身份列为空」行数:这类行没有可对齐的组合身份值,以行内代理键进比对——编码路不参与,
-- 名称/大模型两路可配对(「任意一边 code 空就用 name 匹配」口径),不再静默丢弃。
-- no_key_rows 单列计数并透出到目标说明/导出总览「差异原因」,避免整表空编码却只显示「全部缺失」的误判。
-- target_count/base_count 口径同为「比对时实际读到的总行数」(代理键行也包含在内)。老任务 NULL 按 0 解读。
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS no_key_rows INT;      -- 身份列为空的行数(编码路不参与,仅名称/大模型可配对)
