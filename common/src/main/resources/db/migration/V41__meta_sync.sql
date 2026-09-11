-- 元数据批量同步(数据源页「刷新」):任务表 + 逐数据源明细表
-- 同步范围:库清单→schema 清单→表清单/字段总数/整库 lite 字段→逐表详细字段+索引(DDL 不同步,保留按需浏览)
-- 服务重启时残留 PENDING/RUNNING 统一置 FAILED(见 MetaSyncService.recoverUnfinished)

CREATE TABLE IF NOT EXISTS meta_sync_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED/CANCELED
    total_ds INT NOT NULL DEFAULT 0,                -- 数据源总数
    done_ds INT NOT NULL DEFAULT 0,
    failed_ds INT NOT NULL DEFAULT 0,
    error VARCHAR(2000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS meta_sync_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES meta_sync_job(id) ON DELETE CASCADE,
    datasource_id BIGINT NOT NULL,
    datasource_name VARCHAR(256),                   -- 名称快照:数据源删除后仍可展示
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED/CANCELED
    db_count INT NOT NULL DEFAULT 0,                -- 同步成果统计(单库方言 db_count=0,schema 即库)
    schema_count INT NOT NULL DEFAULT 0,
    table_count INT NOT NULL DEFAULT 0,
    progress VARCHAR(512),                          -- 进度文本,如「正在同步表 xxx(3/12)」
    error VARCHAR(2000),
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_sync_item ON meta_sync_item(job_id, datasource_id);
