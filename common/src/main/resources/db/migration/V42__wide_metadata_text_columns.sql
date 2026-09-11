-- 元数据/扫描快照的文本列放宽为 CLOB:方言与驱动给出的「类型定义/注释/默认值」长度不受本地列宽约束,
-- 典型是 MySQL 的 COLUMN_TYPE 对 ENUM/SET 会带出完整枚举列表(几十个枚举值即超过原 VARCHAR(128)),
-- 写入超长值时整个粒度的刷新/扫描会失败(H2 22001),整批写入被一条超长值拖垮;
-- PG/Oracle/SQL Server 的表/字段注释与默认值同样没有实际上限(MySQL 表注释上限 2048、字段注释 1024)。
-- 这里只放宽列类型(非破坏性,不丢数据、不改语义),新库由 V1 建表后本脚本再放宽、老库增量执行,两条路径收敛。
-- 名称类列(库/schema/表/字段/索引名)保持 VARCHAR(256):它们受各库标识符长度上限约束(最长 128)。
ALTER TABLE meta_column ALTER COLUMN display_type SET DATA TYPE CLOB;
ALTER TABLE meta_column ALTER COLUMN default_value SET DATA TYPE CLOB;
ALTER TABLE meta_column ALTER COLUMN comment SET DATA TYPE CLOB;
ALTER TABLE meta_schema_column ALTER COLUMN col_type SET DATA TYPE CLOB;
ALTER TABLE meta_schema_column ALTER COLUMN comment SET DATA TYPE CLOB;
ALTER TABLE meta_table ALTER COLUMN comment SET DATA TYPE CLOB;
-- 扫描记录里的字段/表快照与结构缓存写入同一批源库元数据,同样放宽(否则扫描枚举列多的表会失败)
ALTER TABLE scan_column ALTER COLUMN column_type SET DATA TYPE CLOB;
ALTER TABLE scan_column ALTER COLUMN column_comment SET DATA TYPE CLOB;
ALTER TABLE scan_column ALTER COLUMN default_value SET DATA TYPE CLOB;
ALTER TABLE scan_table ALTER COLUMN comment SET DATA TYPE CLOB;
