#!/usr/bin/env bash
# dq-tool 局域网共享 E2E 驱动脚本:激活双实例 → A 造数据 → 验证 B 自动发现 A → B 拉取 → B 验证合并结果
# 所有请求/响应证据落盘到 .lan-e2e/evidence/
set -u
cd "$(dirname "$0")"

A=http://localhost:10101
B=http://localhost:10102
EVIDENCE=evidence
mkdir -p "$EVIDENCE"
LICENSE_CODE='DQ1.5YaF6YOo6aqM5pS25rWL6K-VfFBFUk1BTkVOVHwxLjkuOHx8fDg2MzI2YWIwNGZhODRlZGI4YmVkNDZlZGRjMzM5MTIyfDE3ODg3MDM0MzQ0NzB8.RynuMlVlK8MrsWPGqlD5kHcRYg_kWM047UrLjvHFsmtEdo2W5mvtQal9nka-JKVi8tB4iTLh1ih9BHfMD9nYAg'

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
# assert_json_field <描述> <json文件> <jq 表达式(期望为 true)>
assert_jq() {
  if jq -e "$3" "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1 (jq: $3)"; cat "$2"; fi
}

req() { # req <方法> <实例base> <路径> [body] [保存文件名]
  local method=$1 base=$2 path=$3 body=${4:-} out=${5:-}
  local args=(-s -X "$method" -H 'Content-Type: application/json')
  [ -n "$body" ] && args+=(-d "$body")
  if [ -n "$out" ]; then
    curl "${args[@]}" "$base$path" | tee "$EVIDENCE/$out" | jq . > /dev/null 2>&1 || true
  else
    curl "${args[@]}" "$base$path" > /dev/null
  fi
}

echo "== 0. 等待两个实例就绪 =="
for base in $A $B; do
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/health" || true)
    [ "$code" = "200" ] && break; sleep 2
  done
  echo "  $base health=$code"
  [ "$code" = "200" ] || { echo "实例未就绪: $base"; exit 1; }
done
# MySQL 演示库就绪(含初始化种子数据)后扫描才能建
echo "== 0b. 等待 MySQL 健康 =="
for i in $(seq 1 60); do
  st=$(docker inspect -f '{{.State.Health.Status}}' dq-lan-mysql 2>/dev/null || echo unknown)
  [ "$st" = "healthy" ] && break; sleep 2
done
echo "  mysql=$st"
[ "$st" = "healthy" ] || { echo "MySQL 未就绪"; exit 1; }

echo "== 1. 激活两个实例(测试授权码) =="
req POST $A /api/license/activate "{\"code\":\"$LICENSE_CODE\"}" 01-a-activate.json
req POST $B /api/license/activate "{\"code\":\"$LICENSE_CODE\"}" 02-b-activate.json
assert_jq "A 激活成功" $EVIDENCE/01-a-activate.json '.activated == true'
assert_jq "B 激活成功" $EVIDENCE/02-b-activate.json '.activated == true'

echo "== 2. 设置实例名称(心跳广播用) =="
req PUT $A /api/lan/settings '{"instanceName":"实例A-小王的电脑"}' 03-a-settings.json
req PUT $B /api/lan/settings '{"instanceName":"实例B-小李的电脑"}' 04-b-settings.json
assert_jq "A 实例名生效" $EVIDENCE/03-a-settings.json '.instanceName == "实例A-小王的电脑" and .running == true'
assert_jq "B 实例名生效" $EVIDENCE/04-b-settings.json '.instanceName == "实例B-小李的电脑" and .running == true'

echo "== 3. 实例 A 造数据:数据源 + 标记 + 扫描 + 表描述 + 打标 + 所属系统 =="
req POST $A /api/datasources '{"name":"演示MySQL","jdbcUrl":"jdbc:mysql://mysql:3306/dqdemo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","username":"dquser","password":"Dq@12345"}' 05-a-datasource.json
DS_A=$(jq -r '.id' $EVIDENCE/05-a-datasource.json)
[ "$DS_A" != "null" ] && [ -n "$DS_A" ] && ok "A 数据源已建(id=$DS_A)" || { bad "A 数据源创建失败"; exit 1; }

