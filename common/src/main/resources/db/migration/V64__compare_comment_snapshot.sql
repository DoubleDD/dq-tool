-- 比对注释快照(V64):表中文名/字段中文在「比对执行时」快照落库(此时业务库必可达,刚读完数据),
-- 之后的质量报告与 xlsx 导出快照优先;老任务(列为 NULL)走缓存优先兜底(缓存未就绪回源业务库取,
-- 不可达静默降级留空)。读取口径统一 cacheFirst:缓存没有一定要回源。
-- 现场场景:批量导入建大批任务一次跑完,VPN 共用会被挤掉(等价断网),有快照的任务跑完后导出/报告零触库。
-- 库/schema/表定位照旧由 compare_job/compare_target 自身列承担,快照只存注释内容。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS base_table_comment VARCHAR(1000);   -- 基准表中文名快照
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS base_column_comments CLOB;        -- 基准表字段注释快照 JSON(字段小写 → 注释)
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS table_comment VARCHAR(1000);   -- 目标表中文名快照
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS column_comments CLOB;          -- 目标表字段注释快照 JSON(列名小写 → 注释)
