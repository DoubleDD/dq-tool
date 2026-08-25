-- 扫描任务级「扫描后生成表描述」开关:表 DONE 后由大模型生成表描述落 table_doc(已有非空描述的表跳过)
-- 默认开启(TRUE);老库列补齐:IF NOT EXISTS 幂等,新库执行为无操作
ALTER TABLE scan_job ADD COLUMN IF NOT EXISTS gen_doc BOOLEAN NOT NULL DEFAULT TRUE;
