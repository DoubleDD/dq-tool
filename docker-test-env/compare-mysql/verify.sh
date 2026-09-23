#!/usr/bin/env bash
# =============================================================================
# 数据比对测试环境自检:校验「基准库 100 / 厂商库 101、缺失3 / 多余4 / 差异5」是否成立。
#
# 用法:
#   bash docker-test-env/compare-mysql/verify.sh
#   可用环境变量覆盖:CONTAINER=test-mysql-compare MYSQL_USER=dq MYSQL_PASSWORD=dq123456
# 退出码:0 = 全部符合预期;1 = 有不符合项(会逐条打印)
# =============================================================================
set -uo pipefail

CONTAINER="${CONTAINER:-test-mysql-compare}"
DB_USER="${MYSQL_USER:-dq}"
DB_PASS="${MYSQL_PASSWORD:-dq123456}"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "找不到容器 $CONTAINER,请先执行:"
  echo "  docker compose -f docker-test-env/compare-mysql/docker-compose.yml up -d"
  exit 1
fi

# 静默执行 SQL,只取裸值(去掉 mysql 的密码告警)
# 显式指定 utf8mb4:若用客户端默认的 latin1 读取,双重编码的中文会被"对称还原"而掩盖问题
q() {
  docker exec "$CONTAINER" mysql --default-character-set=utf8mb4 -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" -N -B -e "$1" 2>/dev/null
}

FAIL=0
check() { # $1=名称 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then
    printf '  [通过] %-28s 期望=%-6s 实际=%s\n' "$1" "$2" "$3"
  else
    printf '  [失败] %-28s 期望=%-6s 实际=%s\n' "$1" "$2" "$3"
    FAIL=1
  fi
}

echo "=== 1. 行数与对象数量 ==="
check "基准库行数"        100 "$(q 'SELECT COUNT(*) FROM reservoir_base.reservoir_base_info')"
check "厂商库行数"        101 "$(q 'SELECT COUNT(*) FROM reservoir_vendor.t_reservoir_info')"
check "双方都有的对象(matched)" 97 \
  "$(q 'SELECT COUNT(*) FROM reservoir_base.reservoir_base_info b JOIN reservoir_vendor.t_reservoir_info v ON v.RESERVOIR_CODE=b.reservoir_code')"
check "缺失 MISSING(基准有/厂商无)" 3 \
  "$(q 'SELECT COUNT(*) FROM reservoir_base.reservoir_base_info b LEFT JOIN reservoir_vendor.t_reservoir_info v ON v.RESERVOIR_CODE=b.reservoir_code WHERE v.vendor_id IS NULL')"
check "多余 EXTRA(厂商有/基准无)" 4 \
  "$(q 'SELECT COUNT(*) FROM reservoir_vendor.t_reservoir_info v LEFT JOIN reservoir_base.reservoir_base_info b ON b.reservoir_code=v.RESERVOIR_CODE WHERE b.reservoir_code IS NULL')"

echo
echo "=== 2. 逐字段比对(共 16 个基准字段) ==="
DIFF_STAT="$(q "
SELECT COUNT(*), SUM(mm>0), COALESCE(SUM(mm),0) FROM (
  SELECT
    (NOT (b.reservoir_name <=> v.RESERVOIR_NAME))
  + (NOT (b.reservoir_type <=> v.Reservoir_Type))
  + (NOT (b.basin_name <=> v.basin_name))
  + (NOT (COALESCE(b.river_name,'') <=> COALESCE(v.river_name,'')))
  + (NOT (b.admin_division <=> v.admin_division))
  + (NOT (b.longitude <=> CAST(v.Longitude AS DECIMAL(10,6))))
  + (NOT (b.latitude <=> CAST(v.Latitude AS DECIMAL(10,6))))
  + (NOT (b.total_capacity <=> CAST(v.Total_Capacity AS DECIMAL(18,4))))
  + (NOT (b.dam_height <=> CAST(v.Dam_Height AS DECIMAL(10,2))))
  + (NOT (b.build_year <=> v.BUILD_YEAR))
  + (NOT (b.manage_unit <=> v.Manage_Unit))
  + (NOT (b.status <=> v.STATUS))
  + (NOT (COALESCE(b.remark,'') <=> COALESCE(v.remark,'')))
  + (NOT (b.created_at <=> v.created_at))
  + (NOT (b.updated_at <=> v.updated_at)) AS mm
  FROM reservoir_base.reservoir_base_info b
  JOIN reservoir_vendor.t_reservoir_info v ON v.RESERVOIR_CODE=b.reservoir_code
) t;")"
MATCHED="$(echo "$DIFF_STAT" | cut -f1)"
DIFF_ROWS="$(echo "$DIFF_STAT" | cut -f2)"
FIELD_MM="$(echo "$DIFF_STAT" | cut -f3)"
check "差异 DIFF 行数" 5 "$DIFF_ROWS"
check "不一致字段总次数" 7 "$FIELD_MM"
check "完全一致 SAME 行数" 92 "$((MATCHED - DIFF_ROWS))"

