-- 授权码签发留档增加功能列表列(8 段新格式;旧记录为 NULL 表示仅基础功能)

ALTER TABLE license_record ADD COLUMN features VARCHAR(512);
