<template>
  <!-- 元数据批量同步对话框:选择视图(勾选数据源,默认全选)→ 进度视图(1s 轮询任务进度) -->
  <el-dialog
    :model-value="modelValue"
    :title="phase === 'select' ? '刷新元数据' : '元数据同步进度'"
    width="620px"
    destroy-on-close
    :close-on-press-escape="false"
    @update:model-value="onVisibleUpdate"
  >
    <!-- 选择视图:与导出数据源弹窗同款 全选/半选 + 勾选列表 -->
    <template v-if="phase === 'select'">
      <div v-loading="loadingList">
        <template v-if="dsList.length">
          <div class="sync-head">
            <el-checkbox :model-value="checkAll" :indeterminate="indeterminate" @change="onCheckAll">全选</el-checkbox>
            <span class="sync-count">已选 {{ checked.length }} / {{ dsList.length }}</span>
          </div>
          <el-checkbox-group v-model="checked" class="sync-list">
            <el-checkbox v-for="row in dsList" :key="row.id" :value="row.id" class="sync-item">
              <span class="sync-item-main">
                <DbTypeIcon :type="row.dbType" />
                <span class="sync-item-name">{{ row.name }}</span>
                <span class="sync-item-host" :title="row.jdbcUrl">{{ dbHost(row.jdbcUrl) }}</span>
                <!-- 连接异常的数据源不禁止勾选,行内红字提示,同步时会标记失败 -->
                <span v-if="row.connStatus === 'ERROR'" class="sync-item-err">{{ connKindLabel(row) }}</span>
              </span>
            </el-checkbox>
          </el-checkbox-group>
          <div class="sync-tip">将重新拉取所选数据源的库/表/字段元数据;连接异常的数据源可照常勾选,同步时会单独标记失败。</div>
        </template>
        <el-empty v-else-if="!loadingList" description="暂无数据源" :image-size="80" />
      </div>
    </template>

    <!-- 进度视图:整体进度 + 每数据源一行状态 -->
    <template v-else>
      <div class="sync-progress-head">
        <span>已完成 <b>{{ doneDs }}</b> / 共 {{ totalDs }}</span>
        <span v-if="failedDs" class="sync-failed-text">失败 {{ failedDs }}</span>
      </div>
      <el-progress :percentage="progressPct" :status="progressStatus" :stroke-width="12" />
      <div class="sync-job-list">
        <div v-for="it in items" :key="it.datasourceId" class="sync-job-row">
          <el-icon v-if="it.status === 'RUNNING'" class="is-loading sync-icon-run"><Loading /></el-icon>
          <el-icon v-else-if="it.status === 'DONE'" class="sync-icon-ok"><CircleCheck /></el-icon>
          <el-icon v-else-if="it.status === 'FAILED'" class="sync-icon-err"><CircleClose /></el-icon>
          <el-icon v-else class="sync-icon-wait"><Clock /></el-icon>
          <span class="sync-job-name" :title="it.name">{{ it.name }}</span>
          <span class="sync-job-info">
            <template v-if="it.status === 'DONE'">{{ doneInfo(it) }}</template>
            <template v-else-if="it.status === 'FAILED'"><span class="sync-err-text" :title="it.error">{{ it.error || '同步失败' }}</span></template>
            <template v-else-if="it.status === 'RUNNING'">{{ progressText(it) || '同步中…' }}</template>
            <template v-else-if="it.status === 'CANCELED'">已取消</template>
            <template v-else>等待中</template>
          </span>
        </div>
        <el-empty v-if="!items.length && isRunning" description="任务启动中…" :image-size="60" />
      </div>
      <el-alert v-if="jobError" type="error" :closable="false" show-icon :title="jobError" style="margin-top: 12px" />
      <!-- 任务终态汇总 -->
      <div v-if="isTerminal" class="sync-summary">
        {{ summaryText }}
      </div>
    </template>

    <template #footer>
      <template v-if="phase === 'select'">
        <el-button @click="onVisibleUpdate(false)">取消</el-button>
        <el-button type="primary" :disabled="!checked.length" :loading="starting" @click="start">开始同步</el-button>
      </template>
      <template v-else>
        <el-button v-if="isRunning" type="danger" plain :loading="cancelling" @click="cancel">取消任务</el-button>
        <el-button type="primary" @click="onVisibleUpdate(false)">{{ isRunning ? '后台运行' : '关闭' }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CircleCheck, CircleClose, Clock, Loading } from '@element-plus/icons-vue'
import { ElMessage } from '../utils/notify'
import request, { startMetadataSync, getLatestMetadataSync, getMetadataSyncJob, cancelMetadataSync } from '../api'
import DbTypeIcon from './DbTypeIcon.vue'

// 元数据批量同步对话框:done 在任务到达终态时触发一次,父级可借此刷新数据源列表(连接状态可能变化)
const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'done'])

