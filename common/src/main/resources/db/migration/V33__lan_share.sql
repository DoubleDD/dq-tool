-- 局域网共享:实例发现开关(NULL=用配置文件 dq.lan.enabled 默认) + 实例 id(首次启动生成) + 实例名称(心跳广播用)
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS lan_enabled BOOLEAN;
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS instance_id VARCHAR(64);
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS instance_name VARCHAR(128);
