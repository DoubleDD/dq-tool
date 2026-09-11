<template>
  <div class="page-card rg-page">
    <div class="toolbar">
      <h3 style="margin: 0">ER 关系</h3>
      <div class="toolbar-actions">
        <el-select v-model="dsId" filterable placeholder="选择数据源" style="width: 200px" :loading="dsLoading" @change="onDsChange">
          <el-option v-for="ds in datasources" :key="ds.id" :value="String(ds.id)" :label="ds.name">
            <div class="ds-option">
              <DbTypeIcon :type="ds.dbType" :size="14" />
              <span>{{ ds.name }}</span>
            </div>
          </el-option>
        </el-select>
        <!-- 多库方言(SQL Server/Kingbase)先选数据库再选 schema,与库列表页同口径 -->
        <el-select v-if="isMultiDb" v-model="db" filterable placeholder="数据库" style="width: 160px"
                   :loading="dbLoading" :disabled="!dsId" @change="onDbChange">
          <el-option v-for="d in databases" :key="d" :value="d" :label="d" />
        </el-select>
        <el-select v-model="schema" filterable placeholder="选择库/schema" style="width: 180px"
                   :loading="schemaLoading" :disabled="!dsId" @change="onSchemaChange">
          <el-option v-for="s in schemas" :key="s" :value="s" :label="s" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" :disabled="!dsId || !schema" @click="loadGraph">刷新</el-button>
        <el-button :disabled="!dsId || !schema" @click="addVisible = true">手动补充</el-button>
        <!-- 候选管理:整库关系列表批量确认/否决/删除(图上逐条点边操作慢,批量场景走这里) -->
        <el-button :disabled="!dsId || !schema" @click="batchVisible = true">关系管理</el-button>
        <!-- 导出 drawio 已收进画布顶部工具栏(底座可选工具 export-drawio,第 5 个图标位) -->
        <el-button :icon="Download" :loading="exporting" :disabled="!dsId || !schema || !graphData.nodes.length" @click="exportExcel">导出ER关系</el-button>
        <!-- 星型图标识:query 带 table 时只看该表的一度关系 -->
        <template v-if="starTable">
          <el-tag size="small" type="primary" effect="plain">星型图:{{ starTable }}</el-tag>
          <el-button link type="primary" size="small" @click="backToFullGraph">返回全库总图</el-button>
        </template>
      </div>
    </div>

    <!-- 显示档位/连线线型已收进画布顶部工具栏(底座内置默认工具,v-model 双向同步) -->

    <el-alert v-if="graphLoaded && graphData.nodes.length" type="success" :closable="false" class="stats-row">
      <span>共 {{ stats.tables }} 张表</span>
      <span>{{ stats.confirmed }} 条确认关系</span>
      <span>{{ stats.candidate }} 条候选</span>
      <span>{{ stats.orphan }} 张孤儿表</span>
    </el-alert>

    <!-- 画布区:flex:1 吃满页面剩余高度(stats-row 出现/消失自动伸缩,页脚高度已计入 100% 口径) -->
    <div class="canvas-wrap" v-loading="loading">
      <RelationGraphCanvas
        ref="canvasRef"
        v-if="dsId && schema && graphData.nodes.length"
        v-model:level="level"
        v-model:edge-type="edgeType"
        :nodes="graphData.nodes"
        :edges="graphData.edges"
        :anchor-table="starTable"
        :columns-map="columnsMap"
        :field-name-mode="fieldNameMode"
        @export-drawio="exportDrawio"
        @edge-click="onEdgeClick"
        @node-click="onNodeClick"
        @node-open="goTableDetail"
        @changed="onCanvasBatchReject"
      >
        <template #toolbar>
          <!-- 名字口径三档(表名/字段同规则):仅中文(默认,无注释回退英文)/仅英文/中英文同时显示(中文在前) -->
          <el-select v-model="fieldNameMode" size="small" style="width: 150px">
            <el-option label="仅显示字段中文名" value="chinese" />
            <el-option label="仅显示字段英文名" value="english" />
            <el-option label="中英文同时显示" value="both" />
          </el-select>
        </template>
      </RelationGraphCanvas>
      <el-empty v-else-if="!loading" :description="emptyText" :image-size="80" />

      <!-- 节点点击面板:右上角悬浮卡片(点节点表名已直接跳字段明细,此处承载其余操作) -->
      <div v-if="nodePanelTable" class="node-panel">
        <div class="node-panel-head">
          <span class="node-panel-title">{{ nodePanelTable }}</span>
          <el-icon class="node-panel-close" @click="nodePanelTable = ''"><Close /></el-icon>
        </div>
        <div v-if="nodePanelComment" class="node-panel-comment">{{ nodePanelComment }}</div>
        <div class="node-panel-actions">
          <el-button size="small" @click="goTableDetail(nodePanelTable)">查看字段明细</el-button>
          <el-button size="small" @click="viewStar(nodePanelTable)">星型图</el-button>
          <el-button size="small" type="primary" @click="openInfer(nodePanelTable)">以此为锚点推导</el-button>
        </div>
      </div>
    </div>

    <!-- 关系详情抽屉:点边打开(共用组件);确认/否决/删除后刷新图 -->
    <RelationEdgeDrawer v-model="drawerVisible" :edge="currentEdge" @changed="onEdgeChanged" />

    <!-- 手动补充对话框(表清单取当前图节点;保存后刷新图) -->
    <RelationAddDialog
      v-if="dsId && schema"
      v-model="addVisible"
      :ds-id="dsId"
      :db="db"
      :schema="schema"
      :tables="graphData.nodes.map((n) => n.name)"
      @done="onAddDone"
    />

    <!-- 关系批量处理对话框(整库清单,默认仅候选;批量确认/否决/删除后刷新图;星型图预填当前表过滤) -->
    <RelationBatchDialog
      v-if="dsId && schema"
      v-model="batchVisible"
      :ds-id="dsId"
      :db="db"
      :schema="schema"
      :initial-table="starTable"
      @done="loadGraph"
    />

    <!-- 推导对话框(预选当前数据源/库/被点表) -->
    <RelationInferDialog
      v-if="dsId && schema"
      v-model="inferVisible"
      :ds-id="dsId"
      :db="db"
      :schema="schema"
      :table-name="inferTable"
      @done="onInferDone"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from '../utils/notify'
