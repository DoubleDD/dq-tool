-- 数据源分组名:自由文本,空/NULL 表示未分组;分组列表由前端从现有数据源聚合
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS group_name VARCHAR(255);
