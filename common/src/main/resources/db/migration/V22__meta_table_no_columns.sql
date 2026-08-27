-- 无字段标记:表不存在或没有字段(如 Oracle IOT 溢出段 SYS_IOT_OVER_%)时置 TRUE,扫描/续扫按空表跳过
-- 强制刷新表结构(replaceTables 整粒度覆盖)后新行回到默认 FALSE,即标记自动还原
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE meta_table ADD COLUMN IF NOT EXISTS no_columns BOOLEAN DEFAULT FALSE;
