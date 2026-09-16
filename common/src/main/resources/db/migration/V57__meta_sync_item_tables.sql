-- 元数据批量同步表级选择:meta_sync_item.tables_json 列
-- 该明细要同步的表清单 JSON [{db,schema,table}];NULL/空 = 整数据源同步(旧行为)

ALTER TABLE meta_sync_item ADD COLUMN IF NOT EXISTS tables_json CLOB;