import request, { getRelationGraph } from '../api'
import { Close, Download, Refresh } from '@element-plus/icons-vue'
import { downloadDrawio } from '../utils/drawioExport'
import { exportErGraphExcel } from '../utils/erGraphExport'
import RelationGraphCanvas from '../components/RelationGraphCanvas.vue'
import RelationEdgeDrawer from '../components/RelationEdgeDrawer.vue'
import RelationAddDialog from '../components/RelationAddDialog.vue'
import RelationBatchDialog from '../components/RelationBatchDialog.vue'
import RelationInferDialog from '../components/RelationInferDialog.vue'
import DbTypeIcon from '../components/DbTypeIcon.vue'

// ER 图页:默认全库总图(仅确认边+孤儿表);query 带 table 时该表星型图(恒含候选边)。
// 路由 query 驱动(datasourceId/dbName/schemaName/table):切换数据源/库即 push query 重挂载,
// 与本应用 keep-alive 以 fullPath 为 key 的口径一致
const route = useRoute()
const router = useRouter()

// ---------- 选择器(初值取自 query) ----------
const datasources = ref([])
const dsLoading = ref(false)
const dsId = ref(route.query.datasourceId ? String(route.query.datasourceId) : '')
const db = ref(route.query.dbName || '')
const schema = ref(route.query.schemaName || '')
const starTable = ref(route.query.table || '')
const databases = ref([])
const schemas = ref([])
const dbLoading = ref(false)
const schemaLoading = ref(false)

const currentDs = computed(() => datasources.value.find((d) => String(d.id) === dsId.value))
const isMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(currentDs.value?.dbType))

