#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
基准库(国标规范库)reservoir_base.reservoir_base_info 的 100 条水库数据生成器。

设计目标:
  * 全部数值由固定种子推导,任何机器上重跑结果完全一致(便于比对结果可复现);
  * 保留少量 NULL / 空串,用于验证数据比对里的「NULL 与空串视为一致」以及数据完整率;
  * 明确留出 8 个"关键编码",供三方厂商库构造 缺失/差异 场景(见 04-vendor-data.sql):
      - 缺失(基准有/厂商无): 330100000012、330100000055、330100000077
      - 差异(两边都有值不同): 330100000007、330100000023、330100000041、
                              330100000068、330100000090

用法:
    python3 gen-base-data.py > mysql-init/02-base-data.sql
"""

import datetime
import random

SEED = 20260801
ROW_COUNT = 100
CODE_PREFIX = "330100000"  # 9 位前缀 + 3 位序号 = 12 位水库编码(贴近水利对象编码长度)

ADJECTIVES = ["青", "龙", "白", "黑", "金", "银", "红", "翠", "碧", "清",
              "云", "天", "大", "小", "新", "老", "东", "西", "南", "北"]
LANDFORMS = ["山", "河", "湖", "溪", "潭", "泉", "岭", "峰", "湾", "洲"]

BASINS = ["长江流域", "黄河流域", "珠江流域", "淮河流域", "海河流域",
          "松辽流域", "太湖流域", "东南诸河", "西南诸河", "西北诸河"]
RIVERS = ["长江", "黄河", "珠江", "淮河", "海河", "辽河", "松花江", "太湖", "钱塘江", "闽江"]
REGIONS = ["浙江省杭州市", "江苏省南京市", "安徽省合肥市", "江西省南昌市", "湖北省武汉市",
           "湖南省长沙市", "四川省成都市", "广东省广州市", "山东省济南市", "河南省郑州市"]
UNIT_SUFFIX = ["市水利局", "县水利局", "水库管理处", "水务集团", "水库管理所"]


def main() -> None:
    rng = random.Random(SEED)

    # 100 个不重复水库名(从 200 个「修饰词+地貌+水库」组合里抽样)
    all_names = [a + l + "水库" for a in ADJECTIVES for l in LANDFORMS]
    names = rng.sample(all_names, ROW_COUNT)

    lines = []
    lines.append("-- 基准库数据:reservoir_base.reservoir_base_info 共 100 条水库")
    lines.append("-- 本文件由 gen-base-data.py 生成(固定随机种子 %d),请勿手工追加行" % SEED)
    lines.append("-- 关键编码(供厂商库构造差异,勿改动):")
    lines.append("--   缺失: 330100000012 / 330100000055 / 330100000077")
    lines.append("--   差异: 330100000007 / 330100000023 / 330100000041 / 330100000068 / 330100000090")
    lines.append("")
    lines.append("SET NAMES utf8mb4;")
    lines.append("USE reservoir_base;")
    lines.append("")
    lines.append("INSERT INTO reservoir_base_info")
    lines.append("  (reservoir_code, reservoir_name, reservoir_type, basin_name, river_name,")
    lines.append("   admin_division, longitude, latitude, total_capacity, dam_height,")
    lines.append("   build_year, manage_unit, status, remark, created_at, updated_at)")
    lines.append("VALUES")

    rows = []
    for i in range(1, ROW_COUNT + 1):
        code = CODE_PREFIX + "%03d" % i
        name = names[i - 1]

        # 水库类型:大型稀少、中型次之、其余小型
        if i % 17 == 0:
            rtype = "大型"
        elif i % 4 == 0:
            rtype = "中型"
        else:
            rtype = "小型"

        basin = BASINS[i % len(BASINS)]
        river = None if i % 19 == 0 else RIVERS[i % len(RIVERS)]
        region = REGIONS[i % len(REGIONS)]
        lon = None if i % 23 == 0 else round(95 + (i * 137 % 2800) / 100.0, 6)
        lat = None if i % 23 == 0 else round(20 + (i * 211 % 2200) / 100.0, 6)
        capacity = round(120 + (i * 457 % 19800) + (i % 9) * 0.5, 4)
        dam = None if i % 31 == 0 else round(6 + (i * 83 % 180) + (i % 7) * 0.1, 2)
        year = 1950 + (i * 29 % 72)

        # 工程状态:以正常运行 为主,少量除险加固 / 报废
        if i % 23 == 0:
            status = "除险加固"
        elif i % 37 == 0:
            status = "报废"
        else:
            status = "正常运行"

        unit = region[:3] + UNIT_SUFFIX[i % len(UNIT_SUFFIX)]

        # 备注:少量 NULL / 空串
        if i % 13 == 0:
            remark = None
        elif i % 7 == 0:
            remark = ""
        else:
            remark = "重点%d类水库" % (i % 4 + 1)

        ts = (datetime.datetime(2026, 8, 1, 9, 0, 0)
              + datetime.timedelta(minutes=i * 3)).strftime("%Y-%m-%d %H:%M:%S")

        rows.append(
            "  ('%s', '%s', '%s', '%s', %s, '%s', %s, %s, %s, %s, %d, '%s', '%s', %s, '%s', '%s')"
            % (code, name, rtype, basin, sql_str(river), region,
               sql_num(lon), sql_num(lat), sql_num(capacity), sql_num(dam),
               year, unit, status, sql_str(remark), ts, ts)
        )

    lines.append(",\n".join(rows) + ";")
    lines.append("")
    print("\n".join(lines))


def sql_str(v):
    """字符串字面量:None -> NULL,其余单引号包裹(数据内无单引号)。"""
    if v is None:
        return "NULL"
    return "'%s'" % v


def sql_num(v):
    """数值字面量:None -> NULL。"""
    if v is None:
        return "NULL"
    return repr(v)


if __name__ == "__main__":
    main()
