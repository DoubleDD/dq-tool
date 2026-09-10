-- 对象管理:object_table.rel_kind 列(挂载表与所属目录的关系类型:INCLUDE=包含 / ASSOC=关联,NULL=未指定;
-- 在目录图 目录→表 连线上展示,挂载弹窗选表时逐表下拉选择)
ALTER TABLE object_table ADD COLUMN IF NOT EXISTS rel_kind VARCHAR(16);
