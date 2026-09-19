-- 导出中心(点击即登记状态机):异步任务(报告/抽样)提交时产物文件名尚未生成,
-- 登记先行、文件名终态补全 → 放宽 file_name 非空约束。
ALTER TABLE export_record ALTER COLUMN file_name DROP NOT NULL;
