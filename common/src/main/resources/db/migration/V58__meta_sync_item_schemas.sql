-- 元数据批量同步库/schema 级选择:meta_sync_item.schemas_json 列
-- 该明细要同步的库/schema 清单 JSON [{db,schema}];NULL/空 = 非 schema 级(整数据源或表级)

ALTER TABLE meta_sync_item ADD COLUMN IF NOT EXISTS schemas_json CLOB;
