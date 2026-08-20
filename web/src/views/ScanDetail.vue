<template>
  <div class="page-card" v-loading="loading && !job">
    <template v-if="job">
      <div class="toolbar">
        <Breadcrumb :items="breadcrumbItems" />
        <div>
          <el-button v-if="job.status === 'RUNNING'" type="danger" :loading="acting" @click="onCancel">取消</el-button>
          <el-button v-if="['CANCELED', 'INTERRUPTED', 'FAILED'].includes(job.status)" type="primary" :loading="acting" @click="onResume">继续扫描</el-button>
          <ExportButton :job-id="jobId" />
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
        <el-descriptions-item label="并发线程数">{{ job.workers ? job.workers + ' 线程' : '默认' }}</el-descriptions-item>
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
          <span style="color: var(--el-color-danger)">{{ job.error }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div style="margin-bottom: 16px">
        <span style="font-size: 13px; color: var(--el-text-color-regular)">总进度({{ job.doneTables }}/{{ job.totalTables }} 表)</span>
        <el-progress :percentage="Math.round(job.progressPercent || 0)" :status="job.status === 'FAILED' ? 'exception' : undefined" />
      </div>

      <el-table :data="job.tables || []" border>
        <el-table-column type="index" label="序号" width="60" />
        <el-table-column prop="tableName" label="表名" min-width="180" sortable show-overflow-tooltip>
          <template #default="{ row }">
            <el-link v-if="row.status === 'DONE'" type="primary" @click="goColumns(row)">{{ row.tableName }}</el-link>
            <span v-else>{{ row.tableName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="200" sortable :sort-method="(a, b) => chunkPercent(a) - chunkPercent(b)">
          <template #default="{ row }">
            <el-progress v-if="row.totalChunks > 0" :percentage="chunkPercent(row)" :stroke-width="10" />
            <span v-else-if="row.status === 'RUNNING'" style="color: var(--el-text-color-secondary); font-size: 12px">运行中</span>
            <span v-else style="color: var(--el-text-color-secondary); font-size: 12px">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" sortable :sort-method="(a, b) => statusText(a.status).localeCompare(statusText(b.status), 'zh-CN')">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采样" width="90" sortable :sort-method="(a, b) => Number(a.sampled) - Number(b.sampled)">
          <template #header>
            <el-tooltip placement="top" :show-after="200">
              <template #content>
                <div>超过阈值(默认 100 万行或 10GB)的表只统计样本(默认 10 万行),结果为估算值</div>
                <div>MySQL / 达梦 / OceanBase 为 LIMIT 顺序采样,结果可能有偏</div>
                <div>扫描时勾选"强制全量"可逐行精确统计</div>
              </template>
              <span>采样 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-tag v-if="row.sampled" type="warning" size="small">采样</el-tag>
            <span v-else style="color: var(--el-text-color-secondary); font-size: 12px">全量</span>
          </template>
        </el-table-column>
        <el-table-column label="已扫行数 / 估算行数" width="200" sortable :sort-method="(a, b) => (a.scannedRows ?? -1) - (b.scannedRows ?? -1)">
          <template #header>
            <el-tooltip placement="top" :show-after="200">
              <template #content>
                <div>已扫行数:本次实际扫描的行数(采样表即样本行数)</div>
                <div>估算行数:来自数据库元数据,不是 COUNT(*) 精确值,可能不准确</div>
              </template>
              <span>已扫行数 / 估算行数 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            {{ formatNumber(row.scannedRows) }} / {{ formatNumber(row.totalRows ?? row.estRows) }}
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="120" sortable :sort-method="(a, b) => durationMs(a) - durationMs(b)">
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
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import request from '../api'
import ExportButton from '../components/ExportButton.vue'
import Breadcrumb from '../components/Breadcrumb.vue'
import { formatDateTime, formatDuration, formatNumber, statusTagType, statusText } from '../utils/format'
import { setScanSchema, setScanDs, syncTab } from '../stores/tabs'
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
    // 回写库名标签和数据源 id,供页签标题和关闭页签时跳回父级使用
    const schema = job.value.dbName ? `${job.value.dbName}.${job.value.schemaName}` : job.value.schemaName
    setScanSchema(jobId, schema)
    setScanDs(jobId, job.value.datasourceId)
    if (schema && !route.query.schema) {
      syncTab(route)
    }
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

/** 耗时毫秒数,供耗时列排序;未开始的排最后 */
function durationMs(row) {
  if (!row.startedAt) return -1
  const start = new Date(row.startedAt).getTime()
  const end = row.finishedAt ? new Date(row.finishedAt).getTime() : Date.now()
  return Math.max(0, end - start)
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

/** 统一进入字段明细页(带 jobId 展示该任务扫描统计):/datasources/:id/schemas/:schema/tables/:tableName?jobId= */
function goColumns(row) {
  const { datasourceId, schemaName, dbName } = job.value || {}
  if (!datasourceId || !schemaName) return
  const q = []
  if (dbName) q.push(`db=${encodeURIComponent(dbName)}`)
  q.push(`jobId=${jobId}`)
  router.push(`/datasources/${datasourceId}/schemas/${encodeURIComponent(schemaName)}/tables/${encodeURIComponent(row.tableName)}?${q.join('&')}`)
}

// ---------- 面包屑 ----------
const breadcrumbItems = computed(() => {
  if (!job.value) return [{ label: `扫描 #${jobId}` }]
  const schemaLabel = job.value.dbName
    ? `${job.value.dbName}.${job.value.schemaName}`
    : job.value.schemaName
  const scansPath = `/datasources/${job.value.datasourceId}/schemas/${encodeURIComponent(job.value.schemaName)}/scans${job.value.dbName ? `?db=${encodeURIComponent(job.value.dbName)}` : ''}`
  return [
    { label: job.value.datasourceName, to: `/datasources/${job.value.datasourceId}/schemas` },
    { label: schemaLabel, to: scansPath },
    { label: `扫描 #${jobId}` }
  ]
})
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
  color: var(--el-color-danger);
  font-size: 12px;
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: default;
}
</style>
