-- 数据比对:compare_job.display_field(对象名称/显示名字段)
-- 新建向导可选指定,必须在比对字段内;NULL 或空串 = 自动取「比对字段中第一个文本型非主键字段」,
-- 若一个都没有则 object_name 落空串。提交时即解析成基准表实际列名落库,执行/重跑直接沿用。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS display_field VARCHAR(256);
