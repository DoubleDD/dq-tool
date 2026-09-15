-- 授权码签发留档增加备注列(仅管理端展示,不写入授权码 payload;旧记录为 NULL)

ALTER TABLE license_record ADD COLUMN remark VARCHAR(1024);
