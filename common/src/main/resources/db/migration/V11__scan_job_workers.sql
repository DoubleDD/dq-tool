-- 扫描任务级并发 worker 数:创建扫描时可在弹窗中指定,覆盖全局默认值;NULL 表示使用配置默认
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS workers INT;
