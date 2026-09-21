-- 对象名称(显示名)字段多选(V73):compare_job.display_fields_json(基准字段名 JSON array,有序;
--   取值口径 = 按字段顺序第一个非空值);NULL = 老任务,由 display_field 单列退化;
--   display_field 旧列继续写名称字段第一项,新旧读取路径收敛。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS display_fields_json CLOB;
