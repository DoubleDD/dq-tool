-- 对象管理:object_table_rel.rel_kind 列(关系表与挂载表的关系类型:INCLUDE=包含 / ASSOC=关联,NULL=未指定;
-- 在目录图 挂载表→关系表 连线上展示,添加关系表弹窗选表时逐表下拉选择)
ALTER TABLE object_table_rel ADD COLUMN IF NOT EXISTS rel_kind VARCHAR(16);
