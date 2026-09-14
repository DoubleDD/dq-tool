#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 水库_预览.xlsx 的 4 个数据页签灌进 compare-mysql 测试库(MySQL 8.0,127.0.0.1:3307)。

页签 -> 目标表(预览目录里没有 em_td_reservoir / P201_small / LNS_CONSTRUCTION_BUSINESS
/ LNS_SKGL_BUSINESS 的页签,目录中未登记的 sk_hzz 按页签名建表):

  reservoir          -> WaterSupplySMS.reservoir      (英文表头,第 2 行为空行,跳过)
  att_res_base       -> hyd_ln.att_res_base           (第 1 行英文表头,第 2 行中文注释 -> 列注释)
  sk_hzz             -> hyd_ln.sk_hzz                 (中文表头)
  LNS_RES_BUSINESS   -> hyd_ln.LNS_RES_BUSINESS       (第 1 行英文表头,第 2 行中文注释 -> 列注释)

列类型按数据推断:整数 -> BIGINT,小数 -> DECIMAL(精度按数据取),日期 -> DATETIME,
其余 -> VARCHAR(长度按数据取,过长用 TEXT)。纯数字但带前导零的编码列保持 VARCHAR。

用法:
  python3 docker-test-env/compare-mysql/test-data/load-preview-data.py > /tmp/load-preview.sql
  docker exec -i test-mysql-compare mysql -uroot -p"Test@12345" --default-character-set=utf8mb4 \
    < /tmp/load-preview.sql