req POST $A /api/tags '{"name":"核心业务","color":"#F56C6C","description":"核心业务系统相关表","tagType":"AI"}' 06-a-tag1.json
req POST $A /api/tags '{"name":"需治理","color":"#E6A23C","description":"存在空值/缺失需治理","tagType":"MANUAL"}' 07-a-tag2.json
TAG1=$(jq -r '.id' $EVIDENCE/06-a-tag1.json)
TAG2=$(jq -r '.id' $EVIDENCE/07-a-tag2.json)
[ "$TAG1" != "null" ] && ok "A 标记已建(核心业务=$TAG1, 需治理=$TAG2)" || { bad "A 标记创建失败"; exit 1; }

req POST $A /api/scans "{\"datasourceId\":$DS_A,\"schema\":\"dqdemo\",\"genDoc\":false}" 08-a-scan-create.json
JOB=$(jq -r '.jobId' $EVIDENCE/08-a-scan-create.json)
[ "$JOB" != "null" ] && [ -n "$JOB" ] && ok "A 扫描任务已建(jobId=$JOB)" || { bad "A 扫描创建失败"; exit 1; }
for i in $(seq 1 60); do
  st=$(curl -s "$A/api/scans/$JOB" | jq -r '.status')
  [ "$st" = "DONE" ] || [ "$st" = "FAILED" ] && break; sleep 2
done
curl -s "$A/api/scans/$JOB" > $EVIDENCE/09-a-scan-job.json
assert_jq "A 扫描完成(DONE)" $EVIDENCE/09-a-scan-job.json '.status == "DONE"'

# A 的第二个数据源「分析库」(B 上没有,用于验证「同步数据源到本机(不含密码)」);须在种子导入前建好(按名匹配)
req POST $A /api/datasources '{"name":"分析库","jdbcUrl":"jdbc:mysql://mysql:3306/dqdemo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","username":"dquser","password":"Dq@12345"}' 10b-a-datasource2.json
assert_jq "A 第二数据源「分析库」已建" $EVIDENCE/10b-a-datasource2.json '.id != null'
# 表描述(走标准标注导入落库;手动编辑接口只 UPDATE 已有行,无行时静默不落库,不适用种子场景) + 打标 + 所属系统
curl -s -X POST -F "file=@seed-annotations.json" "$A/api/annotations/import" > $EVIDENCE/10-a-doc-import.json
assert_jq "A 表描述种子已落库(2 条,分属两个数据源)" $EVIDENCE/10-a-doc-import.json '.docsUpserted == 2'
req PUT "$A" "/api/datasources/$DS_A/schemas/dqdemo/tables/reservoir_info/tags" "{\"tagIds\":[$TAG1,$TAG2]}" 11-a-tabletags.json
req PUT "$A" "/api/datasources/$DS_A/schemas/dqdemo/table-systems" '{"tableNames":["reservoir_info"],"systemName":"水库矩阵平台"}' 12-a-system.json
ok "A 表描述/打标/所属系统已设置"

echo "== 4. 实例 B 建数据源(故意用不同名「我的MySQL」,验证数据源映射) =="
req POST $B /api/datasources '{"name":"我的MySQL","jdbcUrl":"jdbc:mysql://mysql:3306/dqdemo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","username":"dquser","password":"Dq@12345"}' 13-b-datasource.json
DS_B=$(jq -r '.id' $EVIDENCE/13-b-datasource.json)
[ "$DS_B" != "null" ] && ok "B 数据源已建(id=$DS_B,与 A 异名)" || { bad "B 数据源创建失败"; exit 1; }

