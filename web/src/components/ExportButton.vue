<template>
  <el-button :size="size" @click="open">导出 Excel</el-button>
  <el-dialog v-model="visible" title="导出 Excel" width="780px" append-to-body>
    <div class="tip">
      导出文件结构预览(示例数据)。「表列表」「字段明细」页签内可勾选要导出的列,下方表格实时预览最终样式;首列固定导出。
    </div>

    <!-- Excel 风格 sheet 页签 -->
    <div class="sheet-tabs">
      <div v-for="s in SHEETS" :key="s.key" class="sheet-tab" :class="{ active: activeSheet === s.key }"
           @click="activeSheet = s.key">
        {{ s.name }}
      </div>
    </div>

    <div class="sheet-body">
      <!-- 概览:固定 KV,不可配置 -->
      <table v-if="activeSheet === 'overview'" class="preview-table">
        <tbody>
          <tr v-for="row in OVERVIEW_SAMPLE" :key="row[0]">
            <td class="kv-key">{{ row[0] }}</td>
            <td>{{ row[1] }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 表列表:勾选列 + 实时预览 -->
      <template v-else-if="activeSheet === 'tables'">
        <div class="section-title">表头设置</div>
        <el-checkbox-group v-model="tableChecked" class="col-checks">
          <el-checkbox v-for="c in TABLE_COLS" :key="c.key" :value="c.key" size="small">{{ c.label }}</el-checkbox>
        </el-checkbox-group>
        <table class="preview-table">
          <thead>
            <tr>
              <th class="fixed-col">表名</th>
              <th v-for="c in visibleTableCols" :key="c.key">{{ c.label }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in TABLE_SAMPLE" :key="row.name">
              <td>{{ row.name }}</td>
              <td v-for="c in visibleTableCols" :key="c.key">{{ row[c.key] }}</td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- 字段明细:每张 DONE 的表一个 sheet,结构相同;勾选列 + 实时预览 -->
      <template v-else-if="activeSheet === 'fields'">
        <div class="sheet-note">每张扫描完成的表生成一个 sheet(sheet 名 = 表名),结构相同,此处以 user_order 为例:</div>
        <div class="section-title">表头设置</div>
        <el-checkbox-group v-model="fieldChecked" class="col-checks">
          <el-checkbox v-for="c in FIELD_COLS" :key="c.key" :value="c.key" size="small">{{ c.label }}</el-checkbox>
        </el-checkbox-group>
        <table class="preview-table">
          <thead>
            <tr>
              <th class="fixed-col">字段</th>
              <th v-for="c in visibleFieldCols" :key="c.key">{{ c.label }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in FIELD_SAMPLE" :key="row.name">
              <td>{{ row.name }}</td>
              <td v-for="c in visibleFieldCols" :key="c.key">{{ row[c.key] }}</td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- 异常表:固定,仅有失败表时出现 -->
      <template v-else>
        <div class="sheet-note">仅当存在扫描失败的表时才会生成该 sheet。</div>
        <table class="preview-table">
          <thead>
            <tr><th class="fixed-col">表名</th><th class="fixed-col">错误信息</th></tr>
          </thead>
          <tbody>
            <tr><td>pay_record</td><td>Lock wait timeout exceeded; try restarting transaction</td></tr>
          </tbody>
        </table>
      </template>
    </div>

    <template #footer>
      <el-button @click="reset">重置</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="doExport">导出</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'

// 与后端 ExportService.TABLE_DEFS / COLUMN_DEFS 的 key 一一对应,改动需同步
const TABLE_COLS = [
  { key: 'comment', label: '注释' },
  { key: 'storage', label: '引擎/表空间' },
  { key: 'totalRows', label: '总行数' },
  { key: 'sampled', label: '是否采样' },
  { key: 'sampleRows', label: '采样行数' },
  { key: 'fillRate', label: '整体有值率%' },
  { key: 'status', label: '状态' }
]

const FIELD_COLS = [
  { key: 'comment', label: '注释' },
  { key: 'type', label: '类型' },
  { key: 'key', label: '键' },
  { key: 'nullable', label: '可空' },
  { key: 'default', label: '默认值' },
  { key: 'totalRows', label: '总行数' },
  { key: 'nullCount', label: 'NULL数' },
  { key: 'emptyCount', label: '空串数' },
  { key: 'ruleHitCount', label: '规则命中数' },
  { key: 'valueCount', label: '有值数' },
  { key: 'fillRate', label: '有值率%' }
]

const SHEETS = [
  { key: 'overview', name: '概览' },
  { key: 'tables', name: '表列表' },
  { key: 'fields', name: '字段明细' },
  { key: 'failed', name: '异常表' }
]

const OVERVIEW_SAMPLE = [
  ['数据源', '生产库 (MySQL)'],
  ['库/Schema', 'dqtest'],
  ['状态', 'DONE'],
  ['强制全量', '否'],
  ['空值规则', '默认(NULL + 空字符串)'],
  ['开始时间', '2026-08-04 10:00:00'],
  ['结束时间', '2026-08-04 10:05:30'],
  ['', ''],
  ['统计总结', ''],
  ['统计表数', '120(完成 118,失败 2)'],
  ['空表数(0 行)', '5'],
  ['空表率', '4.17%'],
  ['字段总数', '3,456'],
  ['空字段数(有值数为 0)', '87'],
  ['空字段率', '2.52%'],
  ['总数据行数', '12,500,000(含采样估算)'],
  ['总占用空间', '1.3 GB']
]

// 示例行按列 key 存值,保证预览时表头与表体始终对齐
const TABLE_SAMPLE = [
  { name: 'user_order', comment: '订单表', storage: 'InnoDB · 1.2 GB', totalRows: '12,500,000', sampled: '是(估算)', sampleRows: '1,000,000', fillRate: '87.32', status: 'DONE' },
  { name: 'user_info', comment: '用户表', storage: 'InnoDB · 64 MB', totalRows: '53,210', sampled: '否', sampleRows: '', fillRate: '95.10', status: 'DONE' }
]

const FIELD_SAMPLE = [
  { name: 'id', comment: '主键', type: 'bigint(20)', key: 'PK', nullable: '否', default: '', totalRows: '12,500,000', nullCount: '0', emptyCount: '0', ruleHitCount: '0', valueCount: '12,500,000', fillRate: '100' },
  { name: 'mobile', comment: '手机号', type: 'varchar(20)', key: '', nullable: '是', default: '', totalRows: '12,500,000', nullCount: '3,200', emptyCount: '150', ruleHitCount: '0', valueCount: '12,496,650', fillRate: '99.97' }
]

const props = defineProps({
  jobId: { type: [Number, String], required: true },
  size: { type: String, default: undefined }
})

const visible = ref(false)
// 默认打开「字段明细」页签
const activeSheet = ref('fields')
const tableChecked = ref(TABLE_COLS.map((c) => c.key))
const fieldChecked = ref(FIELD_COLS.map((c) => c.key))

// 只渲染勾选的列(保持定义顺序),表头表体天然对齐
const visibleTableCols = computed(() => TABLE_COLS.filter((c) => tableChecked.value.includes(c.key)))
const visibleFieldCols = computed(() => FIELD_COLS.filter((c) => fieldChecked.value.includes(c.key)))

function reset() {
  tableChecked.value = TABLE_COLS.map((c) => c.key)
  fieldChecked.value = FIELD_COLS.map((c) => c.key)
}

function open() {
  reset()
  activeSheet.value = 'fields'
  visible.value = true
}

function doExport() {
  // 全选时不带参数(默认行为);一列不选时传空串,表示只留固定首列
  const params = new URLSearchParams()
  if (tableChecked.value.length < TABLE_COLS.length) params.set('tableCols', tableChecked.value.join(','))
  if (fieldChecked.value.length < FIELD_COLS.length) params.set('cols', fieldChecked.value.join(','))
  const q = params.toString()
  window.open(`/api/scans/${props.jobId}/export${q ? '?' + q : ''}`, '_blank')
  visible.value = false
}
</script>

<style scoped>
.tip {
  margin-bottom: 12px;
  color: #909399;
  font-size: 13px;
}

.sheet-tabs {
  display: flex;
  border-bottom: 2px solid #409eff;
}

.sheet-tab {
  padding: 4px 16px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
  border: 1px solid #dcdfe6;
  border-bottom: none;
  border-radius: 4px 4px 0 0;
  margin-right: 2px;
  background: #f5f7fa;
}

.sheet-tab.active {
  color: #fff;
  background: #409eff;
  border-color: #409eff;
}

.sheet-body {
  padding: 12px 4px 4px;
  overflow-x: auto;
}

.sheet-note {
  margin-bottom: 8px;
  color: #909399;
  font-size: 12px;
}

.section-title {
  margin-bottom: 8px;
  color: #303133;
  font-size: 13px;
  font-weight: 600;
}

.col-checks {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.preview-table {
  border-collapse: collapse;
  font-size: 12px;
  white-space: nowrap;
}

.preview-table th,
.preview-table td {
  border: 1px solid #dcdfe6;
  padding: 4px 10px;
  text-align: left;
}

.preview-table th {
  background: #f0f2f5;
  color: #303133;
  font-weight: 600;
}

.preview-table th.fixed-col,
.preview-table td.kv-key {
  background: #f0f2f5;
  color: #909399;
}
</style>
