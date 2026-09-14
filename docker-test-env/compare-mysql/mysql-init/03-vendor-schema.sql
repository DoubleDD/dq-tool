-- 强制客户端按 utf8mb4 解释本文件,避免镜像 locale 非 UTF-8 时中文被双重编码
SET NAMES utf8mb4;

-- =============================================================================
-- 三方厂商库:reservoir_vendor(模拟第三方系统,表结构不符合国标规范)
-- 与基准库的差异(刻意设计,用于验证数据比对/元数据展示对"脏结构"的容忍度):
--   1. 表名带 t_ 前缀,且没有表注释
--   2. 用无业务含义的自增代理键 vendor_id 做主键;reservoir_code 只是普通索引(不唯一)
--   3. 字段名大小写混乱(UPPER / Pascal / snake 混用)
--   4. 数值型字段一律用 VARCHAR/INT 存放(总库容、坝高、经纬度都是字符串)
--   5. 多出一批冗余/私有字段:data_source、sync_time、is_deleted、ext_json
--   6. 字段注释简短、无单位,不符合数据字典规范
--
-- 注意:为了让数据比对能按「字段名(忽略大小写)」映射,本表保留了基准表的
--       全部 16 个业务字段(含 reservoir_code / reservoir_name),仅类型/注释/主键不同。
--       这样才能得到干净的 缺失3 / 多余4 / 差异5 预期结果。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS reservoir_vendor
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE reservoir_vendor;

DROP TABLE IF EXISTS t_reservoir_info;

CREATE TABLE t_reservoir_info (
  vendor_id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '厂商自增主键',
  RESERVOIR_CODE  CHAR(32)     NOT NULL               COMMENT '水库编码',
  RESERVOIR_NAME  VARCHAR(200) NOT NULL               COMMENT '水库名称',
  Reservoir_Type  VARCHAR(20)           DEFAULT NULL  COMMENT '水库类型',
  basin_name      VARCHAR(60)           DEFAULT NULL  COMMENT '流域',
  river_name      VARCHAR(60)           DEFAULT NULL  COMMENT '河流',
  admin_division  VARCHAR(60)           DEFAULT NULL  COMMENT '行政区划',
  Longitude       VARCHAR(30)           DEFAULT NULL  COMMENT '经度',
  Latitude        VARCHAR(30)           DEFAULT NULL  COMMENT '纬度',
  Total_Capacity  VARCHAR(50)           DEFAULT NULL  COMMENT '总库容',
  Dam_Height      VARCHAR(50)           DEFAULT NULL  COMMENT '坝高',
  BUILD_YEAR      INT                   DEFAULT NULL  COMMENT '建成年份',
  Manage_Unit     VARCHAR(120)          DEFAULT NULL  COMMENT '管理单位',
  STATUS          VARCHAR(20)           DEFAULT NULL  COMMENT '状态',
  remark          VARCHAR(500)          DEFAULT NULL  COMMENT '备注',
  created_at      DATETIME              DEFAULT NULL  COMMENT '创建时间',
  updated_at      DATETIME              DEFAULT NULL  COMMENT '更新时间',
  data_source     VARCHAR(50)           DEFAULT 'FANGSHANG' COMMENT '数据来源',
  sync_time       DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '同步时间',
  is_deleted      TINYINT               DEFAULT 0     COMMENT '删除标记:0未删1已删',
  ext_json        VARCHAR(500)          DEFAULT NULL  COMMENT '厂商扩展字段',
  PRIMARY KEY (vendor_id),
  KEY idx_reservoir_code (RESERVOIR_CODE)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
