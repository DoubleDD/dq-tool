-- 数据比对导出(总览表):补记目标表实际总行数
-- 原指标只有 base_count(基准侧行数)与 matched/missing/extra 计数,
-- 总览表「条数」列要展示各系统自己的表行数(含多余行),且「与基准差」= 目标总行数 - 基准行数,
-- 故在比对时把目标侧实际读到的行数一并落库;老任务 NULL 表示未采集(导出处显示「—」)。
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS target_count INT;
