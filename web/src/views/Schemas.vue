<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">库列表{{ dsName ? ` - ${dsName}` : '' }}</h3>
      <div>
        <el-button @click="openFilter">库过滤</el-button>
        <el-button type="primary" :disabled="!selected.length" :loading="submitting" @click="scanSelected">
          批量扫描{{ selected.length ? `(${selected.length})` : '' }}
        </el-button>
        <el-button @click="goBack">返回数据源</el-button>
      </div>
    </div>
    <div v-if="databases.length" style="margin-bottom: 12px">
      <span style="margin-right: 8px; color: var(--el-text-color-regular)">数据库</span>
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
          <span :style="!row.tableCount ? 'color: var(--el-text-color-secondary)' : ''">{{ formatNumber(row.tableCount ?? 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="占用空间" width="110" align="right" sortable :sort-method="(a, b) => (a.sizeBytes ?? -1) - (b.sizeBytes ?? -1)">
        <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column label="标记" min-width="220">
        <template #default="{ row }">
          <template v-if="row.tags && row.tags.length">
            <el-tag
              v-for="tag in row.tags.slice(0, TAG_VISIBLE_COUNT)"
              :key="tag.tagId"
              :type="tag.kind === 'EMPTY' ? 'info' : 'primary'"
              size="small"
              effect="plain"
              style="cursor: pointer; margin: 0 4px 4px 0"
              @click="goTagTables(row, tag)"
            >
              <span :style="{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: tag.color, marginRight: '4px' }" />{{ tag.tagName }} {{ tag.count }}
            </el-tag>
            <el-popover v-if="row.tags.length > TAG_VISIBLE_COUNT" trigger="click" placement="bottom-start" width="280">
              <template #reference>
                <el-tag size="small" effect="plain" style="cursor: pointer; margin: 0 4px 4px 0">+{{ row.tags.length - TAG_VISIBLE_COUNT }} 更多</el-tag>
              </template>
              <div>
                <el-tag
                  v-for="tag in row.tags"
                  :key="tag.tagId"
                  :type="tag.kind === 'EMPTY' ? 'info' : 'primary'"
                  size="small"
                  effect="plain"
                  style="cursor: pointer; margin: 0 6px 6px 0"
                  @click="goTagTables(row, tag)"
                >
                  <span :style="{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: tag.color, marginRight: '4px' }" />{{ tag.tagName }} {{ tag.count }}
                </el-tag>
              </div>
            </el-popover>
          </template>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
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
            <span v-else style="color: var(--el-text-color-secondary)">{{ formatDateTime(row.lastScanAt) }}</span>
          </template>
          <span v-else style="color: var(--el-text-color-secondary)">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="isScanning(row)" @click="scanOne(row)">扫描</el-button>
          <el-button link type="primary" :disabled="statsLoaded && !row.lastScanStatus" @click="goScans(row.name)">扫描记录</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 库过滤:勾选需要显示的库,保存为数据源级白名单(与编辑数据源对话框的「库过滤」页签同一份配置) -->
    <el-dialog v-model="filterVisible" title="库过滤" width="480px" destroy-on-close>
      <div v-loading="filterLoading">
        <div class="filter-tip">勾选需要显示的库;全部勾选(或全不勾)表示不过滤。数据库自身的系统库可不勾。</div>
        <template v-if="filterList.length">
          <div class="filter-all">
            <el-checkbox :model-value="filterCheckAll" :indeterminate="filterIndeterminate" @change="onFilterCheckAll">全部</el-checkbox>
            <span class="filter-count">已选 {{ filterChecked.length }} / {{ filterList.length }}</span>
          </div>
          <el-checkbox-group v-model="filterChecked" class="filter-list">
            <el-checkbox v-for="db in filterList" :key="db" :value="db">{{ db }}</el-checkbox>
          </el-checkbox-group>
        </template>
        <el-empty v-else-if="!filterLoading" description="没有可选择的库" :image-size="60" />
      </div>
      <template #footer>
        <el-button @click="filterVisible = false">取消</el-button>
        <el-button type="primary" :loading="filterSaving" :disabled="!filterList.length" @click="saveFilter">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElCheckbox, ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'
import { setDsName, syncTab } from '../stores/tabs'
import { formatBytes, formatDateTime, formatNumber, statusTagType, statusText } from '../utils/format'
import { goBack as historyBack } from '../utils/back'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schemas = ref([])
const dsName = ref('')
const dsRow = ref(null)
const databases = ref([])
const currentDb = ref('')
const loading = ref(false)
const statsLoaded = ref(false)
const keyword = ref('')
const selected = ref([])
const submitting = ref(false)
let timer = null

// ---------- 库过滤弹窗(保存为数据源级白名单,与编辑数据源对话框同一份配置) ----------
const filterVisible = ref(false)
const filterLoading = ref(false)
const filterSaving = ref(false)
const filterList = ref([])
const filterChecked = ref([])

const filterCheckAll = computed(() => filterList.value.length > 0 && filterChecked.value.length === filterList.value.length)
const filterIndeterminate = computed(() => filterChecked.value.length > 0 && filterChecked.value.length < filterList.value.length)

function onFilterCheckAll(val) {
  filterChecked.value = val ? [...filterList.value] : []
}

/** 打开弹窗并拉全量库列表(all=true 旁路白名单),按已存白名单回填勾选 */
async function openFilter() {
  filterVisible.value = true
  filterLoading.value = true
  filterList.value = []
  try {
    let all
    if (dsRow.value?.dbType === 'SQLSERVER') {
      // 多库方言:白名单作用于数据库层级
      all = await request.get(`/datasources/${dsId}/databases?all=true`)
    } else {
      const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}&all=true` : '?all=true'
      all = await request.get(`/datasources/${dsId}/schemas${q}`)
    }
    filterList.value = all || []
    const cur = dsRow.value?.schemaFilter
    filterChecked.value = cur?.length
      ? filterList.value.filter((d) => cur.includes(d))
      : [...filterList.value]
  } finally {
    filterLoading.value = false
  }
}

/** 保存白名单并刷新库列表;全勾/全不勾视为不过滤 */
async function saveFilter() {
  filterSaving.value = true
  try {
    const schemas = (filterChecked.value.length === 0 || filterChecked.value.length === filterList.value.length)
      ? null
      : [...filterChecked.value]
    await request.put(`/datasources/${dsId}/schema-filter`, { schemas })
    if (dsRow.value) dsRow.value.schemaFilter = schemas
    ElMessage.success('库过滤已更新')
    filterVisible.value = false
    // SQL Server 的数据库下拉同样受白名单约束:当前选中库被过滤掉时回退到第一个
    if (dsRow.value?.dbType === 'SQLSERVER') {
      databases.value = await request.get(`/datasources/${dsId}/databases`).catch(() => [])
      if (!databases.value.includes(currentDb.value)) {
        currentDb.value = databases.value[0] || ''
      }
    }
    await loadSchemas()
  } finally {
    filterSaving.value = false
  }
}

// 库行平铺展示的标记块上限,超出折叠为「+N 更多」
const TAG_VISIBLE_COUNT = 3

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

/** 先渲染库名列表,再异步补表数量/最近扫描/标记统计(失败不影响列表) */
async function loadSchemaStats() {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  try {
    // 标记统计与库统计并行拉取;标记接口失败时返回 null,保留行内已有标记数据(轮询不刷丢)
    const [stats, tagStats] = await Promise.all([
      request.get(`/datasources/${dsId}/schema-stats${q}`),
      request.get(`/datasources/${dsId}/schema-tag-stats${q}`).catch(() => null)
    ])
    const byName = new Map((stats || []).map((s) => [s.name, s]))
    const tagsByName = tagStats ? new Map(tagStats.map((s) => [s.schemaName, s.tags])) : null
    schemas.value = schemas.value.map((s) => ({
      ...s,
      ...byName.get(s.name),
      // 接口只返回有标记表的库;无标记的库置 null,列内显示「—」
      ...(tagsByName ? { tags: tagsByName.get(s.name) || null } : {})
    }))
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
    dsRow.value = ds || null
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

/** 点击标记块:跳表列表的只读筛选模式(该库 + 该标记) */
function goTagTables(row, tag) {
  router.push({
    path: `/datasources/${dsId}/schemas/${encodeURIComponent(row.name)}/tables`,
    query: {
      ...(currentDb.value ? { db: currentDb.value } : {}),
      tagId: tag.tagId,
      tagName: tag.tagName
    }
  })
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

// 大模型配置是否可用(合并默认值后完整),决定「AI 自动打标」复选框默认勾选;首次确认时拉取并缓存
const aiAvailable = ref(false)
let aiConfigFetched = false
async function ensureAiConfig() {
  if (aiConfigFetched) return
  aiConfigFetched = true
  const cfg = await request.get('/ai-config').catch(() => null)
  aiAvailable.value = !!cfg?.available
}

/** 整库扫描确认:内嵌「AI 自动打标」复选(默认勾选 = 模型配置可用),取消则放弃提交 */
async function confirmScans(rows) {
  await ensureAiConfig()
  const autoTag = ref(aiAvailable.value)
  const text = rows.length === 1 ? `将对库「${rows[0].name}」发起全库扫描` : `将对 ${rows.length} 个库发起全库扫描`
  try {
    await ElMessageBox.confirm(
      h('div', null, [
        h('div', null, text),
        h('div', { style: 'margin-top: 8px' }, [
          h(ElCheckbox, {
            modelValue: autoTag.value,
            'onUpdate:modelValue': (v) => { autoTag.value = v }
          }, () => 'AI 自动打标(无注释的表会抽样 100 行数据发给大模型)')
        ])
      ]),
      '开始扫描',
      { confirmButtonText: '开始扫描', cancelButtonText: '取消' }
    )
  } catch {
    return // 取消
  }
  submitScans(rows, autoTag.value)
}

/** 对给定库逐个提交全库扫描任务(tables=null 即整库),失败的单独计数 */
async function submitScans(rows, autoTag) {
  submitting.value = true
  try {
    const results = await Promise.allSettled(rows.map((row) =>
      request.post('/scans', {
        datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
        schema: row.name,
        database: currentDb.value || null,
        tables: null,
        forceFull: false,
        nullRules: [],
        autoTag
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
  confirmScans(targets)
}

function scanOne(row) {
  confirmScans([row])
}

onMounted(load)

// 页签切换走 keep-alive:失活时停轮询,回来时刷新统计并按需恢复
onActivated(() => {
  loadSchemaStats()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
/* 库过滤弹窗:全选行 + 勾选列表 */
.filter-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 12px;
}
.filter-all {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 8px;
}
.filter-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.filter-list {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow-y: auto;
}
</style>
