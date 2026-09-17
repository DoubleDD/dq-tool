-- 数据比对导出总览:「数据最新更新时间」由占位「—」改为比对执行时采集的快照。
-- 采集口径:优先名称/注释命中的 update 类时间字段(其次 create 类),没有则交大模型语义挑「最新修改时间」字段,
-- 命中后对业务库执行 MAX(列);表里没有可用时间字段、查询失败、未配置大模型都留空(NULL)。
-- 老任务无该快照(NULL)导出留空,不改历史结果。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS base_data_updated_at VARCHAR(64);     -- 基准表最新更新时间快照
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS data_updated_at VARCHAR(64);       -- 各目标表最新更新时间快照
