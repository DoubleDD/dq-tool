<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">比对任务</h3>
      <!-- 按钮组统一右侧、gap 统一间距(与抽样导出页同写法) -->
      <div class="toolbar-actions">
        <el-checkbox v-model="showArchived" @change="load">显示已归档</el-checkbox>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" @click="router.push('/compare/new')">+ 新建比对任务</el-button>
      </div>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      <template #title>
        比对为长时任务:创建后按「连接数据源 → 读取基准表 → 逐字段比对 → 生成报告」执行,运行中可留在本页观察进度
      </template>
    </el-alert>
    <el-table :data="tasks" v-loading="loading" border>
      <el-table-column label="任务" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <div>{{ row.name }}</div>
          <div style="color: var(--el-text-color-secondary); font-size: 12px">T-{{ row.id }}</div>
        </template>
      </el-table-column>
      <el-table-column label="基准表" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div>{{ row.baseDatasourceName || `数据源 ${row.baseDatasourceId}` }}</div>
          <div style="color: var(--el-text-color-secondary); font-size: 12px">{{ baseTableLabel(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="比对字段" width="90" align="center">
        <template #default="{ row }">{{ (row.fields || []).length }} 个</template>
      </el-table-column>
      <!-- 列表视图不含目标数,逐任务详情接口补一次(缓存,不随轮询重拉) -->
      <el-table-column label="比对系统" width="90" align="center">
        <template #default="{ row }">
          <span v-if="targetCounts[row.id] != null">{{ targetCounts[row.id] }} 个系统</span>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="row.status === 'FAILED' && row.error" :content="row.error" placement="top" :show-after="200">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </el-tooltip>
          <el-tag v-else :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          <el-tag v-if="row.archived" size="small" type="info" plain style="margin-left: 4px">已归档</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" min-width="170">
        <template #default="{ row }">
          <!-- 进度条与阶段文案纵向排列,与抽样导出页同一写法 -->
          <span v-if="row.status === 'RUNNING'" style="display: inline-flex; flex-direction: column; gap: 2px; vertical-align: middle">
            <el-progress :percentage="row.progressPercent || 0" :stroke-width="10" style="width: 130px" />
            <span style="color: var(--el-text-color-secondary); font-size: 12px">{{ row.stage || '运行中' }}</span>
          </span>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
        </template>
      </el-table-column>
      <el-table-column label="发起时间" width="165" class-name="nowrap-cell">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="110" class-name="nowrap-cell">
        <template #default="{ row }">
          <span v-if="row.status === 'RUNNING'">—</span>
          <span v-else>{{ formatDuration(row.startedAt, row.finishedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right" class-name="nowrap-cell">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DONE'" link type="primary" @click="router.push(`/compare/${row.id}/diff`)">查看详情</el-button>
          <el-button v-if="row.status === 'DONE' || row.status === 'FAILED'" link type="primary" @click="confirmRerun(row)">重新比对</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有比对任务,点击「+ 新建比对任务」发起" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>

<script setup>
import { onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import {
  listCompareJobs, getCompareJob, rerunCompareJob, deleteCompareJob
} from '../api'
import { formatDateTime, formatDuration, statusTagType, statusText } from '../utils/format'

const router = useRouter()

const tasks = ref([])
const loading = ref(false)
const showArchived = ref(false)
// 各任务的目标系统数(列表视图不返回,从详情接口补;任务 id → 目标数)
const targetCounts = ref({})
let timer = null

/** 基准表定位串:多库方言带库名 db.schema.table,否则 schema.table */
function baseTableLabel(row) {
  const schemaPart = row.baseDb ? `${row.baseDb}.${row.baseSchema || ''}` : (row.baseSchema || '')
  return schemaPart ? `${schemaPart}.${row.baseTable}` : row.baseTable
}

function needPolling() {
  return tasks.value.some((t) => t.status === 'RUNNING')
}

async function load() {
  loading.value = true
  try {
    tasks.value = await listCompareJobs(showArchived.value) || []
    fillTargetCounts()
  } finally {
    loading.value = false
    if (needPolling() && !timer) startPolling()
  }
}

/** 逐个任务补目标系统数:仅对未缓存的任务发详情请求(静默,失败保持「—」) */
function fillTargetCounts() {
  for (const t of tasks.value) {
    if (targetCounts.value[t.id] != null) continue
    getCompareJob(t.id, true)
      .then((d) => { targetCounts.value = { ...targetCounts.value, [t.id]: (d?.targets || []).length } })
      .catch(() => {})
  }
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    tasks.value = await listCompareJobs(showArchived.value).catch(() => tasks.value)
    if (!needPolling()) stopPolling()
  }, 1000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 重新比对:确认后按原目标清单重跑(RUNNING 时后端 409,拦截器统一提示) */
async function confirmRerun(row) {
  try {
    await ElMessageBox.confirm(
      `将按任务「${row.name}」原目标清单重新比对,现有差异明细与报告会被覆盖。`,
      '重新比对',
      { type: 'warning', confirmButtonText: '重新比对', cancelButtonText: '取消' }
    )
  } catch { /* 用户取消 */ return }
  await rerunCompareJob(row.id)
  ElMessage.success(`任务 T-${row.id} 已开始重新比对`)
  await load()
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `将删除任务「${row.name}」及其差异明细与报告,删除后不可恢复。`,
      '删除比对任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { /* 用户取消 */ return }
  await deleteCompareJob(row.id)
  ElMessage.success(`任务 T-${row.id} 已删除`)
  await load()
}

onMounted(load)

// 固定页签走 keep-alive:失活时停轮询,回来时刷新并按需恢复
onActivated(load)
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
/* 工具栏按钮组:flex + gap 统一间距,并清掉 el-button 相邻默认 margin(与抽样导出页同写法) */
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}

/* 发起时间/耗时/操作列不换行 */
:deep(.nowrap-cell) {
  white-space: nowrap;
}
</style>