// phase: select=勾选数据源;progress=任务进度
const phase = ref('select')
const dsList = ref([])
const checked = ref([])
const loadingList = ref(false)
const starting = ref(false)
const cancelling = ref(false)

// 当前任务:id + 最近一次轮询快照
const jobId = ref(null)
const job = ref(null)
// 防止终态重复触发 done(轮询停止前可能多拉一次)
let doneEmitted = false
let timer = null

// ---------- 选择视图 ----------
const checkAll = computed(() => dsList.value.length > 0 && checked.value.length === dsList.value.length)
const indeterminate = computed(() => checked.value.length > 0 && checked.value.length < dsList.value.length)

function onCheckAll(val) {
  checked.value = val ? dsList.value.map((r) => r.id) : []
}

/** 从 JDBC URL 提取主机名(与数据源卡片摘要同一口径) */
function dbHost(jdbcUrl) {
  const m = (jdbcUrl || '').match(/(?:@\/\/|:\/\/)([^/:;?]+)/)
  return m ? m[1] : ''
}

/** 连接失败分类文案(connKind:UNREACHABLE=网络不可达/AUTH=认证失败/OTHER=连接失败) */
function connKindLabel(row) {
  if (row.connKind === 'UNREACHABLE') return '网络不可达'
  if (row.connKind === 'AUTH') return '认证失败'
  return '连接失败'
}

// ---------- 防御性读取:后端字段命名若有出入,兼容常见 camelCase 变体 ----------
function pick(obj, ...keys) {
  for (const k of keys) {
    if (obj?.[k] !== undefined && obj?.[k] !== null) return obj[k]
  }
  return undefined
}

/** 后端任务详情为 {job, items} 结构,这里摊平成一层并做字段变体兼容 */
function normalizeJob(j) {
  if (!j || typeof j !== 'object') return null
  if (j.job && typeof j.job === 'object') return { ...j.job, items: j.items || j.details || [] }
  return j
}

/** 任务明细行:归一化字段名,状态统一大写 */
const items = computed(() => {
  const arr = pick(job.value, 'items', 'details', 'datasources') || []
  return arr.map((it) => ({
    datasourceId: pick(it, 'datasourceId', 'dsId', 'id'),
    name: pick(it, 'datasourceName', 'dsName', 'name') || `数据源 #${pick(it, 'datasourceId', 'dsId', 'id') ?? '?'}`,
    status: String(pick(it, 'status', 'state') || 'PENDING').toUpperCase(),
    dbCount: pick(it, 'dbCount', 'dbs'),
    schemaCount: pick(it, 'schemaCount', 'schemas'),
    tableCount: pick(it, 'tableCount', 'tables'),
    progress: pick(it, 'progress', 'progressText'),
    error: pick(it, 'error', 'errorMessage')
  }))
})

const jobStatus = computed(() => String(job.value?.status || job.value?.state || '').toUpperCase())
const isTerminal = computed(() => ['DONE', 'FAILED', 'CANCELED'].includes(jobStatus.value))
const isRunning = computed(() => phase.value === 'progress' && !isTerminal.value)

// 总数/完成数/失败数:优先取任务级字段,缺失时从明细行聚合兜底
const totalDs = computed(() => pick(job.value, 'totalDs', 'total', 'totalCount') ?? items.value.length)
const doneDs = computed(() => pick(job.value, 'doneDs', 'done', 'doneCount') ?? items.value.filter((i) => i.status === 'DONE').length)
const failedDs = computed(() => pick(job.value, 'failedDs', 'failed', 'failedCount') ?? items.value.filter((i) => i.status === 'FAILED').length)
const jobError = computed(() => pick(job.value, 'error', 'errorMessage') || '')

const progressPct = computed(() => (totalDs.value ? Math.round((doneDs.value / totalDs.value) * 100) : 0))
const progressStatus = computed(() => {
  if (jobStatus.value === 'FAILED') return 'exception'
  if (jobStatus.value === 'DONE') return 'success'
  return ''
})

const summaryText = computed(() => {
  if (jobStatus.value === 'CANCELED') return `任务已取消:成功 ${doneDs.value},失败 ${failedDs.value}`
  if (jobStatus.value === 'FAILED') return `同步失败:成功 ${doneDs.value},失败 ${failedDs.value}`
  return `同步完成:成功 ${doneDs.value},失败 ${failedDs.value}`
})

/** 成功行计数摘要:库 X / Schema Y / 表 Z(字段缺失则省略该项) */
function doneInfo(it) {
  const parts = []
  if (it.dbCount != null) parts.push(`库 ${it.dbCount}`)
  if (it.schemaCount != null) parts.push(`Schema ${it.schemaCount}`)
  if (it.tableCount != null) parts.push(`表 ${it.tableCount}`)
  return parts.length ? parts.join(' / ') : '同步成功'
}

