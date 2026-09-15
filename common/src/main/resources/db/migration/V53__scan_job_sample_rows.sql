-- 扫描任务级采样行数:超过阈值的表只统计样本时取该值;NULL 表示使用全局默认(dq.scan.sample-rows / 系统设置)
-- 老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS sample_rows INT;
