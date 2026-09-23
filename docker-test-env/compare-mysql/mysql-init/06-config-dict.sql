-- 强制客户端按 utf8mb4 解释本文件,避免镜像 locale 非 UTF-8 时中文被双重编码
SET NAMES utf8mb4;

-- =============================================================================
-- 配置库:config(模拟互联网数据集的「系统-库-模式」字典)
--   * config.sys_dict 登记各业务系统与其数据库/模式的对应关系
--   * 字段结构与线上互联网数据集的 config.sys_dict 一致(5 个 VARCHAR,全中文注释)
--   * db_code(数据库实例名称)的值与本实例「库列表」中的库名一一对应:
--       辽宁省防汛抗旱综合信息平台 ×10 行  -> fxkhzxhxxpt(列表描述确认)
--       河库水文业务系统(水资源管理)     -> hyd_ln(sys_db=hyd-ln 对应)
--       国家防汛抗旱指挥系统二期工程数据汇集平台 -> hsybdd(同为防汛调度口)
--       辽宁省大中型数字灌区系统           -> xsdstll
--       辽宁省防指二期旱情信息采集系统     -> shzhfy(同属防灾信息类)
--   * 库列表中的 fxkhzxhxxpt / hsybdd / shzhfy / skjzpc / xsdstll 在本实例
--     不一定存在,这里一并建空库,保证 db_code 全部可解析
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `fxkhzxhxxpt` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `hsybdd`     DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `shzhfy`     DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `skjzpc`     DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `xsdstll`    DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;

CREATE DATABASE IF NOT EXISTS `config`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE `config`;

DROP TABLE IF EXISTS sys_dict;

CREATE TABLE sys_dict (
  sys_name VARCHAR(30) DEFAULT NULL COMMENT '系统名称',
  sys_code VARCHAR(30) DEFAULT NULL COMMENT '系统编码',
  sys_db   VARCHAR(30) DEFAULT NULL COMMENT '系统库名',
  sys_seg  VARCHAR(30) DEFAULT NULL COMMENT '系统模式名称',
  db_code  VARCHAR(64) DEFAULT NULL COMMENT '数据库实例名称'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '系统-库-模式字典表(模拟互联网数据集 config.sys_dict,db_code 对应本实例库名)';

INSERT INTO sys_dict (sys_name, sys_code, sys_db, sys_seg, db_code) VALUES
('国家防汛抗旱指挥系统二期工程数据汇集平台', 'gjfxkhzhxteqgcsjhpt', 'FXKH',    'USER_3101', 'hsybdd'),
('河库水文业务系统(水资源管理)',           'hksw_szy',           'hyd-ln',  'gw_szy',    'hyd_ln'),
('辽宁省大中型数字灌区系统',                 'lnsdzxszgqxt',       'prosaln', 'prosaln',   'xsdstll'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'BASIC',   'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'gxpt',    'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'HNFA',    'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'HNS_SHYJ','dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'HT_FHGC', 'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'HT_HSJS', 'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'RWDB',    'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'RWDB_NY', 'dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'RWDBTEMP','dbo',       'fxkhzxhxxpt'),
('辽宁省防汛抗旱综合信息平台',               'fxkhzhxxpt',         'SHYJ_NY', 'dbo',       'fxkhzxhxxpt'),
('辽宁省防指二期旱情信息采集系统',           'lnsfzeqhxxcjxt',     'fqurq2',  'dbo',       'shzhfy');

GRANT ALL PRIVILEGES ON `config`.*     TO 'dq'@'%';
GRANT ALL PRIVILEGES ON `fxkhzxhxxpt`.* TO 'dq'@'%';
GRANT ALL PRIVILEGES ON `hsybdd`.*     TO 'dq'@'%';
GRANT ALL PRIVILEGES ON `shzhfy`.*     TO 'dq'@'%';
GRANT ALL PRIVILEGES ON `skjzpc`.*     TO 'dq'@'%';
GRANT ALL PRIVILEGES ON `xsdstll`.*    TO 'dq'@'%';
FLUSH PRIVILEGES;
