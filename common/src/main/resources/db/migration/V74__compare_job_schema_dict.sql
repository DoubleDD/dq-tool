-- 库/模式名反查字典(V74):compare_job.schema_dict_json(字典表配置 JSON:
--   {"datasourceId":..,"db":..,"schema":..,"table":..,"nameField":..,"dbField":..,"schemaField":..},
--   三个字段名落库前已归一为字典表实际列名);
--   仅影响比对导出 xlsx 表名单元格第三行「(库名.模式名)」的反查替换,不影响比对执行与页面展示;
--   NULL = 不反查(老任务/未配置)。
ALTER TABLE compare_job ADD COLUMN IF NOT EXISTS schema_dict_json CLOB;
