-- 授权码签发留档增加开放菜单与免鉴权标记列(10 段新格式;旧记录为 NULL,展示时按旧功能段推导菜单)

ALTER TABLE license_record ADD COLUMN menus VARCHAR(512);
ALTER TABLE license_record ADD COLUMN bypass_auth BOOLEAN;
