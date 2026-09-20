-- 页面心跳间隔(秒):前端按该间隔上报 /api/heartbeat,桌面看门狗按 max(配置超时, 3 个间隔) 判定窗口已关闭;
-- NULL = 默认 5 秒(系统设置页「页面心跳」可视化维护,单位秒/分/时,保存后即时生效)
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS heartbeat_interval_seconds INT;
