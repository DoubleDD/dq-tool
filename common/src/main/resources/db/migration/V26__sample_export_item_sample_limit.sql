-- 抽样导出明细:support 按 Excel「数据量」列逐表控制抽样行数
-- NULL = 列缺失/留空/非正数,导出时按默认抽样行数(50)处理
ALTER TABLE sample_export_item ADD COLUMN IF NOT EXISTS sample_limit INT;