// ---------- 图数据与显示选项(档位不持久化) ----------
const graphData = ref({ nodes: [], edges: [] })
const loading = ref(false)
const graphLoaded = ref(false)
const level = ref('name')
// 连线线型:curve 曲线 / orth 直角 / orth-round 圆角;localStorage 持久化(与字段明细页 ER 页签同 key 共享)
const edgeType = ref(localStorage.getItem('dq-er-edge-type') || 'curve')
watch(edgeType, (v) => localStorage.setItem('dq-er-edge-type', v))
// level=all 时各表字段清单(表名 -> [{name, type, comment}]),按库缓存一次(comment 供画布字段行展示)
const columnsMap = ref({})
let columnsKey = ''
// 名字口径三档(表名/字段同规则):chinese=仅中文(默认,无注释回退英文)/english=仅英文/both=中英文同时显示(中文在前);
// 与对象管理图同名开关同语义,不持久化
const fieldNameMode = ref('chinese')

const stats = computed(() => {
  const nodes = graphData.value.nodes
  const edges = graphData.value.edges
  const linked = new Set()
  let confirmed = 0
  let candidate = 0
  for (const e of edges) {
    linked.add(e.oneTable)
    linked.add(e.manyTable)
    if (e.status === 'CONFIRMED') confirmed++
    else if (e.status === 'CANDIDATE') candidate++
  }
  return { tables: nodes.length, confirmed, candidate, orphan: nodes.filter((n) => !linked.has(n.name)).length }
})

const emptyText = computed(() => {
  if (!dsId.value) return '请选择数据源'
  if (!schema.value) return '请选择库/schema'
  return '暂无数据:该库没有表结构缓存,也没有已推导的关系'
})

// ---------- 数据源/库清单 ----------
async function loadDatasources() {
  dsLoading.value = true
  try {
    datasources.value = await request.get('/datasources')
  } finally {
    dsLoading.value = false
  }
}

async function fetchDatabases(id) {
  return await request.get(`/datasources/${id}/databases`).catch(() => [])
}

async function fetchSchemas(id, dbName) {
  const q = dbName ? `?db=${encodeURIComponent(dbName)}` : ''
  return await request.get(`/datasources/${id}/schemas${q}`).catch(() => [])
}

/** 刷新库/schema 下拉清单(不改变当前选中值;选中值不在清单内时 el-select 原样显示) */
async function reloadSchemaOptions() {
  if (!dsId.value) return
  if (isMultiDb.value) {
    dbLoading.value = true
    databases.value = await fetchDatabases(dsId.value)
    dbLoading.value = false
  }
  schemaLoading.value = true
  schemas.value = await fetchSchemas(dsId.value, isMultiDb.value ? db.value : '')
  schemaLoading.value = false
}

/** query 变化即重挂载(fullPath 为 keep-alive key);与当前相同则不推,避免多余重载 */
function pushQuery(patch) {
  const q = {}
  const merged = { datasourceId: dsId.value, dbName: db.value, schemaName: schema.value, ...patch }
  for (const [k, v] of Object.entries(merged)) {
    if (v !== '' && v !== null && v !== undefined) q[k] = v
  }
  const cur = route.query
  const same = ['datasourceId', 'dbName', 'schemaName', 'table'].every((k) => String(cur[k] || '') === String(q[k] || ''))
  if (!same) router.push({ path: '/relations', query: q })
}

async function onDsChange(id) {
  const ds = datasources.value.find((d) => String(d.id) === String(id))
  let dbName = ''
  let schemaName = ''
  if (['SQLSERVER', 'KINGBASE'].includes(ds?.dbType)) {
    const dbs = await fetchDatabases(id)
    dbName = dbs[0] || ''
    const ss = dbName ? await fetchSchemas(id, dbName) : []
    schemaName = ss[0] || ''
  } else {
    const ss = await fetchSchemas(id, '')
    schemaName = ss[0] || ''
  }
  pushQuery({ datasourceId: id, dbName, schemaName, table: '' })
}

async function onDbChange(dbName) {
  const ss = await fetchSchemas(dsId.value, dbName)
  pushQuery({ dbName, schemaName: ss[0] || '', table: '' })
}

function onSchemaChange(s) {
  pushQuery({ schemaName: s, table: '' })
}

