-- 多库方言(SQL Server/Kingbase)库清单缓存污染清理:
-- 历史上 listSchemas/listSchemaStats 在 database 为空时把 schema 名(如 dbo)误写进 meta_database 的 db_name='',
-- 该槽位本是数据源级库清单(V29 口径),导致库清单缓存被污染,后续请求把 schema 名当库名切 catalog 报错
-- (SQL Server:数据库 'dbo' 不存在)。判定依据:多库方言的 schema 清单永远按真实库名缓存,
-- db_name='' 不应存在 SCHEMA 标记;存在即说明槽位已被 schema 名覆盖,清行与标记后下次访问回源重建库清单。

DELETE FROM meta_database
WHERE db_name = '' AND datasource_id IN (
    SELECT ds.id FROM data_source ds
    WHERE ds.db_type IN ('SQLSERVER', 'KINGBASE')
      AND EXISTS (SELECT 1 FROM meta_cache_flag f
                  WHERE f.datasource_id = ds.id AND f.db_name = '' AND f.kind = 'SCHEMA'));

DELETE FROM meta_cache_flag
WHERE db_name = '' AND kind = 'SCHEMA' AND datasource_id IN (
    SELECT id FROM data_source WHERE db_type IN ('SQLSERVER', 'KINGBASE'));
