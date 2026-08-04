<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">字段统计 - {{ tableName }}</h3>
      <div>
        <el-button @click="$router.push(`/scans/${jobId}`)">返回</el-button>
        <el-button @click="onExport">导出 Excel</el-button>
      </div>
    </div>

    <el-alert v-if="tableInfo" type="info" :closable="false" style="margin-bottom: 16px">
      <span style="margin-right: 24px">总行数: {{ formatNumber(tableInfo.totalRows ?? tableInfo.scannedRows) }}</span>
      <span style="margin-right: 24px">
        统计方式: {{ tableInfo.sampled ? '采样' : '全量' }}
        <el-tag v-if="tableInfo.sampled" type="warning" size="small" style="margin-left: 6px">估算值</el-tag>
      </span>
      <span v-if="tableInfo.sampled" style="margin-right: 24px">采样行数: {{ formatNumber(tableInfo.sampleRows) }}</span>
      <span>耗时: {{ formatDuration(tableInfo.startedAt, tableInfo.finishedAt) }}</span>
    </el-alert>

    <el-table :data="columns" v-loading="loading" border>
      <el-table-column prop="columnName" label="字段名" min-width="140" show-overflow-tooltip />
      <el-table-column prop="columnComment" label="注释" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.columnComment">{{ row.columnComment }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="columnType" label="类型" width="150" show-overflow-tooltip />
      <el-table-column label="键" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.keyLabel" size="small" :type="row.keyLabel === 'PK' ? 'primary' : 'success'">{{ row.keyLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可空" width="70">
        <template #default="{ row }">
          <span v-if="row.nullable === null || row.nullable === undefined">-</span>
          <span v-else>{{ row.nullable ? '是' : '否' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="defaultValue" label="默认值" width="110" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.defaultValue !== null && row.defaultValue !== undefined && row.defaultValue !== ''">{{ row.defaultValue }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="空值数(合计)" width="130" sortable :sort-method="sortByNullTotal">
        <template #default="{ row }">
          <span :style="{ color: nullTotal(row) > 0 ? '#e6a23c' : 'inherit' }">{{ formatNumber(nullTotal(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="totalRows" label="总行数" width="110">
        <template #default="{ row }">{{ formatNumber(row.totalRows) }}</template>
      </el-table-column>
      <el-table-column prop="nullCount" label="NULL 数" width="110">
        <template #default="{ row }">{{ formatNumber(row.nullCount) }}</template>
      </el-table-column>
      <el-table-column prop="emptyCount" label="空串数" width="110">
        <template #default="{ row }">{{ formatNumber(row.emptyCount) }}</template>
      </el-table-column>
      <el-table-column prop="valueCount" label="有值数" width="110">
        <template #default="{ row }">{{ formatNumber(row.valueCount) }}</template>
      </el-table-column>
      <el-table-column label="有值率" width="180">
        <template #default="{ row }">
          <el-progress
            :percentage="row.fillRate || 0"
            :stroke-width="10"
            :color="row.fillRate >= 95 ? '#67c23a' : row.fillRate >= 80 ? '#e6a23c' : '#f56c6c'"
          />
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '../api'
import { formatDuration, formatNumber } from '../utils/format'

const route = useRoute()
const jobId = route.params.jobId
const tableName = route.params.tableName

const columns = ref([])
const tableInfo = ref(null)
const loading = ref(false)

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

function onExport() {
  window.open(`/api/scans/${jobId}/export`, '_blank')
}

onMounted(load)
</script>