/** 运行中行进度文本:字符串原样展示;数值按百分比展示(0~1 视为小数比例) */
function progressText(it) {
  const p = it.progress
  if (p == null || p === '') return ''
  if (typeof p === 'number') return p <= 1 ? `${Math.round(p * 100)}%` : `${Math.round(p)}%`
  return String(p)
}

// ---------- 轮询 ----------
function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startPolling() {
  stopPolling()
  timer = setInterval(poll, 1000)
}

async function poll() {
  if (!jobId.value) return
  try {
    const j = normalizeJob(await getMetadataSyncJob(jobId.value))
    if (j && typeof j === 'object') {
      job.value = j
      if (['DONE', 'FAILED', 'CANCELED'].includes(String(j.status || j.state || '').toUpperCase())) {
        stopPolling()
        if (!doneEmitted) {
          doneEmitted = true
          emit('done')
        }
      }
    }
  } catch {
    // 轮询失败静默(接口 _silent),下个周期重试
  }
}

// ---------- 动作 ----------
async function start() {
  if (!checked.value.length || starting.value) return
  starting.value = true
  try {
    const resp = await startMetadataSync(checked.value)
    // 返回 { jobId },兼容直接返回任务 id 或 { id }
    const id = typeof resp === 'number' ? resp : (resp?.jobId ?? resp?.id)
    jobId.value = id
    job.value = null
    phase.value = 'progress'
    await poll()
    startPolling()
  } catch (e) {
    if (e?.response?.status === 409) {
      // 已有运行中任务:拉最近一次任务,仍在运行则直接接入其进度
      const latest = normalizeJob(await getLatestMetadataSync().catch(() => null))
      const st = String(latest?.status || latest?.state || '').toUpperCase()
      if (latest && ['PENDING', 'RUNNING'].includes(st)) {
        jobId.value = latest.id
        job.value = latest
        phase.value = 'progress'
        startPolling()
        ElMessage.info('已有运行中的同步任务,已接入该任务进度')
        return
      }
      ElMessage.warning(e?.response?.data?.message || '已有运行中的同步任务,请稍后再试')
    } else {
      ElMessage.error(e?.response?.data?.message || '启动同步失败')
    }
  } finally {
    starting.value = false
  }
}

async function cancel() {
  if (!jobId.value || cancelling.value) return
  cancelling.value = true
  try {
    await cancelMetadataSync(jobId.value)
    await poll()
  } finally {
    cancelling.value = false
  }
}

/** 关闭:任务仍在运行时提示后台继续运行(不阻断关闭) */
function onVisibleUpdate(v) {
  if (!v && isRunning.value) ElMessage.info('同步任务仍在后台继续运行')
  emit('update:modelValue', v)
}

// 打开时重置为选择视图并拉数据源列表(默认全选);关闭时停轮询。
// immediate 不能省:父级用 v-model 挂载,首次打开时组件随 modelValue=true 一起创建
watch(() => props.modelValue, async (v) => {
  if (!v) {
    stopPolling()
    return
  }
  phase.value = 'select'
  job.value = null
  jobId.value = null
  doneEmitted = false
  stopPolling()
  loadingList.value = true
  try {
    dsList.value = await request.get('/datasources')
    checked.value = dsList.value.map((r) => r.id)
  } finally {
    loadingList.value = false
  }
}, { immediate: true })
</script>

<style scoped>
/* 选择视图:与导出数据源弹窗同款 全选行 + 勾选项列表 */
.sync-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.sync-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sync-list {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow-y: auto;
}
.sync-item {
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 6px 0;
}
.sync-item :deep(.el-checkbox__label) {
  flex: 1;
  overflow: hidden;
}
.sync-item-main {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
.sync-item-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
  white-space: nowrap;
}
.sync-item-host {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sync-item-err {
  color: var(--el-color-danger);
  font-size: 12px;
  white-space: nowrap;
}
.sync-tip {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 进度视图 */
.sync-progress-head {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
  font-size: 14px;
}
.sync-failed-text {
  color: var(--el-color-danger);
  font-size: 13px;
}
.sync-job-list {
  margin-top: 12px;
  max-height: 320px;
  overflow-y: auto;
}
.sync-job-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.sync-job-row:last-child {
  border-bottom: none;
}
.sync-icon-ok {
  color: var(--el-color-success);
}
.sync-icon-err {
  color: var(--el-color-danger);
}
.sync-icon-run {
  color: var(--el-color-primary);
}
.sync-icon-wait {
  color: var(--el-text-color-placeholder);
}
.sync-job-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}
.sync-job-info {
  flex: 1;
  min-width: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sync-err-text {
  color: var(--el-color-danger);
}
.sync-summary {
  margin-top: 12px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}
</style>
