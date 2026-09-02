-- 扫描任务记录目标数据库版本号(任务创建时从连接元数据快照),扫描记录详情页「数据库类型」旁展示
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS db_version VARCHAR(255);
