<template>
  <div>
    <div class="page-card">
      <div class="toolbar">
        <h3 style="margin: 0">进行中的任务</h3>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <el-empty v-if="!loading && activeJobs.length === 0" class="empty-compact" :image-size="60" description="当前没有正在进行的扫描任务" />
      <div v-else class="job-cards" v-loading="loading">
        <div v-for="row in activeJobs" :key="row.id" class="job-card" @click="goDetail(row)">
          <el-progress type="circle" :width="64" :percentage="Math.round(row.progressPercent)"
                       :status="row.status === 'PENDING' ? 'info' : undefined" />
          <div class="job-card-body">
            <div class="job-card-title">
              <span class="job-card-schema">{{ row.dbName ? row.dbName + '.' + row.schemaName : row.schemaName }}</span>
              <span class="job-card-action">
                <el-tag size="small" class="tag-status" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
                <el-button size="small" type="danger" class="btn-cancel" @click.stop="cancel(row)">取消</el-button>
              </span>
            </div>
            <div class="job-card-meta">{{ row.datasourceName }} · 任务 #{{ row.id }}</div>
            <div class="job-card-meta">表 {{ row.doneTables }}/{{ row.totalTables }} · 已耗时 {{ elapsed(row.startedAt) }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="page-card">
      <div class="toolbar">
        <h3 style="margin: 0">近期历史</h3>
      </div>
      <el-table :data="pagedHistoryJobs" v-loading="loading" border style="width: 100%">
        <el-table-column type="index" label="序号" width="60" :index="indexMethod" />
        <el-table-column prop="datasourceName" label="数据源" min-width="140" />
        <el-table-column label="库/Schema" min-width="140">
          <template #default="{ row }">{{ row.dbName ? row.dbName + '.' + row.schemaName : row.schemaName }}</template>
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
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[15, 30, 50, 100]"
          :total="historyJobs.length"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import ExportButton from '../components/ExportButton.vue'
import JobTimeline from '../components/JobTimeline.vue'
import { formatDateTime, formatDuration, statusTagType, statusText } from '../utils/format'

const router = useRouter()

const jobs = ref([])
const loading = ref(false)
const now = ref(Date.now())
let timer = null
let ticker = null

// 进行中:等待中/运行中;接口按 id 倒序,历史分页展示
const activeJobs = computed(() => jobs.value.filter(j => ['PENDING', 'RUNNING'].includes(j.status)))
const historyJobs = computed(() => jobs.value.filter(j => !['PENDING', 'RUNNING'].includes(j.status)))
const currentPage = ref(1)
const pageSize = ref(15)
const pagedHistoryJobs = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return historyJobs.value.slice(start, start + pageSize.value)
})
// 分页序号:第2页从21开始
function indexMethod(index) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}
// 删除等操作导致数据减少时,修正越界的页码
watch(() => historyJobs.value.length, (total) => {
  const maxPage = Math.max(1, Math.ceil(total / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
})

// 读取 now 建立响应式依赖,卡片上的耗时随秒针刷新
function elapsed(startedAt) {
  void now.value
  return formatDuration(startedAt, null)
}

async function load() {
  loading.value = true
  try {
    jobs.value = await api.get('/scans')
  } finally {
    loading.value = false
  }
}

async function resume(row) {
  await api.post(`/scans/${row.id}/resume`)
  ElMessage.success('已继续扫描')
  load()
}

async function cancel(row) {
  await ElMessageBox.confirm('确定取消该扫描任务吗?', '取消确认', { type: 'warning' })
  await api.post(`/scans/${row.id}/cancel`)
  ElMessage.success('已取消')
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

onActivated(() => {
  load()
  // 有进行中的任务时每 2 秒自动刷新
  timer = setInterval(() => {
    if (jobs.value.some(j => ['PENDING', 'RUNNING'].includes(j.status))) {
      load()
    }
  }, 2000)
  // 秒针:驱动卡片上的耗时显示
  ticker = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

function clearTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (ticker) {
    clearInterval(ticker)
    ticker = null
  }
}

// 页签切换走 keep-alive 的激活/失活,失活时停掉轮询
onDeactivated(clearTimer)
onUnmounted(clearTimer)
</script>

<style scoped>
/* 空状态紧凑展示,默认 el-empty 上下 40px padding 太高 */
.empty-compact {
  padding: 12px 0;
}
.empty-compact :deep(.el-empty__description) {
  margin-top: 4px;
}
.job-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.job-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.job-card:hover {
  box-shadow: var(--el-box-shadow-light);
}
.job-card-body {
  min-width: 0;
  flex: 1;
}
.job-card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.job-card-schema {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 悬停状态标签切换为取消按钮,两者同尺寸、容器固定高度,切换时不抖动 */
.job-card-action {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  height: 24px;
}
.job-card-action .tag-status,
.job-card-action .btn-cancel {
  height: 24px;
  min-width: 60px;
  margin: 0;
  padding: 0 8px;
  box-sizing: border-box;
  justify-content: center;
  font-size: 12px;
}
.job-card-action .btn-cancel {
  display: none;
}
.job-card-action:hover .tag-status {
  display: none;
}
.job-card-action:hover .btn-cancel {
  display: inline-flex;
}
.job-card-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}
</style>
