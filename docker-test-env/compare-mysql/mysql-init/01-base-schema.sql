-- 强制客户端按 utf8mb4 解释本文件,避免镜像 locale 非 UTF-8 时中文被双重编码
SET NAMES utf8mb4;

-- =============================================================================
-- 基准库:reservoir_base(按国家标准/水利行业规范建库)
--   * 库名、表名、字段名全部小写 snake_case,语义完整
--   * 自然主键 reservoir_code(水库编码),不使用代理键
--   * 每个字段都有规范的中文注释(含单位),表本身也有注释
--   * 数值字段使用精确类型(DECIMAL/SMALLINT),时间字段带默认值
-- =============================================================================

CREATE DATABASE IF NOT EXISTS reservoir_base
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE reservoir_base;

DROP TABLE IF EXISTS reservoir_base_info;

CREATE TABLE reservoir_base_info (
  reservoir_code   VARCHAR(32)   NOT NULL               COMMENT '水库编码,12位水利对象编码,主键',
  reservoir_name   VARCHAR(128)  NOT NULL               COMMENT '水库名称',
  reservoir_type   VARCHAR(16)   NOT NULL               COMMENT '水库类型:大型/中型/小型',
  basin_name       VARCHAR(64)            DEFAULT NULL  COMMENT '所在流域',
  river_name       VARCHAR(64)            DEFAULT NULL  COMMENT '所在河流',
  admin_division   VARCHAR(64)            DEFAULT NULL  COMMENT '行政区划,省+市',
  longitude        DECIMAL(10,6)          DEFAULT NULL  COMMENT '经度(度)',
  latitude         DECIMAL(10,6)          DEFAULT NULL  COMMENT '纬度(度)',
  total_capacity   DECIMAL(18,4)          DEFAULT NULL  COMMENT '总库容(万立方米)',
  dam_height       DECIMAL(10,2)          DEFAULT NULL  COMMENT '最大坝高(米)',
  build_year       SMALLINT               DEFAULT NULL  COMMENT '建成年份',
  manage_unit      VARCHAR(128)           DEFAULT NULL  COMMENT '管理单位',
  status           VARCHAR(16)            DEFAULT NULL  COMMENT '工程状态:正常运行/除险加固/报废',
  remark           VARCHAR(255)           DEFAULT NULL  COMMENT '备注',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (reservoir_code),
  KEY idx_reservoir_name (reservoir_name),
  KEY idx_admin_division (admin_division)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '水库基础信息表(按水利行业标准规范建表)';
