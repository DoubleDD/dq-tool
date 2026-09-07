-- 局域网共享:手动添加的实例列表(UDP 广播发现不可用的网络按地址直连探测,逗号分隔 host:port)
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS lan_manual_peers VARCHAR(2000);
