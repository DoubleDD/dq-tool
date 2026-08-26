-- 系统设置:应用模式首选浏览器 id(NULL=自动按系统优先级选择);浏览器清单由 server 壳层探测,本列只持久化选择
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS browser_app VARCHAR(64);
