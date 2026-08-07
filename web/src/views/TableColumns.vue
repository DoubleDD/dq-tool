<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">字段统计 - {{ tableName }}</h3>
      <div>
        <el-button @click="goBack">返回</el-button>
        <ExportButton :job-id="jobId" />
      </div>
    </div>

    <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
      <el-input v-model="keyword" placeholder="按字段名或注释搜索" clearable style="width: 280px" />
      <el-checkbox v-model="onlyEmpty">只看空字段(有值数为 0)</el-checkbox>
    </div>

    <el-alert v-if="tableInfo || columns.length" type="success" :closable="false" style="margin-bottom: 16px">
      <template v-if="tableInfo">
        <span style="margin-right: 24px">总行数: {{ formatNumber(tableInfo.totalRows ?? tableInfo.scannedRows) }}</span>
        <span style="margin-right: 24px">
          统计方式: {{ tableInfo.sampled ? '采样' : '全量' }}
          <el-tooltip v-if="tableInfo.sampled" placement="top" :show-after="200">
            <template #content>
              <div>该表只统计了样本(默认 10 万行),空值数、有值率等由样本按比例推算,为估算值</div>
              <div>MySQL / 达梦 / OceanBase 为 LIMIT 顺序采样,结果可能有偏</div>
            </template>
            <el-tag type="warning" size="small" style="margin-left: 6px">估算值</el-tag>
          </el-tooltip>
        </span>
        <span v-if="tableInfo.sampled" style="margin-right: 24px">采样行数: {{ formatNumber(tableInfo.sampleRows) }}</span>
        <span style="margin-right: 24px">耗时: {{ formatDuration(tableInfo.startedAt, tableInfo.finishedAt) }}</span>
      </template>
      <span style="margin-right: 24px">字段数量: {{ columns.length }}</span>
      <span>
        空字段数量:
        <el-link type="primary" :disabled="!emptyColumns.length" @click="onlyEmpty = true">{{ emptyColumns.length }}</el-link>
      </span>
    </el-alert>

    <el-table :data="filteredColumns" v-loading="loading" border>
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="columnName" label="字段名" min-width="140" sortable show-overflow-tooltip />
      <el-table-column prop="columnComment" label="注释" min-width="140" sortable show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.columnComment">{{ row.columnComment }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="columnType" label="类型" width="150" sortable show-overflow-tooltip />
      <el-table-column label="键" width="70" sortable :sort-method="(a, b) => (a.keyLabel || '').localeCompare(b.keyLabel || '')">
        <template #default="{ row }">
          <el-tag v-if="row.keyLabel" size="small" :type="row.keyLabel === 'PK' ? 'primary' : 'success'">{{ row.keyLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可空" width="70" sortable :sort-method="(a, b) => Number(a.nullable ?? true) - Number(b.nullable ?? true)">
        <template #default="{ row }">
          <span v-if="row.nullable === null || row.nullable === undefined">-</span>
          <span v-else>{{ row.nullable ? '是' : '否' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="defaultValue" label="默认值" width="110" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.defaultValue !== null && row.defaultValue !== undefined && row.defaultValue !== ''">{{ row.defaultValue }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="空值数(合计)" width="130" sortable :sort-method="sortByNullTotal">
        <template #default="{ row }">
          <span :style="{ color: nullTotal(row) > 0 ? 'var(--el-color-warning)' : 'inherit' }">{{ formatNumber(nullTotal(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="totalRows" label="总行数" width="110" sortable>
        <template #default="{ row }">{{ formatNumber(row.totalRows) }}</template>
      </el-table-column>
      <el-table-column prop="nullCount" label="NULL 数" width="110" sortable>
        <template #default="{ row }">{{ formatNumber(row.nullCount) }}</template>
      </el-table-column>
      <el-table-column prop="emptyCount" label="空串数" width="110" sortable>
        <template #default="{ row }">{{ formatNumber(row.emptyCount) }}</template>
      </el-table-column>
      <el-table-column prop="valueCount" label="有值数" width="110" sortable>
        <template #default="{ row }">{{ formatNumber(row.valueCount) }}</template>
      </el-table-column>
      <el-table-column label="有值率" width="180" sortable :sort-method="(a, b) => (a.fillRate || 0) - (b.fillRate || 0)">
        <template #default="{ row }">
          <el-progress
            :percentage="row.fillRate || 0"
            :stroke-width="10"
            :color="row.fillRate >= 95 ? 'var(--el-color-success)' : row.fillRate >= 80 ? 'var(--el-color-warning)' : 'var(--el-color-danger)'"
          />
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '../api'
import { QuestionFilled } from '@element-plus/icons-vue'
import ExportButton from '../components/ExportButton.vue'
import { formatDuration, formatNumber } from '../utils/format'
import { goBack as historyBack } from '../utils/back'

const route = useRoute()
const router = useRouter()
const jobId = route.params.jobId
const tableName = route.params.tableName

const columns = ref([])
const tableInfo = ref(null)
const loading = ref(false)
const keyword = ref('')
const onlyEmpty = ref(false)

// 空字段:有值数为 0 的字段
const emptyColumns = computed(() => columns.value.filter((c) => (c.valueCount || 0) === 0))

const filteredColumns = computed(() => {
  let list = onlyEmpty.value ? emptyColumns.value : columns.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((c) =>
      c.columnName.toLowerCase().includes(kw) || (c.columnComment || '').toLowerCase().includes(kw))
  }
  return list
})

function nullTotal(row) {
  return (row.nullCount || 0) + (row.emptyCount || 0) + (row.ruleHitCount || 0)
}

function sortByNullTotal(a, b) {
  return nullTotal(a) - nullTotal(b)
}

async function load() {
  loading.value = true
  try {
    const [cols, job] = await Promise.all([
      request.get(`/scans/${jobId}/tables/${encodeURIComponent(tableName)}/columns`),
      request.get(`/scans/${jobId}`).catch(() => null)
    ])
    columns.value = cols || []
    if (job && job.tables) {
      tableInfo.value = job.tables.find((t) => t.tableName === tableName) || null
    }
  } finally {
    loading.value = false
  }
}

// 原路返回;无历史记录(直接打开)时兜底回任务详情
function goBack() {
  historyBack(router, `/scans/${jobId}`)
}

onMounted(load)
</script>
