<template>
  <div class="page-card">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div>
        <ExportButton v-if="hasJob" :job-id="jobId" />
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <!-- 扫描信息:有扫描时展示统计概览;无扫描时提示仅结构 -->
    <el-alert v-if="hasJob && jobTable" type="success" :closable="false" style="margin-bottom: 16px">
      <span style="margin-right: 24px">总行数: {{ formatNumber(jobTable.totalRows ?? jobTable.scannedRows) }}</span>
      <span style="margin-right: 24px">
        统计方式: {{ jobTable.sampled ? '采样' : '全量' }}
        <el-tooltip v-if="jobTable.sampled" placement="top" :show-after="200">
          <template #content>
            <div>该表只统计了样本(默认 10 万行),空值数、有值率等由样本按比例推算,为估算值</div>
            <div>MySQL / 达梦 / OceanBase 为 LIMIT 顺序采样,结果可能有偏</div>
          </template>
          <el-tag type="warning" size="small" style="margin-left: 6px">估算值</el-tag>
        </el-tooltip>
      </span>
      <span v-if="jobTable.sampled" style="margin-right: 24px">采样行数: {{ formatNumber(jobTable.sampleRows) }}</span>
      <span style="margin-right: 24px">耗时: {{ formatDuration(jobTable.startedAt, jobTable.finishedAt) }}</span>
      <span style="margin-right: 24px">字段数量: {{ columns.length }}</span>
      <span>
        空字段数量:
        <el-link type="primary" :disabled="!emptyColumns.length" @click="onlyEmpty = true">{{ emptyColumns.length }}</el-link>
      </span>
    </el-alert>
    <el-alert v-else type="info" :closable="false" style="margin-bottom: 12px">
      该表尚未扫描,以下为数据库元数据中的字段结构与索引结构,不含空值与有值率统计。
      在表列表勾选该表后「开始扫描」,扫描完成即可查看字段级统计。
    </el-alert>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="字段明细" name="columns">
        <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
          <el-input v-model="keyword" placeholder="按字段名或注释搜索" clearable style="width: 280px" />
          <el-checkbox v-if="hasJob" v-model="onlyEmpty">只看空字段(有值数为 0)</el-checkbox>
          <span>字段数量: {{ columns.length }}</span>
        </div>

        <!-- 字段列表:基础结构列 + (已扫描时)统计列 -->
        <el-table :data="filteredColumns" v-loading="loading" border>
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column prop="name" label="字段名" min-width="140" sortable show-overflow-tooltip />
          <el-table-column prop="comment" label="注释" min-width="140" sortable show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.comment">{{ row.comment }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="displayType" label="类型" width="150" sortable show-overflow-tooltip />
          <el-table-column label="键" width="70" sortable :sort-method="(a, b) => keyLabel(a).localeCompare(keyLabel(b))">
            <template #default="{ row }">
              <el-tag v-if="keyLabel(row)" size="small" :type="keyLabel(row) === 'PK' ? 'primary' : 'success'">{{ keyLabel(row) }}</el-tag>
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
          <template v-if="hasJob">
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
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="索引结构" name="indexes">
        <div style="display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px">
          <h4 style="margin: 0">索引结构</h4>
          <span style="color: var(--el-text-color-secondary)">共 {{ indexes.length }} 个</span>
        </div>
        <el-table :data="indexes" v-loading="indexesLoading" border size="small">
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column prop="name" label="索引名" min-width="180" show-overflow-tooltip />
          <el-table-column label="唯一" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.unique" type="success" size="small">唯一</el-tag>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <el-table-column label="索引列" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="c in row.columns" :key="c" size="small" style="margin: 0 4px 2px 0">{{ c }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import request from '../api'
import ExportButton from '../components/ExportButton.vue'
import Breadcrumb from '../components/Breadcrumb.vue'
import { formatDuration, formatNumber } from '../utils/format'
import { ensureDsName, getDsName, syncTab } from '../stores/tabs'

const route = useRoute()
const tableName = route.params.tableName
// 兼容旧路由 /scans/:jobId/tables/:tableName:数据源上下文从任务接口回填
const isScanRoute = route.path.startsWith('/scans/')

const dsId = ref(isScanRoute ? '' : route.params.id)
const schema = ref(isScanRoute ? '' : route.params.schema)
const db = ref(isScanRoute ? '' : (route.query.db || ''))
const jobId = ref(isScanRoute ? route.params.jobId : (route.query.jobId || ''))

const metaColumns = ref([])   // 结构字段(元数据)
const indexes = ref([])       // 索引结构
const statsColumns = ref([])  // 扫描统计字段(有扫描时)
const jobTable = ref(null)    // 任务中该表的统计概览
const loading = ref(false)
const indexesLoading = ref(false)
const indexesLoaded = ref(false)  // 索引是否已加载(懒加载)
const activeTab = ref('columns')
const refreshing = ref(false)
const keyword = ref('')
const onlyEmpty = ref(false)

