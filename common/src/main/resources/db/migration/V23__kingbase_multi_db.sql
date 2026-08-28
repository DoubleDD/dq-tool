-- Kingbase 升级为多库方言(先选库再选 schema,与 SQL Server 一致):
-- 库过滤白名单作用层级由 schema 级变为数据库级,存量 Kingbase 数据源的旧白名单(库内 schema 名)
-- 在新口径下匹配不到任何数据库,会导致库列表空跑,直接清空回到「不过滤」,由用户按需重新勾选
UPDATE data_source SET schema_filter = NULL WHERE db_type = 'KINGBASE';
