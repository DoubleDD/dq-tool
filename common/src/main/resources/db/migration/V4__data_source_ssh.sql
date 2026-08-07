-- 数据源 SSH 隧道配置(经跳板机连接目标库):三个秘密字段(密码/私钥/私钥口令)AES-GCM 加密存储,读取后由调用方解密
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_host VARCHAR(256);
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_port INT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_username VARCHAR(128);
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_auth_method VARCHAR(16);      -- password / publickey
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_password_enc VARCHAR(2048);
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_private_key_enc VARCHAR(8192); -- 私钥内容(非文件路径),PEM 较长
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS ssh_passphrase_enc VARCHAR(2048);