const hasJob = computed(() => !!jobId.value)

// 空字段:有值数为 0 的字段
const emptyColumns = computed(() => columns.value.filter((c) => (c.valueCount || 0) === 0))

// 字段合并:以结构为准,扫描统计按列名附加
const columns = computed(() => {
  const statsMap = new Map(statsColumns.value.map((c) => [c.columnName, c]))
  return metaColumns.value.map((c) => ({ ...c, ...(statsMap.get(c.name) || {}) }))
})

const filteredColumns = computed(() => {
  let list = onlyEmpty.value ? emptyColumns.value : columns.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((c) =>
      (c.name || '').toLowerCase().includes(kw) || (c.comment || '').toLowerCase().includes(kw))
  }
  return list
})

// 键约束展示:PK / UNI / 空(结构推导优先,兼容扫描接口旧 keyLabel)
function keyLabel(row) {
  if (row.primaryKey) return 'PK'
  if (row.uniqueIndexFirst) return 'UNI'
  return row.keyLabel || ''
}

function nullTotal(row) {
  return (row.nullCount || 0) + (row.emptyCount || 0) + (row.ruleHitCount || 0)
}

function sortByNullTotal(a, b) {
  return nullTotal(a) - nullTotal(b)
}

async function load(refresh = false) {
  loading.value = true
  try {
    // 旧扫描路由 / 表路由带 jobId:先取任务信息(拿数据源上下文 + 该表统计概览)
    if (isScanRoute || jobId.value) {
      const job = await request.get(`/scans/${jobId.value}`).catch(() => null)
      if (job) {
        if (isScanRoute) {
          dsId.value = job.datasourceId
          schema.value = job.schemaName
          db.value = job.dbName || ''
        }
        jobTable.value = job.tables?.find((t) => t.tableName === tableName) || null
      }
    }
    if (!dsId.value || !schema.value) return

    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (refresh) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`

    // 结构 + 扫描统计并行拉取(索引懒加载,切到索引 tab 时才请求)
    const [cols, stats] = await Promise.all([
      request.get(`${url}/columns${q}`),
      jobId.value
        ? request.get(`/scans/${jobId.value}/tables/${encodeURIComponent(tableName)}/columns`).catch(() => [])
        : Promise.resolve([])
    ])
    metaColumns.value = cols
    statsColumns.value = stats || []
  } finally {
    loading.value = false
  }
}

/** 索引懒加载:首次切到索引 tab 或刷新时调用,已加载则跳过(除非强制) */
async function loadIndexes(force = false) {
  if (indexesLoaded.value && !force) return
  if (!dsId.value || !schema.value) return
  indexesLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (force) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`
    indexes.value = await request.get(`${url}/indexes${q}`).catch(() => [])
    indexesLoaded.value = true
  } finally {
    indexesLoading.value = false
  }
}

/** 切换 tab:切到索引时懒加载 */
function onTabChange(name) {
  if (name === 'indexes') loadIndexes()
}

/** 手动刷新:结构强制从业务库拉最新并覆盖本地缓存,统计一并重拉;索引按当前 tab 决定是否重载 */
async function refreshAll() {
  refreshing.value = true
  try {
    // 重置索引加载标记,若当前在索引 tab 则强制重新拉取
    indexesLoaded.value = false
    await load(true)
    if (activeTab.value === 'indexes') await loadIndexes(true)
    ElMessage.success('已刷新结构与扫描信息')
  } finally {
    refreshing.value = false
  }
}

// ---------- 面包屑 ----------
const dsName = computed(() => getDsName(dsId.value) || `数据源 ${dsId.value}`)
const schemaLabel = computed(() => (db.value ? `${db.value}.${schema.value}` : schema.value))
const tablesPath = computed(() => {
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables`
  return db.value ? `${base}?db=${encodeURIComponent(db.value)}` : base
})
const breadcrumbItems = computed(() => {
  const items = [
    { label: dsName.value, to: `/datasources/${dsId.value}/schemas` },
    { label: schemaLabel.value, to: tablesPath.value }
  ]
  if (hasJob.value) items.push({ label: `扫描 #${jobId.value}`, to: `/scans/${jobId.value}` })
  items.push({ label: tableName })
  return items
})

onMounted(async () => {
  await load()
  // 数据源名兜底解析:刷新/直达 URL 无 ?name= 时也能恢复真名,并刷新页签标题
  // (dsId 在 load 内由任务接口回填,须在 load 之后解析)
  if (dsId.value) ensureDsName(dsId.value).then(() => syncTab(route))
})
</script>
