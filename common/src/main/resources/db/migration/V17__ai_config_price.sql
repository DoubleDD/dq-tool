-- AI 计费价格配置列(单行 ai_config 扩展):NULL = 使用配置文件/内置 DeepSeek 默认价
-- 峰谷计价开关(默认开):关闭时只用单一输入/输出价(peak_input/peak_output 兼任),不区分时段;
-- 开启时按「工作时间 / 非工作时间」两档计费(同 DeepSeek 官方峰谷规则):工作时间段(工作日)按高峰价,
-- 其余时间与周末按谷价;工作时间段可多段,存 "HH:mm-HH:mm,..."(如 09:00-12:00,14:00-18:00)
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS peak_valley_enabled BOOLEAN;     -- 是否启用峰谷计价
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS peak_input_price   DECIMAL(12,4);  -- 工作时间(高峰)输入价 / 单一输入价(元/百万 token)
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS peak_output_price  DECIMAL(12,4);  -- 工作时间(高峰)输出价 / 单一输出价(元/百万 token)
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS valley_input_price DECIMAL(12,4);  -- 非工作时间(谷价)输入价(元/百万 token)
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS valley_output_price DECIMAL(12,4); -- 非工作时间(谷价)输出价(元/百万 token)
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS work_periods       VARCHAR(512);   -- 工作时间段(高峰),可多段
ALTER TABLE ai_config ADD COLUMN IF NOT EXISTS weekend_valley     BOOLEAN;        -- 周末全天按谷价
