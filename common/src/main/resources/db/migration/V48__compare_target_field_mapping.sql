-- 数据比对:compare_target.field_mapping_json(基准字段 → 目标列的落库映射)
-- 新建向导第四步「字段映射」人工连线确定(如基准 code/name ↔ 厂商 RESERVOIR_CODE/Reservoir_Name);
-- JSON 对象 {"基准表实际列名":"目标表实际列名"},提交时即解析成双侧实际列名。
-- NULL/空 = 不指定映射,执行时按「字段名忽略大小写自动匹配」的旧行为(老任务与老文件兼容)。
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS field_mapping_json CLOB;