echo "== 5. 验证 UDP 广播自动发现(B 发现 A,A 发现 B) =="
for i in $(seq 1 20); do
  curl -s "$B/api/lan/peers" > $EVIDENCE/14-b-peers.json
  jq -e 'any(.instanceName == "实例A-小王的电脑")' $EVIDENCE/14-b-peers.json >/dev/null 2>&1 && break
  sleep 2  # 改名在下个心跳(≤5s)生效,按名等待
done
assert_jq "B 在局域网发现 A(UDP 广播)" $EVIDENCE/14-b-peers.json 'any(.instanceName == "实例A-小王的电脑" and .httpPort == 10000)'
for i in $(seq 1 20); do
  curl -s "$A/api/lan/peers" > $EVIDENCE/15-a-peers.json
  jq -e 'any(.instanceName == "实例B-小李的电脑")' $EVIDENCE/15-a-peers.json >/dev/null 2>&1 && break
  sleep 2
done
assert_jq "A 在局域网发现 B(双向发现)" $EVIDENCE/15-a-peers.json 'any(.instanceName == "实例B-小李的电脑")'
PEER_ID_ON_B=$(jq -r '.[0].instanceId' $EVIDENCE/14-b-peers.json)

echo "== 5b. 同步前预览(共享出口预览端点 + 拉取侧聚合预览) =="
curl -s "$A/api/lan/share/annotations/preview" > $EVIDENCE/15c-a-share-annotations-preview.json
assert_jq "A 标注预览:2 个标记 + 2 个数据源分布" $EVIDENCE/15c-a-share-annotations-preview.json \
  '(.tags | length) == 2 and (.datasources | length) == 2'
assert_jq "A 标注预览含逐行明细:打标(2 行带表名与标记名)、描述原文、所属系统" $EVIDENCE/15c-a-share-annotations-preview.json \
  '(.tableTags | length) == 2
   and (.tableTags | all(.tableName == "reservoir_info"))
   and ([.tableTags[].tagName] | sort) == (["核心业务","需治理"] | sort)
   and ([.tableDocs[].description] | any(contains("水库基本信息主档表")))
   and .tableSystems[0].systemName == "水库矩阵平台"'
curl -s "$A/api/lan/share/datasources" > $EVIDENCE/15f-a-share-datasources.json
assert_jq "A 数据源共享清单:含名称/地址/用户名 + TransferCrypto 密文密码(非明文)" $EVIDENCE/15f-a-share-datasources.json \
  'length == 2 and all(.jdbcUrl != "" and .username == "dquser" and .passwordEnc != null and .passwordEnc != "") and (tostring | contains("Dq@12345") | not)'
curl -s "$A/api/lan/share/scans/preview" > $EVIDENCE/15d-a-share-scans-preview.json
assert_jq "A 扫描任务预览:1 个 DONE 任务" $EVIDENCE/15d-a-share-scans-preview.json \
  'length == 1 and .[0].datasourceName == "演示MySQL" and .[0].status == "DONE" and .[0].totalTables == 2'
curl -s "$B/api/lan/preview/$PEER_ID_ON_B" > $EVIDENCE/15e-b-peer-preview.json
assert_jq "B 聚合预览:拿到 A 的标注与扫描预览" $EVIDENCE/15e-b-peer-preview.json \
  '.peerInstanceName == "实例A-小王的电脑" and (.annotations.tags | length) == 2 and (.scanJobs | length) == 1'
assert_jq "B 聚合预览含逐行明细(打标 2 行 + 描述 2 行 + 系统 1 行)" $EVIDENCE/15e-b-peer-preview.json \
  '(.annotations.tableTags | length) == 2 and (.annotations.tableDocs | length) == 2 and (.annotations.tableSystems | length) == 1'
