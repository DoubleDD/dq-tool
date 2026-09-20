-- 数据比对抽样(V70):任务级抽样条数,NULL = 全量比对;抽样语义为确定性「按身份列排序取前 N 条」
ALTER TABLE compare_job ADD COLUMN sample_rows INT NULL;
