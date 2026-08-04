<template>
  <div class="page-card" v-loading="loading && !job">
    <template v-if="job">
      <div class="toolbar">
        <h3 style="margin: 0">扫描任务 #{{ job.id }}</h3>
        <div>
          <el-button @click="goBack">返回</el-button>
          <el-button v-if="job.status === 'RUNNING'" type="danger" :loading="acting" @click="onCancel">取消</el-button>
          <el-button v-if="['CANCELED', 'INTERRUPTED', 'FAILED'].includes(job.status)" type="primary" :loading="acting" @click="onResume">继续扫描</el-button>
          <el-button @click="onExport">导出 Excel</el-button>
        </div>
      </div>

      <el-descriptions :column="3" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="数据源">{{ job.datasourceName }}</el-descriptions-item>
        <el-descriptions-item label="库">{{ job.dbName ? job.dbName + '.' + job.schemaName : job.schemaName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(job.status)" size="small">{{ statusText(job.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="数据库类型">{{ job.dbType }}</el-descriptions-item>
        <el-descriptions-item label="强制全量">{{ job.forceFull ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(job.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="空值规则" :span="3">
          <template v-if="job.nullRules && job.nullRules.length">
            <el-tag v-for="(r, i) in job.nullRules" :key="i" size="small" style="margin-right: 6px">
              {{ r.column }}: {{ (r.values || []).join(', ') }}
            </el-tag>
          </template>
          <span v-else>无</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="job.error" label="错误信息" :span="3">
          <span style="color: #f56c6c">{{ job.error }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div style="margin-bottom: 16px">
        <span style="font-size: 13px; color: #606266">总进度({{ job.doneTables }}/{{ job.totalTables }} 表)</span>
        <el-progress :percentage="Math.round(job.progressPercent || 0)" :status="job.status === 'FAILED' ? 'exception' : undefined" />
      </div>

      <el-table :data="job.tables || []" border>
        <el-table-column prop="tableName" label="表名" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link v-if="row.status === 'DONE'" type="primary" @click="goColumns(row)">{{ row.tableName }}</el-link>
            <span v-else>{{ row.tableName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="200">
          <template #default="{ row }">
            <el-progress v-if="row.totalChunks > 0" :percentage="chunkPercent(row)" :stroke-width="10" />
            <span v-else-if="row.status === 'RUNNING'" style="color: #909399; font-size: 12px">运行中</span>
            <span v-else style="color: #909399; font-size: 12px">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采样" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.sampled" type="warning" size="small">采样</el-tag>
            <span v-else style="color: #909399; font-size: 12px">全量</span>
          </template>
        </el-table-column>
        <el-table-column label="已扫行数 / 估算行数" width="200">
          <template #default="{ row }">
            {{ formatNumber(row.scannedRows) }} / {{ formatNumber(row.totalRows ?? row.estRows) }}
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="120">
          <template #default="{ row }">{{ formatDuration(row.startedAt, row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="140">
          <template #default="{ row }">
            <el-tooltip v-if="row.error" :content="row.error" placement="top" :show-after="200">
              <span class="error-text">{{ row.error }}</span>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup>
import { onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'
import { formatDateTime, formatDuration, formatNumber, statusTagType, statusText } from '../utils/format'

const route = useRoute()
const router = useRouter()
const jobId = route.params.jobId

const job = ref(null)
const loading = ref(false)
const acting = ref(false)

const TERMINAL = ['DONE', 'FAILED', 'CANCELED', 'INTERRUPTED']
let timer = null

async function fetchJob() {
  loading.value = true
  try {
    job.value = await request.get(`/scans/${jobId}`)
    if (TERMINAL.includes(job.value.status)) stopPolling()
  } finally {
    loading.value = false
  }
}

function startPolling() {
  stopPolling()
  timer = setInterval(fetchJob, 2000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function chunkPercent(row) {
  if (!row.totalChunks) return 0
  return Math.min(100, Math.round((row.doneChunks / row.totalChunks) * 100))
}

async function onCancel() {
  await ElMessageBox.confirm('确定取消该扫描任务吗?', '取消确认', { type: 'warning' })
  acting.value = true
  try {
    await request.post(`/scans/${jobId}/cancel`)
    ElMessage.success('已取消')
    await fetchJob()
  } finally {
    acting.value = false
  }
}

async function onResume() {
  acting.value = true
  try {
    await request.post(`/scans/${jobId}/resume`)
    ElMessage.success('已继续扫描')
    await fetchJob()
    if (!TERMINAL.includes(job.value.status)) startPolling()
  } finally {
    acting.value = false
  }
}

function onExport() {
  window.open(`/api/scans/${jobId}/export`, '_blank')
}

function goColumns(row) {
  router.push(`/scans/${jobId}/tables/${encodeURIComponent(row.tableName)}`)
}

// 返回该任务所属数据源/库的扫描记录列表
function goBack() {
  const q = job.value.dbName ? `?db=${encodeURIComponent(job.value.dbName)}` : ''
  router.push(`/datasources/${job.value.datasourceId}/schemas/${encodeURIComponent(job.value.schemaName)}/scans${q}`)
}

onMounted(async () => {
  await fetchJob()
  if (job.value && !TERMINAL.includes(job.value.status)) startPolling()
})

// 页签内下钻到字段页时是失活而非卸载:停掉轮询,回来时刷新并按需恢复
onDeactivated(stopPolling)

onActivated(async () => {
  if (!job.value) return // 首次挂载由 onMounted 处理
  await fetchJob()
  if (job.value && !TERMINAL.includes(job.value.status)) startPolling()
})

onUnmounted(stopPolling)
</script>

<style scoped>
.error-text {
  color: #f56c6c;
  font-size: 12px;
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: default;
}
</style>
