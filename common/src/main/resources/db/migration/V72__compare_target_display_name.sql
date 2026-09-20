-- 比对目标自定义显示名(向导「已添加比对系统」清单铅笔编辑):NULL/空串 = 回落数据源名快照。
-- 展示与导出统一口径:自定义名 > 系统登记(table_system) > 数据源名快照;重跑保留,向导编辑随新提交重写。
ALTER TABLE compare_target ADD COLUMN IF NOT EXISTS display_name VARCHAR(256);  -- 目标自定义显示名
