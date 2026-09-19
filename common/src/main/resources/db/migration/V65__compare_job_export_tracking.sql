-- 比对报告导出件跟踪(V65):导出由服务端直存 <数据目录>/compare/(任务 ID 前缀命名,同名覆盖只留最后一次),
-- 落库导出状态 + 文件 SHA-256 checksum;列表「打开」按钮置灰口径 = 文件存在且 checksum 与库中记录一致,
-- 不一致或文件不存在则仅「打开文件夹」可点(进 compare 目录)。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS export_status VARCHAR(16);    -- 导出状态:'DONE'=已生成;NULL=未导出(失败不落库,保留上次成功态)
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS export_file VARCHAR(512);     -- 导出件文件名(compare 目录内 basename,任务 ID 前缀)
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS export_checksum VARCHAR(64);  -- 导出件 SHA-256(hex)
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS export_at TIMESTAMP;          -- 最近导出完成时间
