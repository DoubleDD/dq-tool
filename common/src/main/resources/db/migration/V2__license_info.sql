-- 授权信息(单行,id 固定 1):授权码密文 + 客户/到期日冗余,激活时整体覆盖
CREATE TABLE IF NOT EXISTS license_info (
    id           BIGINT PRIMARY KEY,
    code_enc     VARCHAR(4096),                   -- 对称加密后的授权码
    customer     VARCHAR(256),                    -- 授权客户标识
    expires_at   DATE,                            -- 到期日(当天仍有效)
    activated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 老库列补齐:存量库 baseline 到 V1 后由本迁移补列(IF NOT EXISTS 幂等,新库执行为无操作)
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS db_name VARCHAR(256);
ALTER TABLE scan_table ADD COLUMN IF NOT EXISTS comment VARCHAR(1024);
ALTER TABLE scan_table ADD COLUMN IF NOT EXISTS storage_info VARCHAR(128);
ALTER TABLE scan_column ADD COLUMN IF NOT EXISTS column_comment VARCHAR(1024);
ALTER TABLE scan_column ADD COLUMN IF NOT EXISTS nullable BOOLEAN;
ALTER TABLE scan_column ADD COLUMN IF NOT EXISTS default_value VARCHAR(1024);
ALTER TABLE scan_column ADD COLUMN IF NOT EXISTS key_label VARCHAR(8);
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS db_mode VARCHAR(32);
