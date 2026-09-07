-- E2E 演示库:两张业务表,含 NULL/空串,供 A 实例扫描产生真实扫描记录
CREATE TABLE IF NOT EXISTS reservoir_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL COMMENT '水库名称',
  code VARCHAR(64) DEFAULT NULL COMMENT '水库编码',
  region VARCHAR(128) DEFAULT NULL COMMENT '所在地区',
  capacity DECIMAL(12,2) DEFAULT NULL COMMENT '总库容(万m³)',
  built_year INT DEFAULT NULL COMMENT '建成年份',
  remark VARCHAR(255) DEFAULT NULL COMMENT '备注'
) COMMENT='水库基本信息表';

CREATE TABLE IF NOT EXISTS water_level_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservoir_id BIGINT DEFAULT NULL COMMENT '水库id',
  level DECIMAL(8,2) DEFAULT NULL COMMENT '水位(m)',
  measured_at DATETIME DEFAULT NULL COMMENT '测量时间',
  operator VARCHAR(64) DEFAULT NULL COMMENT '测量人'
) COMMENT='水位观测记录表';

INSERT INTO reservoir_info (name, code, region, capacity, built_year, remark) VALUES
  ('青云水库', 'QY-001', '华东区', 12500.50, 1988, '重点中型水库'),
  ('石门水库', 'SM-002', '华东区', 8300.00, 1975, NULL),
  ('龙潭水库', NULL, '华南区', NULL, 2003, ''),
  ('白云水库', 'BY-004', NULL, 3200.75, NULL, NULL),
  ('黑石水库', 'HS-005', '西南区', 15200.00, 1969, '除险加固中');

INSERT INTO water_level_log (reservoir_id, level, measured_at, operator) VALUES
  (1, 152.30, '2026-08-01 08:00:00', '张三'),
  (1, 152.45, '2026-08-02 08:00:00', '张三'),
  (2, NULL, '2026-08-02 08:00:00', NULL),
  (3, 88.10, NULL, '李四'),
  (NULL, NULL, NULL, NULL);