"""
import re
import sys
from datetime import datetime
from pathlib import Path

import openpyxl

XLSX = Path(__file__).resolve().parent / "水库_预览.xlsx"

# (页签名, 目标库, 目标表, 表注释, 中文注释行在表头后第几行,0 表示没有)
SHEETS = [
    ("reservoir", "WaterSupplySMS", "reservoir",
     "来源:辽宁省小型水库报汛系统 WaterSupplySMS.dbo.reservoir", 0),
    ("att_res_base", "hyd_ln", "att_res_base",
     "来源:辽宁省山洪在监测预警平台 hyd-ln.gw_hyd.att_res_base", 1),
    ("sk_hzz", "hyd_ln", "sk_hzz",
     "来源:水库_预览.xlsx 页签 sk_hzz(预览目录未登记,列语义与 att_res_base 一致)", 0),
    ("LNS_RES_BUSINESS", "hyd_ln", "LNS_RES_BUSINESS",
     "来源:辽宁省水库矩阵运管平台 hyd-ln.LNS_RESERVOIR_BUSINESS.LNS_RES_BUSINESS", 1),
]

DT_FMTS = ["%Y-%m-%d %H:%M:%S", "%Y-%m-%d"]
INT_RE = re.compile(r"^-?\d+$")
DEC_RE = re.compile(r"^-?\d+\.\d+$")


def col_name(raw: str, idx: int, used: set) -> str:
    """清洗列名:去空白,空名/重名补后缀,全部反引号引用由调用方处理。"""
    name = (raw or "").strip() or f"col_{idx + 1}"
    base, n = name, 2
    while name in used:
        name = f"{base}_{n}"
        n += 1
    used.add(name)
    return name


def infer_type(values: list):
    """按非空值推断列类型,返回 (ddl_type, kind);kind ∈ int/dec/dt/str。"""
    samples = [v for v in values if v not in (None, "")]
    if not samples:
        return "VARCHAR(64)", "str"
    is_int = all(INT_RE.match(v) for v in samples)
    if is_int:
        # 前导零编码(如 02900001)不能用数字类型,否则丢零
        if any(len(v.lstrip("-")) > 1 and v.lstrip("-").startswith("0") for v in samples):
            return varchar_type(samples), "str"
        return "BIGINT", "int"
    is_dec = all(INT_RE.match(v) or DEC_RE.match(v) for v in samples)
    if is_dec:
        int_digits = max(len(v.split(".")[0].lstrip("-")) for v in samples)
        scale = max((len(v.split(".")[1]) for v in samples if "." in v), default=0)
        scale = min(max(scale + 2, 4), 20)
        precision = min(int_digits + scale + 2, 65)
        return f"DECIMAL({precision},{scale})", "dec"
    for fmt in DT_FMTS:
        try:
            for v in samples:
                datetime.strptime(v, fmt)
            return "DATETIME", "dt"
        except ValueError:
            pass
    return varchar_type(samples), "str"


def varchar_type(samples: list) -> str:
    maxlen = max(len(v) for v in samples)
    if maxlen > 16000:
        return "MEDIUMTEXT"
    return f"VARCHAR({min(max(int(maxlen * 1.6), 32), 16383)})"


def esc(v) -> str:
    """SQL 字符串字面量转义(配合 --default-character-set=utf8mb4)。"""
    if v is None:
        return "NULL"
    s = str(v).replace("\\", "\\\\").replace("'", "\\'")
    s = s.replace("\r", "\\r").replace("\n", "\\n")
    return f"'{s}'"


def main() -> None:
    wb = openpyxl.load_workbook(XLSX, read_only=True)
    out = sys.stdout
    out.write("SET NAMES utf8mb4;\n\n")
    for sheet, db, table, comment, comment_row in SHEETS:
        rows = [r for r in wb[sheet].iter_rows(values_only=True)]
        header = [col_name(c, i, set()) for i, c in enumerate(rows[0])]
        # 重新生成,保证去重时可见全量已用名
        used: set = set()
        header = [col_name(c, i, used) for i, c in enumerate(rows[0])]
        col_comments = None
        data = rows[1:]
        if comment_row:
            col_comments = [str(c).strip() if c else "" for c in rows[comment_row]]
            data = rows[comment_row + 1:]
        data = [r for r in data if any(c not in (None, "") for c in r)]  # 去掉全空行

        cols = []
        for i, name in enumerate(header):
            raw = [(str(r[i]).strip() if r[i] is not None else None)
                   if i < len(r) else None for r in data]
            ddl, kind = infer_type([v for v in raw if v is not None])
            cmt = f" COMMENT '{esc(col_comments[i])[1:-1]}'" if col_comments and col_comments[i] else ""
            cols.append((name, ddl, kind, cmt))
        out.write(f"CREATE DATABASE IF NOT EXISTS `{db}` DEFAULT CHARACTER SET utf8mb4 "
                  f"DEFAULT COLLATE utf8mb4_general_ci;\nUSE `{db}`;\n\n")
        out.write(f"DROP TABLE IF EXISTS `{table}`;\nCREATE TABLE `{table}` (\n")
        out.write(",\n".join(f"  `{c[0]}` {c[1]} DEFAULT NULL{c[3]}" for c in cols))
        out.write(f"\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT={esc(comment)};\n\n")

        names = ", ".join(f"`{c[0]}`" for c in cols)
        batch = []
        for r in data:
            vals = []
            for i, (_, _, kind, _) in enumerate(cols):
                v = r[i] if i < len(r) else None
                if isinstance(v, str):
                    v = v.strip()
                if v == "" and kind != "str":
                    v = None
                vals.append(esc(v))
            batch.append(f"({', '.join(vals)})")
            if len(batch) == 100:
                out.write(f"INSERT INTO `{table}` ({names}) VALUES\n" + ",\n".join(batch) + ";\n\n")
                batch = []
        if batch:
            out.write(f"INSERT INTO `{table}` ({names}) VALUES\n" + ",\n".join(batch) + ";\n\n")
        print(f"-- {db}.{table}: {len(data)} 行 {len(cols)} 列", file=sys.stderr)

    # dq 应用账号授权(与 mysql-init/05-init-user.sql 口径一致)
    out.write("GRANT ALL PRIVILEGES ON `WaterSupplySMS`.* TO 'dq'@'%';\n")
    out.write("GRANT ALL PRIVILEGES ON `hyd_ln`.*     TO 'dq'@'%';\n")
    out.write("FLUSH PRIVILEGES;\n")


if __name__ == "__main__":
    main()
