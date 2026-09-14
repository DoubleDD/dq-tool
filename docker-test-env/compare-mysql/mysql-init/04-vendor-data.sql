-- 强制客户端按 utf8mb4 解释本文件,避免镜像 locale 非 UTF-8 时中文被双重编码
SET NAMES utf8mb4;

-- =============================================================================
-- 三方厂商库数据:reservoir_vendor.t_reservoir_info
-- 以基准库 100 条为源,构造出 缺失 3 / 多余 4 / 差异 5 的场景:
--
--   ① 复制:基准库 100 条中剔除 3 条  -> 厂商库 97 条与基准库同码
--   ② 修改:从中挑 5 条随机改值        -> 5 条 数据不一致(DIFF)
--   ③ 新增:插入 4 条基准库没有的编码  -> 4 条 对象多余(EXTRA)
--
-- 最终:基准库 100 条,厂商库 97 + 4 = 101 条。
-- 因为与基准库同处一个 MySQL 实例,这里直接用跨库 INSERT ... SELECT,
-- 保证"未被修改的 92 条"与基准库逐字节一致(避免手抄引入噪声差异)。
-- =============================================================================

USE reservoir_vendor;

-- ① 复制基准库 100 条,剔除 3 条(缺失场景:基准有 / 厂商无)
INSERT INTO reservoir_vendor.t_reservoir_info
  (RESERVOIR_CODE, RESERVOIR_NAME, Reservoir_Type, basin_name, river_name, admin_division,
   Longitude, Latitude, Total_Capacity, Dam_Height, BUILD_YEAR, Manage_Unit, STATUS, remark,
   created_at, updated_at, data_source, is_deleted, ext_json)
SELECT
  b.reservoir_code,
  b.reservoir_name,
  b.reservoir_type,
  b.basin_name,
  b.river_name,
  b.admin_division,
  CAST(b.longitude AS CHAR),
  CAST(b.latitude AS CHAR),
  CAST(b.total_capacity AS CHAR),
  CAST(b.dam_height AS CHAR),
  b.build_year,
  b.manage_unit,
  b.status,
  b.remark,
  b.created_at,
  b.updated_at,
  'FANGSHANG',
  0,
  NULL
FROM reservoir_base.reservoir_base_info b
WHERE b.reservoir_code NOT IN ('330100000012', '330100000055', '330100000077');

-- ② 挑 5 条改值(差异场景:两边都有该对象,字段值不一致)

-- 差异 1:水库名称被改写(字符串差异)
UPDATE reservoir_vendor.t_reservoir_info
SET RESERVOIR_NAME = CONCAT(RESERVOIR_NAME, '(厂商补录)')
WHERE RESERVOIR_CODE = '330100000007';

-- 差异 2:总库容对不上(基准是 DECIMAL,厂商是 VARCHAR,考验数值归一化比较)
UPDATE reservoir_vendor.t_reservoir_info v
JOIN reservoir_base.reservoir_base_info b ON b.reservoir_code = v.RESERVOIR_CODE
SET v.Total_Capacity = CAST(b.total_capacity + 300 AS CHAR)
WHERE v.RESERVOIR_CODE = '330100000023';

-- 差异 3:水库类型口径不一致
UPDATE reservoir_vendor.t_reservoir_info v
JOIN reservoir_base.reservoir_base_info b ON b.reservoir_code = v.RESERVOIR_CODE
SET v.Reservoir_Type = IF(b.reservoir_type = '小型', '中型', '大型')
WHERE v.RESERVOIR_CODE = '330100000041';

-- 差异 4:建成年份、坝高两个字段都不一致
UPDATE reservoir_vendor.t_reservoir_info v
JOIN reservoir_base.reservoir_base_info b ON b.reservoir_code = v.RESERVOIR_CODE
SET v.BUILD_YEAR = b.build_year + 3,
    v.Dam_Height = CAST(b.dam_height + 1.5 AS CHAR)
WHERE v.RESERVOIR_CODE = '330100000068';

-- 差异 5:管理单位、工程状态两个字段都不一致
UPDATE reservoir_vendor.t_reservoir_info v
JOIN reservoir_base.reservoir_base_info b ON b.reservoir_code = v.RESERVOIR_CODE
SET v.Manage_Unit = CONCAT(b.manage_unit, '(已划转)'),
    v.STATUS = IF(b.status = '除险加固', '正常运行', '除险加固')
WHERE v.RESERVOIR_CODE = '330100000090';

-- ③ 插入 4 条基准库不存在的编码(多余场景:厂商有 / 基准无)
INSERT INTO reservoir_vendor.t_reservoir_info
  (RESERVOIR_CODE, RESERVOIR_NAME, Reservoir_Type, basin_name, river_name, admin_division,
   Longitude, Latitude, Total_Capacity, Dam_Height, BUILD_YEAR, Manage_Unit, STATUS, remark,
   created_at, updated_at, data_source, is_deleted, ext_json)
VALUES
  ('330199000001', '厂商新增水库甲', '小型', '长江流域', '长江', '浙江省杭州市',
   '119.100000', '30.200000', '2600.0000', '38.50', 2015, '杭州市水务集团', '正常运行',
   '仅厂商库存在', '2026-08-02 10:00:00', '2026-08-02 10:00:00', 'FANGSHANG', 0, NULL),
  ('330199000002', '厂商新增水库乙', '中型', '黄河流域', '黄河', '山东省济南市',
   '117.050000', '36.650000', '9800.0000', '52.30', 1998, '济南市水利局', '除险加固',
   '仅厂商库存在', '2026-08-02 10:05:00', '2026-08-02 10:05:00', 'FANGSHANG', 0, NULL),
  ('330199000003', '厂商新增水库丙', '小型', '珠江流域', '珠江', '广东省广州市',
   '113.260000', '23.130000', '1450.5000', '26.80', 2007, '广州市水库管理处', '正常运行',
   '仅厂商库存在', '2026-08-02 10:10:00', '2026-08-02 10:10:00', 'FANGSHANG', 0, NULL),
  ('330199000004', '厂商新增水库丁', '大型', '松辽流域', '松花江', '吉林省长春市',
   '125.320000', '43.880000', '23500.0000', '88.60', 1976, '长春市水务集团', '正常运行',
   '仅厂商库存在', '2026-08-02 10:15:00', '2026-08-02 10:15:00', 'FANGSHANG', 0, NULL);
