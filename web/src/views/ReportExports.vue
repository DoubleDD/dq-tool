<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">导出任务</h3>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <div style="display: flex; gap: 12px; margin-bottom: 12px; align-items: center">
      <el-input v-model="keyword" placeholder="按文件名、数据源搜索" clearable style="width: 280px" />
      <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 120px">
        <el-option label="排队中" value="PENDING" />
        <el-option label="生成中" value="RUNNING" />
        <el-option label="完成" value="DONE" />
        <el-option label="失败" value="FAILED" />
      </el-select>
    </div>
    <el-table :data="filteredTasks" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="数据源" min-width="120">
        <template #default="{ row }">{{ row.datasourceName || `数据源 ${row.datasourceId}` }}</template>
      </el-table-column>
      <el-table-column label="库" min-width="180">
        <template #default="{ row }">
          <span v-if="row.schemaNames && row.schemaNames.length" :title="row.schemaNames.join('、')">
            {{ row.schemaNames.length <= 3 ? row.schemaNames.join('、') : row.schemaNames.slice(0, 3).join('、') + ` 等 ${row.schemaNames.length} 个` }}
          </span>
          <span v-else style="color: var(--el-text-color-secondary)">全部库</span>
        </template>
      </el-table-column>
      <el-table-column label="状态 / 进度" min-width="260">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small" style="margin-right: 8px">{{ statusText(row.status) }}</el-tag>
          <template v-if="row.status === 'RUNNING' || row.status === 'PENDING'">
            <el-progress :percentage="percent(row)" :stroke-width="10" style="width: 130px; display: inline-flex; vertical-align: middle" />
            <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">{{ row.stage || '排队中' }}</span>
          </template>
          <span v-else-if="row.status === 'FAILED'" style="color: var(--el-color-danger); font-size: 12px" :title="row.error">{{ row.error }}</span>
        </template>
      </el-table-column>
      <el-table-column label="文件名" min-width="220">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DONE' && row.fileName" link type="primary" :title="row.fileName" @click="openDoc(row)">{{ row.fileName }}</el-button>
          <span v-else style="color: var(--el-text-color-secondary)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="文件大小" width="100" align="right">
        <template #default="{ row }">{{ row.fileSize != null ? formatBytes(row.fileSize) : '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">{{ duration(row) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'DONE'">
            <el-button v-if="isTauri" link type="primary" :loading="row._saving" @click="saveAs(row)">另存为</el-button>
            <el-button v-else link type="primary" @click="download(row)">下载</el-button>
            <el-button link type="primary" @click="reveal(row)">打开目录</el-button>
          </template>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有导出任务,在库列表/表列表页点击「导出报告」提交" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'
import { formatBytes, formatDateTime } from '../utils/format'

const tasks = ref([])
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(false)
let timer = null

function statusType(s) {
  return { PENDING: 'info', RUNNING: 'primary', DONE: 'success', FAILED: 'danger' }[s] || 'info'
}

function statusText(s) {
  return { PENDING: '排队中', RUNNING: '生成中', DONE: '完成', FAILED: '失败' }[s] || s
}

function percent(row) {
  if (!row.progressTotal) return 0
  return Math.min(100, Math.round((row.progressDone / row.progressTotal) * 100))
}

/** 耗时:完成/失败取 finished-created,进行中取当前-created;不足 1 分钟显示秒 */
function duration(row) {
  if (!row.createdAt) return '—'
  const end = row.finishedAt ? new Date(row.finishedAt) : new Date()
  const secs = Math.max(0, Math.round((end - new Date(row.createdAt)) / 1000))
  if (secs < 60) return `${secs} 秒`
  return `${Math.floor(secs / 60)} 分 ${secs % 60} 秒`
}

function hasActive() {
  return tasks.value.some((t) => t.status === 'PENDING' || t.status === 'RUNNING')
}

const filteredTasks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const st = statusFilter.value
  return tasks.value.filter((t) => {
    if (st && t.status !== st) return false
    if (kw) {
      const name = (t.fileName || '').toLowerCase()
      const ds = (t.datasourceName || '').toLowerCase()
      if (!name.includes(kw) && !ds.includes(kw)) return false
    }
    return true
  })
})


async function load() {
  loading.value = true
  try {
    tasks.value = await request.get('/report-exports')
  } finally {
    loading.value = false
    if (hasActive() && !timer) startPolling()
  }
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    tasks.value = await request.get('/report-exports').catch(() => tasks.value)
    if (!hasActive()) stopPolling()
  }, 2000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 另存为:直接走浏览器下载(GET 流式响应) */
function download(row) {
  const a = document.createElement('a')
  a.href = `/api/report-exports/${row.id}/download`
  a.click()
}

// tauri 套壳环境(webview 注入 __TAURI_INTERNALS__):「另存为」走原生保存对话框,由 Rust 侧复制产物文件
const isTauri = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__

async function saveAs(row) {
  row._saving = true
  try {
    const name = row.fileName || `${row.datasourceName || '数据调研报告'}-数据调研报告.docx`
    // sourceName 传磁盘上的真实文件名(后端返回的 basename),Rust 侧按它定位产物文件
    const saved = await window.__TAURI_INTERNALS__.invoke('save_report_as', {
      name,
      sourceName: row.fileName || '',
    })
    if (saved) ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(String(e))
  } finally {
    row._saving = false
  }
}

/** 打开:后端调系统软件(MS Office → WPS → 默认关联)打开文档 */
async function openDoc(row) {
  await request.post(`/report-exports/${row.id}/open`)
  ElMessage.success('已用系统默认应用打开')
}

/** 打开文件所在目录并选中文件 */
async function reveal(row) {
  await request.post(`/report-exports/${row.id}/reveal`)
  ElMessage.success('已打开文件目录')
}

onMounted(load)

// 固定页签走 keep-alive:失活时停轮询,回来时刷新并按需恢复
onActivated(load)
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>