echo
echo "=== 3. 关键编码抽样 ==="
MISSING_CODES="$(q "SELECT GROUP_CONCAT(b.reservoir_code ORDER BY b.reservoir_code) FROM reservoir_base.reservoir_base_info b LEFT JOIN reservoir_vendor.t_reservoir_info v ON v.RESERVOIR_CODE=b.reservoir_code WHERE v.vendor_id IS NULL")"
EXTRA_CODES="$(q "SELECT GROUP_CONCAT(v.RESERVOIR_CODE ORDER BY v.RESERVOIR_CODE) FROM reservoir_vendor.t_reservoir_info v LEFT JOIN reservoir_base.reservoir_base_info b ON b.reservoir_code=v.RESERVOIR_CODE WHERE b.reservoir_code IS NULL")"
check "缺失编码" "330100000012,330100000055,330100000077" "$MISSING_CODES"
check "多余编码" "330199000001,330199000002,330199000003,330199000004" "$EXTRA_CODES"

echo
echo "=== 4. 中文编码(防止初始化时被 latin1 双重编码) ==="
# 330100000001 水库名为「新河水库」:4 个字符 / UTF-8 12 字节;双重编码会变成 12 字符 / 26 字节
check "中文名称字符数(新河水库=4)" 4 \
  "$(q "SELECT CHAR_LENGTH(reservoir_name) FROM reservoir_base.reservoir_base_info WHERE reservoir_code='330100000001'")"
check "中文名称字节数(UTF-8=12)" 12 \
  "$(q "SELECT LENGTH(reservoir_name) FROM reservoir_base.reservoir_base_info WHERE reservoir_code='330100000001'")"
check "厂商库中文名称字符数" 4 \
  "$(q "SELECT CHAR_LENGTH(RESERVOIR_NAME) FROM reservoir_vendor.t_reservoir_info WHERE RESERVOIR_CODE='330100000001'")"
check "字段注释中文正常(水库编码)" "水库编码,12位水利对象编码,主键" \
  "$(q "SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='reservoir_base' AND TABLE_NAME='reservoir_base_info' AND COLUMN_NAME='reservoir_code'")"

echo
echo "=== 5. config.sys_dict 字典表 ==="
check "sys_dict 行数" 14 "$(q 'SELECT COUNT(*) FROM config.sys_dict')"
check "sys_dict 字段注释(数据库实例名称)" "数据库实例名称" \
  "$(q "SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='config' AND TABLE_NAME='sys_dict' AND COLUMN_NAME='db_code'")"
check "db_code 均有对应库(未命中数)" 0 \
  "$(q "SELECT COUNT(*) FROM config.sys_dict d LEFT JOIN information_schema.SCHEMATA s ON s.SCHEMA_NAME=d.db_code WHERE s.SCHEMA_NAME IS NULL")"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 自检通过:该环境符合「缺失3 / 多余4 / 差异5」的预期。"
else
  echo "❌ 自检未通过,请检查初始化脚本是否被修改或数据被改动过。"
fi
exit "$FAIL"
