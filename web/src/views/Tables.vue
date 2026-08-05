<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">表列表 - {{ db ? db + '.' + schema : schema }}</h3>
      <div>
        <el-button @click="goBack">返回</el-button>
        <el-button @click="$router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/scans${dbQuery()}`)">扫描记录</el-button>
        <el-button type="primary" @click="openScanDialog">开始扫描</el-button>
      </div>
    </div>

    <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
      <el-input v-model="keyword" placeholder="按表名或注释搜索" clearable style="width: 280px" />
      <el-checkbox v-model="onlyEmpty">只看空表(约行数为 0)</el-checkbox>
    </div>

    <el-alert v-if="tables.length" type="info" :closable="false" style="margin-bottom: 12px">
      <span style="margin-right: 24px">总行数: 约 {{ formatNumber(totalEstRows) }}</span>
      <span style="margin-right: 24px">总大小: {{ formatBytes(totalSizeBytes) }}</span>
      <span style="margin-right: 24px">表数量: {{ tables.length }}</span>
      <span>
        空表数量:
        <el-link type="primary" :disabled="!emptyTables.length" @click="onlyEmpty = true">{{ emptyTables.length }}</el-link>
      </span>
    </el-alert>

    <el-table :data="filteredTables" v-loading="loading" border @selection-change="onSelectionChange">
      <el-table-column type="selection" width="45" />
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="name" label="表名" min-width="180" sortable show-overflow-tooltip>
        <template #default="{ row }">
          <el-link v-if="latestScans[row.name]" type="primary" @click="goLatestResult(row)">{{ row.name }}</el-link>
          <span v-else>{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="comment" label="注释" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.comment">{{ row.comment }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="storageInfo" label="引擎/表空间" width="130">
        <template #default="{ row }">
          <span v-if="row.storageInfo">{{ row.storageInfo }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="estRows" label="约行数" width="140" sortable>
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>来自数据库元数据的估算行数,不是 COUNT(*) 精确值</div>
              <div>超过采样阈值(默认 100 万行或 10GB,可在数据源配置)的表,扫描时默认只采样统计</div>
            </template>
            <span>约行数 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <span v-if="row.estRows !== null && row.estRows !== undefined">约 {{ formatNumber(row.estRows) }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="sizeBytes" label="总大小" width="140" sortable>
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>数据 + 索引占用的估算值,来自数据库元数据</div>
            </template>
            <span>总大小 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column label="最近扫描时间" width="170" sortable :sort-method="(a, b) => latestScanTime(a) - latestScanTime(b)">
        <template #default="{ row }">
          <span v-if="latestScans[row.name]">{{ formatDateTime(latestScans[row.name].finishedAt) }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <!-- 正在扫描:显示分段进度,点击跳到任务详情;排队中的表显示 0% -->
          <el-progress
            v-if="runningScans[row.name]"
            :percentage="scanPercent(runningScans[row.name])"
            :stroke-width="10"
            style="cursor: pointer"
            @click="goRunningJob(row)"
          />
          <el-button v-else link type="primary" @click="scanSingle(row)">扫描</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="scanDialogVisible" title="开始扫描" width="640px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="扫描范围">
          <span v-if="singleTable">仅扫描表:{{ singleTable }}</span>
          <span v-else-if="selectedTables.length">已选 {{ selectedTables.length }} 张表</span>
          <span v-else>未选择表,将扫描全库</span>
        </el-form-item>
        <el-form-item label="强制全量">
          <el-switch v-model="scanForm.forceFull" />
          <div class="form-tip">超过阈值的表不做采样,逐行精确统计</div>
        </el-form-item>
        <el-form-item label="表大小上限">
          <div style="width: 100%">
            <el-input-number v-model="scanForm.maxSizeValue" :min="1" placeholder="不限制"
                             controls-position="right" style="width: 160px" />
            <el-select v-model="scanForm.maxSizeUnit" style="width: 80px; margin-left: 8px">
              <el-option label="MB" value="MB" />
              <el-option label="GB" value="GB" />
            </el-select>
            <div class="form-tip">只扫描不超过该大小的表(按元数据估算的数据+索引大小),留空表示不限制</div>
            <div v-if="skippedBySize" class="form-tip" style="color: #e6a23c">
              当前范围内有 {{ skippedBySize }} 张表超过上限,将被跳过
            </div>
          </div>
        </el-form-item>
        <el-form-item label="空值规则">
          <div style="width: 100%">
            <div v-for="(rule, idx) in scanForm.nullRules" :key="idx" class="rule-row">
              <el-input v-model="rule.column" placeholder="列名(* 表示所有列)" style="width: 180px" />
              <el-input v-model="rule.valuesText" placeholder="视为空的取值,逗号分隔" style="flex: 1" />
              <el-button link type="danger" @click="scanForm.nullRules.splice(idx, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="scanForm.nullRules.push({ column: '', valuesText: '' })">
              + 添加规则
            </el-button>
            <div class="form-tip">例如:列名 *,取值 0,-1 表示所有列中值为 0 或 -1 的也视为空</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scanDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitScan">提交扫描</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import request from '../api'
import { formatBytes, formatDateTime, formatNumber } from '../utils/format'
import { goBack as historyBack } from '../utils/back'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schema = route.params.schema
const db = route.query.db || ''

const tables = ref([])
const loading = ref(false)
const keyword = ref('')
const selectedTables = ref([])
// 每张表最近一次 DONE 扫描的信息(表名 -> { jobId, finishedAt }),有值的表名渲染为链接
const latestScans = ref({})
// 运行中任务里每张未完成表的分段进度(表名 -> { jobId, status, doneChunks, totalChunks })
const runningScans = ref({})

const scanDialogVisible = ref(false)
const submitting = ref(false)
// maxSizeValue 为空(null)表示不限制表大小
const scanForm = reactive({ forceFull: false, nullRules: [], maxSizeValue: null, maxSizeUnit: 'GB' })
// 行内"扫描"按钮带出的单表目标;为空则按勾选/全库走
const singleTable = ref('')

// 空表:估算行数为 0(含估算缺失)的表
const onlyEmpty = ref(false)
const emptyTables = computed(() => tables.value.filter((t) => !t.estRows))
// 库级汇总:行数/大小均为各表估算值之和
const totalEstRows = computed(() => tables.value.reduce((sum, t) => sum + (t.estRows || 0), 0))
const totalSizeBytes = computed(() => tables.value.reduce((sum, t) => sum + (t.sizeBytes || 0), 0))

// 当前扫描范围内的表:单表 > 勾选 > 全库
const scopeTables = computed(() => {
  if (singleTable.value) return tables.value.filter((t) => t.name === singleTable.value)
  return selectedTables.value.length ? selectedTables.value : tables.value
})

// 表大小上限换算成字节;未设置返回 null
function maxSizeBytes() {
  if (!scanForm.maxSizeValue) return null
  return scanForm.maxSizeValue * (scanForm.maxSizeUnit === 'GB' ? 1073741824 : 1048576)
}

// 范围内将被大小上限跳过的表数量(大小未知的表不参与统计)
const skippedBySize = computed(() => {
  const limit = maxSizeBytes()
  if (!limit) return 0
  return scopeTables.value.filter((t) => t.sizeBytes != null && t.sizeBytes > limit).length
})

const filteredTables = computed(() => {
  let list = onlyEmpty.value ? emptyTables.value : tables.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((t) =>
      t.name.toLowerCase().includes(kw) || (t.comment || '').toLowerCase().includes(kw))
  }
  return list
})

async function load() {
  loading.value = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    // 最新扫描映射查的是本地 H2,失败时仅影响表名是否可点,不阻塞表列表
    const [tableList, latest] = await Promise.all([
      request.get(`${base}/tables${dbQuery()}`),
      request.get(`${base}/latest-scan-jobs${dbQuery()}`).catch(() => ({}))
    ])
    tables.value = tableList
    latestScans.value = latest || {}
  } finally {
    loading.value = false
  }
}

// 轮询运行中扫描进度(本地 H2 查询,开销极小);没有运行中任务时也低频探一次,能发现别处发起的扫描
let pollTimer = null
async function fetchRunning() {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  runningScans.value = await request.get(`${base}/running-scans${dbQuery()}`).catch(() => ({}))
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(fetchRunning, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 表级分段进度百分比
function scanPercent(s) {
  if (!s.totalChunks) return 0
  return Math.min(100, Math.round((s.doneChunks / s.totalChunks) * 100))
}

// 点击进度条跳到正在扫描的任务详情
function goRunningJob(row) {
  router.push(`/scans/${runningScans.value[row.name].jobId}?schema=${encodeURIComponent(db ? `${db}.${schema}` : schema)}`)
}

// 行内"扫描"按钮:只扫这一张表
function scanSingle(row) {
  openScanDialog()
  singleTable.value = row.name
}

// 排序用:该表最近扫描完成时间的毫秒值,未扫描过排最前
function latestScanTime(row) {
  const t = latestScans.value[row.name]?.finishedAt
  return t ? new Date(t).getTime() : 0
}

function dbQuery() {
  return db ? `?db=${encodeURIComponent(db)}` : ''
}

// 原路返回;无历史记录(直接打开)时兜底回库列表
function goBack() {
  historyBack(router, `/datasources/${dsId}/schemas`)
}

function onSelectionChange(rows) {
  selectedTables.value = rows
}

// 点击表名直达该表最近一次扫描完成的字段级结果
// (旧版后端只返回 jobId 数字,做一层兼容,重启后端后可去掉)
function goLatestResult(row) {
  const s = latestScans.value[row.name]
  router.push(`/scans/${s.jobId ?? s}/tables/${encodeURIComponent(row.name)}`)
}

function openScanDialog() {
  singleTable.value = ''
  scanForm.forceFull = false
  scanForm.nullRules = []
  scanForm.maxSizeValue = null
  scanForm.maxSizeUnit = 'GB'
  scanDialogVisible.value = true
}

async function submitScan() {
  const nullRules = scanForm.nullRules
    .filter((r) => r.column.trim() && r.valuesText.trim())
    .map((r) => ({
      column: r.column.trim(),
      values: r.valuesText.split(',').map((v) => v.trim()).filter(Boolean)
    }))
    .filter((r) => r.values.length > 0)

  submitting.value = true
  try {
    const res = await request.post('/scans', {
      datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
      schema,
      database: db || null,
      tables: singleTable.value ? [singleTable.value] : (selectedTables.value.length ? selectedTables.value.map((t) => t.name) : null),
      forceFull: scanForm.forceFull,
      nullRules,
      maxTableSizeBytes: maxSizeBytes()
    })
    ElMessage.success('扫描任务已提交')
    scanDialogVisible.value = false
    // 带上库名标签,供页签标题展示
    const schemaLabel = db ? `${db}.${schema}` : schema
    router.push(`/scans/${res.jobId}?schema=${encodeURIComponent(schemaLabel)}`)
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await load()
  fetchRunning()
  startPolling()
})

// 页签下钻时是失活而非卸载:停掉轮询,回来时立即刷新并恢复
onActivated(() => {
  fetchRunning()
  startPolling()
})

onDeactivated(stopPolling)

onUnmounted(stopPolling)
</script>

<style scoped>
.rule-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}
</style>