function backToFullGraph() {
  pushQuery({ table: '' })
}

/** 节点面板「星型图」:query 带 table 重挂载为星型图 */
function viewStar(table) {
  nodePanelTable.value = ''
  pushQuery({ table })
}

// ---------- 图数据 ----------
async function loadGraph() {
  if (!dsId.value || !schema.value) return
  loading.value = true
  try {
    graphData.value = await getRelationGraph({
      datasourceId: dsId.value,
      dbName: db.value || undefined,
      schemaName: schema.value,
      table: starTable.value || undefined,
      includeCandidate: !!starTable.value // 全库总图只显示已确认关系;星型图恒含候选边
    })
    graphLoaded.value = true
  } finally {
    loading.value = false
  }
}

// ---------- 导出 drawio ----------
// 画布实例引用(exportData 取 G6 实测布局中心与当前档位字段行)
const canvasRef = ref(null)

/** 导出 .drawio:画布当前档位/口径所见即所得,前端拼装 mxfile XML 下载(浏览器/Tauri 双端) */
function exportDrawio() {
  const data = canvasRef.value?.exportData()
  if (!data || !data.nodes.length) return ElMessage.warning('当前图没有可导出的节点')
  downloadDrawio(data, starTable.value ? `ER 星型图-${schema.value}-${starTable.value}` : `ER 总图-${schema.value}`)
}

// ---------- 导出 ER 关系 Excel ----------
const exporting = ref(false)

/** 导出 ER 关系 Excel:当前图(全库总图/星型图)口径的表清单,逻辑见 erGraphExport.js(与字段明细页「ER 关系」页签共用) */
async function exportExcel() {
  if (!graphData.value.nodes.length) return
  exporting.value = true
  try {
    await loadColumnsMap()
    await exportErGraphExcel({
      dsId: dsId.value,
      db: db.value,
      schema: schema.value,
      filename: starTable.value ? `ER 星型图-${schema.value}-${starTable.value}` : `ER 总图-${schema.value}`,
      graph: graphData.value,
      columnsMap: columnsMap.value
    })
  } finally {
    exporting.value = false
  }
}

/** level=all 时拉整库字段清单并按表分组(同一库只拉一次) */
async function loadColumnsMap() {
  const key = `${dsId.value}${db.value}${schema.value}`
  if (columnsKey === key) return
  columnsKey = key
  columnsMap.value = {}
  const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
  const list = await request
    .get(`/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/columns${q}`)
    .catch(() => [])
  const map = {}
  for (const c of list || []) {
    if (!c || !c.table || !c.name) continue
    if (!map[c.table]) map[c.table] = []
    map[c.table].push({ name: c.name, type: c.type || '', comment: c.comment || '' })
  }
  columnsMap.value = map
}

watch(level, (v) => {
  if (v === 'all') loadColumnsMap()
})

// ---------- 点边:关系详情抽屉(共用组件承载三态操作) ----------
const drawerVisible = ref(false)
const currentEdge = ref(null)

function onEdgeClick(rel) {
  currentEdge.value = rel
  drawerVisible.value = true
}

/** 抽屉内操作成功:确认就地更新状态标签并整图回源刷新;
 *  否决/删除的边不再进图——先更新内存中的关系数据(剔除该边,统计条随 computed 联动;
 *  星型图节点口径=锚点+边两端,失去全部连线的邻表节点一并摘除;全库总图节点含孤儿表不摘,
 *  与服务端 graph() 口径一致),画布 watch 到变化后整体重绘(不回源重拉) */
function onEdgeChanged({ action }) {
  if (action === 'confirm') {
    if (currentEdge.value) currentEdge.value = { ...currentEdge.value, status: 'CONFIRMED' }
    loadGraph()
    return
  }
  const id = currentEdge.value?.id
  if (id == null) return
  const edges = (graphData.value.edges || []).filter((e) => e.id !== id)
  let nodes = graphData.value.nodes || []
  if (starTable.value) {
    const keep = new Set([starTable.value])
    for (const e of edges) { keep.add(e.oneTable); keep.add(e.manyTable) }
    nodes = nodes.filter((n) => keep.has(n.name))
  }
  graphData.value = { ...graphData.value, nodes, edges }
}

