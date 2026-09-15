-- 系统「备份表」标记:表名以 _copy/_bak/_backup/_tmp(+可选序号)结尾的备份/临时表,扫描后由系统联动自动打/摘,
-- 用户不可编辑或删除;tag_type=0 与「空表」同口径(系统标记,不作为 AI 自动打标候选,AI 自动打标对这类表直接跳过)。
-- 按名称幂等合并:老库升级时若已存在同名人工标记(用户自建的「备份表」USER 标记),连同其打标关系一并转为系统标记
-- (关系保留不丢,此后由扫描结果自动维护),避免同名双份导致扫描联动找不到系统标记。

MERGE INTO tag_def(name, color, kind, description, tag_type)
KEY(name) VALUES ('备份表', '#E6A23C', 'BACKUP', '表名以 _copy/_bak/_backup/_tmp(+序号)结尾的备份/临时表,由扫描结果自动维护', 0);

-- 被合并的同名人工标记关系改记系统来源,与「空表」标记口径一致
UPDATE table_tag SET source = 'SYSTEM' WHERE tag_id IN (SELECT id FROM tag_def WHERE kind = 'BACKUP');
