-- 数据源库过滤白名单:逗号分隔的库名列表,空/NULL 表示不过滤(显示全部库)
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS schema_filter VARCHAR(4000);