assert_jq "B 聚合预览含数据源映射:对方 2 个数据源本机均无同名(matchedLocalId=null),本机清单含「我的MySQL」" $EVIDENCE/15e-b-peer-preview.json \
  '(.datasources | length) == 2 and (.datasources | all(.matchedLocalId == null)) and (.localDatasources | any(.name == "我的MySQL"))'
REMOTE_JOB=$(jq -r '.scanJobs[0].jobId' $EVIDENCE/15e-b-peer-preview.json)

echo "== 6. B 从 A 拉取:勾「核心业务」+描述+所属系统;演示MySQL→我的MySQL(异名映射),分析库→新建无密码副本 =="
req POST $B "/api/lan/pull/$PEER_ID_ON_B" "{\"annotations\":{\"tagNames\":[\"核心业务\"],\"includeTableTags\":true,\"includeDocs\":true,\"includeSystems\":true},\"dsMapping\":{\"演示MySQL\":$DS_B},\"createDatasources\":[\"分析库\"]}" 16-b-pull-annotations.json
assert_jq "拉取无错误" $EVIDENCE/16-b-pull-annotations.json '(.errors | length) == 0'
assert_jq "只新建勾选的 1 个标记" $EVIDENCE/16-b-pull-annotations.json '.annotations.tagsCreated == 1'
assert_jq "表级打标只带勾选标记的关系(1 条)" $EVIDENCE/16-b-pull-annotations.json '.annotations.tableTagsAdded == 1'
assert_jq "表描述已合并(2 条,含「分析库」上的)" $EVIDENCE/16-b-pull-annotations.json '.annotations.docsUpserted == 2'
assert_jq "表所属系统已合并" $EVIDENCE/16-b-pull-annotations.json '.annotations.systemsUpserted == 1'
assert_jq "「分析库」已同步到本机(无密码副本)" $EVIDENCE/16-b-pull-annotations.json '.datasourcesCreated == ["分析库"]'
curl -s "$B/api/tags" > $EVIDENCE/16b-b-tags-partial.json
assert_jq "B 只有「核心业务」,没有未勾选的「需治理」" $EVIDENCE/16b-b-tags-partial.json \
  'any(.[]; .name == "核心业务") and ([.[] | select(.name == "需治理")] | length) == 0'
curl -s "$B/api/datasources" > $EVIDENCE/16d-b-datasources.json
assert_jq "B 上「分析库」存在且带密码(hasPassword=true,密文随行)" $EVIDENCE/16d-b-datasources.json \
  'any(.[]; .name == "分析库" and .hasPassword == true)'
DS_B_FX=$(jq -r '.[] | select(.name == "分析库") | .id' $EVIDENCE/16d-b-datasources.json)
# 连通实测:副本带密码(秘密字段留空回落已存值),能连上 MySQL 才算密码真同步过来了
req POST $B /api/datasources/test '{"id":'"$DS_B_FX"',"jdbcUrl":"jdbc:mysql://mysql:3306/dqdemo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","username":"dquser"}' 16g-b-fx-test.json
assert_jq "B 的「分析库」副本可直接连通(密码已随行)" $EVIDENCE/16g-b-fx-test.json '.success == true'
curl -s "http://localhost:10101/api/datasources" > $EVIDENCE/16e-a-datasources.json
assert_jq "A 上「分析库」密码未受影响(hasPassword=true)" $EVIDENCE/16e-a-datasources.json \
  'any(.[]; .name == "分析库" and .hasPassword == true)'
curl -s "$B/api/datasources/$DS_B_FX/schemas/dqdemo/table-docs" > $EVIDENCE/16f-b-fx-docs.json
assert_jq "B 的「分析库」上描述已落(water_level_log)" $EVIDENCE/16f-b-fx-docs.json \
  'to_entries | any(.key == "water_level_log" and (.value | contains("水位观测记录流水表")))'

