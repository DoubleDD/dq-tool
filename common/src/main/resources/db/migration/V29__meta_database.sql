-- 库/schema 清单本地缓存:库列表页「库」层级清单(多库方言的 database 清单、各库的 schema 清单)
-- 与 meta_table 等结构缓存同一套语义:浏览路径懒加载 + refresh=true 强制回源覆盖;网络不通时有缓存即可正常浏览
-- 口径:db_name 空串 = 数据源级库清单(多库方言的 database 列表;单库方言为空列表);
--       db_name=库名 = 该库的 schema 清单(单库方言 db_name 空串,schema 即用户眼中的库)
-- 白名单不在缓存层过滤:缓存存全量,读取路径按 data_source.schema_filter 过滤(与 schema_stat 一致,改白名单无需重建缓存)

CREATE TABLE IF NOT EXISTS meta_database (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id BIGINT NOT NULL,
    db_name       VARCHAR(256) NOT NULL DEFAULT '',
    name          VARCHAR(256) NOT NULL,
    ordinal       INT NOT NULL,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_database ON meta_database(datasource_id, db_name, name);

-- 缓存存在标记复用 meta_cache_flag:kind=DATABASE(db_name/schema_name/table_name 均空串)= 库清单已缓存;
-- kind=SCHEMA(db_name=库名,schema_name/table_name 空串)= 该库 schema 清单已缓存
