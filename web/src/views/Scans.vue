<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">扫描记录{{ schema ? ` - ${db ? db + '.' + schema : schema}` : '' }}</h3>
      <div>
        <el-button @click="goBack">返回</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>
    <el-table :data="jobs" v-loading="loading" border style="width: 100%">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="id" label="任务ID" width="90" />
      <el-table-column v-if="!schema" prop="datasourceName" label="数据源" min-width="140" />
      <el-table-column v-if="!schema" label="库/Schema" min-width="140">
        <template #default="{ row }">{{ row.dbName ? row.dbName + '.' + row.schemaName : row.schemaName }}</template>
      </el-table-column>
      <el-table-column label="进度" width="180">
        <template #default="{ row }">
          <el-progress :percentage="Math.round(row.progressPercent)" :status="row.status === 'FAILED' ? 'exception' : undefined" />
        </template>
      </el-table-column>
      <el-table-column label="表(完成/总数)" width="120">
        <template #default="{ row }">{{ row.doneTables }}/{{ row.totalTables }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <JobTimeline :events="row.events">
            <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </JobTimeline>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="开始时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="完成时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.finishedAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="110">
        <template #default="{ row }">{{ formatDuration(row.startedAt, row.finishedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="goDetail(row)">查看</el-button>
          <el-button v-if="['CANCELED','INTERRUPTED','FAILED'].includes(row.status)"
                     size="small" type="warning" @click="resume(row)">继续扫描</el-button>
          <ExportButton :job-id="row.id" size="small" />
          <el-button v-if="!['PENDING','RUNNING'].includes(row.status)"
                     size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onActivated, onDeactivated, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import ExportButton from '../components/ExportButton.vue'
import JobTimeline from '../components/JobTimeline.vue'
import { formatDateTime, formatDuration, statusTagType, statusText } from '../utils/format'
import { goBack as historyBack } from '../utils/back'

const route = useRoute()
const router = useRouter()
// 从库列表下钻进来时带上数据源/库过滤条件
const dsId = route.params.id
const schema = route.params.schema || ''
const db = route.query.db || ''

const jobs = ref([])
const loading = ref(false)
let timer = null

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams()
    if (dsId) params.set('datasourceId', dsId)
    if (db) params.set('dbName', db)
    if (schema) params.set('schemaName', schema)
    const q = params.toString()
    jobs.value = await api.get('/scans' + (q ? `?${q}` : ''))
  } finally {
    loading.value = false
  }
}

async function resume(row) {
  await api.post(`/scans/${row.id}/resume`)
  ElMessage.success('已继续扫描')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除任务 #${row.id} 的扫描记录吗?`, '删除确认', { type: 'warning' })
  await api.delete(`/scans/${row.id}`)
  ElMessage.success('删除成功')
  load()
}

// 带上库名标签,供页签标题展示
function goDetail(row) {
  const schema = row.dbName ? `${row.dbName}.${row.schemaName}` : row.schemaName
  router.push(`/scans/${row.id}?schema=${encodeURIComponent(schema)}`)
}

// 原路返回;无历史记录(直接打开)时兜底回库列表
function goBack() {
  historyBack(router, `/datasources/${dsId}/schemas`)
}

onActivated(() => {
  load()
  // 有进行中的任务时每 2 秒自动刷新
  timer = setInterval(() => {
    if (jobs.value.some(j => ['PENDING', 'RUNNING'].includes(j.status))) {
      load()
    }
  }, 2000)
})

function clearTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

// 页签切换走 keep-alive 的激活/失活,失活时停掉轮询
onDeactivated(clearTimer)
onUnmounted(clearTimer)
</script>