echo "== 6b. 二次拉取补勾选「需治理」(增量选择,异名映射) =="
req POST $B "/api/lan/pull/$PEER_ID_ON_B" "{\"annotations\":{\"tagNames\":[\"需治理\"],\"includeTableTags\":true,\"includeDocs\":false,\"includeSystems\":false},\"dsMapping\":{\"演示MySQL\":$DS_B,\"分析库\":$DS_B_FX}}" 16c-b-pull-second.json
assert_jq "补拉新建「需治理」并补上它的打标关系" $EVIDENCE/16c-b-pull-second.json \
  '.annotations.tagsCreated == 1 and .annotations.tableTagsAdded == 1 and .annotations.docsUpserted == 0'

echo "== 7. B 从 A 拉取:勾选扫描任务(异名映射:演示MySQL→我的MySQL) =="
req POST $B "/api/lan/pull/$PEER_ID_ON_B" "{\"scanJobIds\":[$REMOTE_JOB],\"dsMapping\":{\"演示MySQL\":$DS_B}}" 17-b-pull-scans.json
assert_jq "扫描记录拉取无错误" $EVIDENCE/17-b-pull-scans.json '(.errors | length) == 0'
assert_jq "扫描任务已导入(1个)" $EVIDENCE/17-b-pull-scans.json '.scans.imported == 1'

echo "== 8. 重复拉取幂等性(全选重拉,不产生重复数据) =="
req POST $B "/api/lan/pull/$PEER_ID_ON_B" "{\"annotations\":{\"tagNames\":[\"核心业务\",\"需治理\"],\"includeTableTags\":true,\"includeDocs\":true,\"includeSystems\":true},\"scanJobIds\":[$REMOTE_JOB],\"dsMapping\":{\"演示MySQL\":$DS_B,\"分析库\":$DS_B_FX}}" 18-b-pull-again.json
assert_jq "重复拉取扫描任务被去重(skipped=1)" $EVIDENCE/18-b-pull-again.json '.scans.skipped == 1 and .scans.imported == 0'
assert_jq "重复拉取标记按名合并(tagsCreated=0)" $EVIDENCE/18-b-pull-again.json '.annotations.tagsCreated == 0'

echo "== 9. B 侧数据核验(与 A 一致) =="
curl -s "$B/api/tags" > $EVIDENCE/19-b-tags.json
assert_jq "B 标记列表含同步来的「核心业务」「需治理」" $EVIDENCE/19-b-tags.json 'any(.[]; .name == "核心业务") and any(.[]; .name == "需治理")'
curl -s "$B/api/datasources/$DS_B/schemas/dqdemo/table-docs" > $EVIDENCE/20-b-table-docs.json
assert_jq "B 表描述已同步(reservoir_info)" $EVIDENCE/20-b-table-docs.json 'to_entries | any(.key == "reservoir_info" and (.value | contains("水库基本信息主档表")))'
curl -s "$B/api/datasources/$DS_B/schemas/dqdemo/table-systems" > $EVIDENCE/21-b-table-systems.json
assert_jq "B 表所属系统已同步(水库矩阵平台)" $EVIDENCE/21-b-table-systems.json 'to_entries | any(.key == "reservoir_info" and .value == "水库矩阵平台")'
curl -s "$B/api/scans?datasourceId=$DS_B" > $EVIDENCE/22-b-scans.json
assert_jq "B 扫描记录列表含导入任务(DONE, 2 张表)" $EVIDENCE/22-b-scans.json 'any(.[]; .status == "DONE" and .totalTables == 2)'
IMPORTED_JOB=$(jq -r '.[0].id' $EVIDENCE/22-b-scans.json)
curl -s "$B/api/scans/$IMPORTED_JOB" > $EVIDENCE/23-b-scan-detail.json
assert_jq "B 可查看导入任务的字段级明细" $EVIDENCE/23-b-scan-detail.json '.status == "DONE"'

echo
echo "==============================================="
echo "  E2E 结果: PASS=$PASS  FAIL=$FAIL"
echo "==============================================="
[ "$FAIL" = "0" ]