/** 画布选中批量否决成功:与单条否决同口径——按 ids 剔除这些边(不回源重拉);
 *  星型图同步摘除失去全部连线的邻表节点;全库总图节点含孤儿表不摘(与服务端 graph() 口径一致) */
function onCanvasBatchReject({ ids = [] } = {}) {
  if (!ids.length) return
  const idSet = new Set(ids.map(String))
  const edges = (graphData.value.edges || []).filter((e) => !idSet.has(String(e.id)))
  let nodes = graphData.value.nodes || []
  if (starTable.value) {
    const keep = new Set([starTable.value])
    for (const e of edges) { keep.add(e.oneTable); keep.add(e.manyTable) }
    nodes = nodes.filter((n) => keep.has(n.name))
  }
  graphData.value = { ...graphData.value, nodes, edges }
}

// ---------- 手动补充 ----------
const addVisible = ref(false)

// ---------- 候选管理(关系批量处理) ----------
const batchVisible = ref(false)

/** 手动补充完成:命中唯一键的已存在关系后端转 CONFIRMED 返回原 id(existing=true) */
async function onAddDone(res) {
  if (res?.existing) ElMessage.success('该关系已存在,已转为确认')
  else ElMessage.success('已添加确认关系')
  await loadGraph()
}

// ---------- 点节点:面板(推导入口);点表名:跳字段明细新页签 ----------
const nodePanelTable = ref('')
const nodePanelComment = computed(() => graphData.value.nodes.find((n) => n.name === nodePanelTable.value)?.comment || '')

function onNodeClick(table) {
  nodePanelTable.value = table
}

/** 跳字段明细页(同表列表页跳 TableDetail 的路由参数形态;未扫描不带 jobId) */
function goTableDetail(table) {
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables/${encodeURIComponent(table)}`
  router.push(db.value ? `${base}?db=${encodeURIComponent(db.value)}` : base)
}

// ---------- 推导对话框 ----------
const inferVisible = ref(false)
const inferTable = ref('')

function openInfer(table) {
  nodePanelTable.value = ''
  inferTable.value = table
  inferVisible.value = true
}

/** 推导完成:刷新图(全库总图只显示确认边,发现的候选关系在星型图可见) */
async function onInferDone(job) {
  ElMessage.success(`推导完成,发现 ${job.foundCount} 条候选关系`)
  await loadGraph()
}

onMounted(async () => {
  await loadDatasources()
  if (dsId.value) {
    await reloadSchemaOptions()
    await loadGraph()
    if (level.value === 'all') loadColumnsMap()
  }
})

</script>

<style scoped>
.rg-page {
  display: flex;
  flex-direction: column;
  /* 铺满 .main 可视高度:100% 相对 el-main(flex:1,有确定高度)解析,只减 page-card 自身上下 margin(20+20);
     授权页脚/页签栏高度天然计入,不再溢出产生滚动条(同 Logs/SqlConsole 口径) */
  height: calc(100% - 40px);
  box-sizing: border-box;
  overflow: hidden;
}
/* 工具栏按钮组:flex + gap 统一间距,并清掉 el-button 相邻默认 margin(与各列表页同写法) */
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}
.ds-option {
  display: flex;
  align-items: center;
  gap: 6px;
}
.stats-row {
  margin-bottom: 12px;
}
.stats-row span {
  margin-right: 24px;
}
.canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 360px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  overflow: hidden;
}
/* 节点点击面板:画布右上角悬浮卡片(顶部工具栏之下,避免压住右侧缩放控制条) */
.node-panel {
  position: absolute;
  top: 52px;
  right: 12px;
  width: 260px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
  padding: 12px;
  z-index: 10;
}
.node-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.node-panel-title {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-panel-close {
  cursor: pointer;
  color: var(--el-text-color-secondary);
}
.node-panel-comment {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.node-panel-actions {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.node-panel-actions :deep(.el-button) {
  margin-left: 0;
}
</style>
