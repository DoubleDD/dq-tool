-- 导出中心(V67):push 模型——所有导出(含落盘类:报告 Word/抽样 zip/比对 xlsx)成功后
-- 由文件写入方把完整记录推送到 export_record;记录相对数据目录的详细路径,打开/定位统一走 rel_path。
-- UNION 归一(V66 查询层)随之废弃:单表即全量,落盘类不再依赖各任务表。
ALTER TABLE export_record ADD COLUMN IF NOT EXISTS rel_path VARCHAR(512);
