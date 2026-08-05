<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">库列表{{ dsName ? ` - ${dsName}` : '' }}</h3>
      <div>
        <el-button type="primary" :disabled="!selected.length" :loading="submitting" @click="scanSelected">
          批量扫描{{ selected.length ? `(${selected.length})` : '' }}
        </el-button>
        <el-button @click="goBack">返回数据源</el-button>
      </div>
    </div>
    <div v-if="databases.length" style="margin-bottom: 12px">
      <span style="margin-right: 8px; color: #606266">数据库</span>
      <el-select v-model="currentDb" style="width: 240px" @change="loadSchemas">
        <el-option v-for="d in databases" :key="d" :label="d" :value="d" />
      </el-select>
    </div>
    <el-input v-model="keyword" placeholder="按库名搜索" clearable style="width: 280px; margin-bottom: 12px" />
    <el-table :data="filteredSchemas" v-loading="loading" border row-key="name" @selection-change="onSelectionChange">
      <el-table-column type="selection" width="45" reserve-selection />
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="name" label="库名(Schema)" min-width="200" sortable>
        <template #default="{ row }">
          <el-link type="primary" @click="goTables(row.name)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="表数量" width="100" align="right" sortable :sort-method="(a, b) => (a.tableCount ?? -1) - (b.tableCount ?? -1)">
        <template #default="{ row }">
          <span :style="!row.tableCount ? 'color: #909399' : ''">{{ formatNumber(row.tableCount ?? 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近扫描" min-width="260" sortable :sort-method="(a, b) => scanAtMs(a) - scanAtMs(b)">
        <template #default="{ row }">
          <template v-if="row.lastScanStatus">
            <el-tag :type="statusTagType(row.lastScanStatus)" size="small" style="margin-right: 8px">
              {{ statusText(row.lastScanStatus) }}
            </el-tag>
            <el-progress
              v-if="['PENDING', 'RUNNING'].includes(row.lastScanStatus)"
              :percentage="scanPercent(row)"
              :stroke-width="10"
              style="width: 130px; display: inline-flex; vertical-align: middle"
            />
            <span v-else style="color: #909399">{{ formatDateTime(row.lastScanAt) }}</span>
          </template>
          <span v-else style="color: #909399">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="isScanning(row)" @click="scanOne(row)">扫描</el-button>
          <el-button link type="primary" :disabled="statsLoaded && !row.lastScanStatus" @click="goScans(row.name)">扫描记录</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'
import { setDsName, syncTab } from '../stores/tabs'
import { formatDateTime, formatNumber, statusTagType, statusText } from '../utils/format'
import { goBack as historyBack } from '../utils/back'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schemas = ref([])
const dsName = ref('')
const databases = ref([])
const currentDb = ref('')
const loading = ref(false)
const statsLoaded = ref(false)
const keyword = ref('')
const selected = ref([])
const submitting = ref(false)
let timer = null

const filteredSchemas = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return schemas.value
  return schemas.value.filter((s) => s.name.toLowerCase().includes(kw))
})

/** 从 jdbcUrl 解析默认库(databaseName=/database=) */
function parseDefaultDb(jdbcUrl) {
  const m = /[;?&]database(?:Name)?=([^;?&]+)/i.exec(jdbcUrl || '')
  return m ? decodeURIComponent(m[1]) : ''
}

/** 先渲染库名列表,再异步补表数量与最近扫描(失败不影响列表) */
async function loadSchemaStats() {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  try {
    const stats = await request.get(`/datasources/${dsId}/schema-stats${q}`)
    const byName = new Map((stats || []).map((s) => [s.name, s]))
    schemas.value = schemas.value.map((s) => ({ ...s, ...byName.get(s.name) }))
  } catch (e) {
    // 统计查询失败(如连接超时)时保留纯名称列表,扫描记录入口不置灰
  } finally {
    statsLoaded.value = true
    // 有进行中的任务时启动轮询,实时刷新进度
    if (hasRunning() && !timer) startPolling()
  }
}

async function loadSchemas() {
  statsLoaded.value = false
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  const schemaList = await request.get(`/datasources/${dsId}/schemas${q}`)
  schemas.value = (schemaList || []).map((name) => ({ name }))
  loadSchemaStats()
}

async function load() {
  loading.value = true
  try {
    const dsList = await request.get('/datasources').catch(() => [])
    const ds = (dsList || []).find((d) => String(d.id) === String(dsId))
    dsName.value = ds ? ds.name : ''
    // 回写数据源名并刷新页签标题(进入时 URL 可能没带 ?name=)
    if (dsName.value) {
      setDsName(dsId, dsName.value)
      syncTab(route)
    }
    if (ds && ds.dbType === 'SQLSERVER') {
      databases.value = await request.get(`/datasources/${dsId}/databases`).catch(() => [])
      const fromUrl = parseDefaultDb(ds.jdbcUrl)
      currentDb.value = databases.value.includes(fromUrl) ? fromUrl : (databases.value[0] || '')
    }
    await loadSchemas()
  } finally {
    loading.value = false
  }
}

function goTables(schema) {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables${q}`)
}

function goScans(schema) {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/scans${q}`)
}

// 原路返回;无历史记录(直接打开)时兜底回数据源列表
function goBack() {
  historyBack(router, '/datasources')
}

function onSelectionChange(rows) {
  selected.value = rows
}

function isScanning(row) {
  return ['PENDING', 'RUNNING'].includes(row.lastScanStatus)
}

function hasRunning() {
  return schemas.value.some(isScanning)
}

/** 最近任务的表级进度(完成表数/总表数) */
function scanPercent(row) {
  if (!row.lastScanTotalTables) return 0
  return Math.min(100, Math.round((row.lastScanDoneTables / row.lastScanTotalTables) * 100))
}

/** 最近扫描时间毫秒数,供排序;未扫描的排最前(升序时) */
function scanAtMs(row) {
  return row.lastScanAt ? new Date(row.lastScanAt).getTime() : -1
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    await loadSchemaStats()
    if (!hasRunning()) stopPolling()
  }, 2000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 对给定库逐个提交全库扫描任务(tables=null 即整库),失败的单独计数 */
async function submitScans(rows) {
  submitting.value = true
  try {
    const results = await Promise.allSettled(rows.map((row) =>
      request.post('/scans', {
        datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
        schema: row.name,
        database: currentDb.value || null,
        tables: null,
        forceFull: false,
        nullRules: []
      })
    ))
    const ok = results.filter((r) => r.status === 'fulfilled').length
    if (ok === results.length) {
      ElMessage.success(`已提交 ${ok} 个扫描任务`)
    } else {
      ElMessage.warning(`已提交 ${ok}/${results.length} 个扫描任务,其余提交失败`)
    }
    await loadSchemaStats()
  } finally {
    submitting.value = false
  }
}

function scanSelected() {
  // 跳过正在扫描的库,避免重复提交
  const targets = selected.value.filter((row) => !isScanning(row))
  if (!targets.length) {
    ElMessage.info('选中的库都正在扫描中')
    return
  }
  submitScans(targets)
}

function scanOne(row) {
  submitScans([row])
}

onMounted(load)

// 页签切换走 keep-alive:失活时停轮询,回来时刷新统计并按需恢复
onActivated(() => {
  loadSchemaStats()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>
