<template>
  <!-- 对象管理「图谱」页签:选中目录(含全部子目录)目录 + 挂载表 + 登记关系表的关系图。
       节点 = 目录(选中目录为锚点居中,主题 warning 色) + 挂载表(圆形,主题色) + 关系表(圆形,success 色区分);
       布局 = d3-force 力导向(知识图谱口径:目录大节点下挂小节点表。不预设坐标,仿真从零自动编排;
           节点尺寸分档 锚点目录 = 1.5 × 目录节点 > 表节点(挂载表与关系表同尺寸,仅颜色区分:关系表 success 色;两档默认 50/30 可在工具栏「调试」面板实时调节);边长分档 目录↔目录 S1=150 = 3 × 目录→表/表→关系表 S2=50,
           长边弱短边强、叶子斥力大,collide 按节点尺寸防重叠,节点可拖拽自动归位);
       边 = 挂载/归属边(父目录→子目录、目录→挂载表、挂载表→登记关系表,status=MOUNT 灰色实线) + ER 推导关系边
           (listRelations 按 (库,schema) 分组拉取,过滤两端都在目录表集合(含关系表)内的边);
       渲染复用表详情「图谱」页签的 TableGraphCanvas(圆形节点星型图,只读) -->
  <div class="odg-pane" v-loading="loading">
    <TableGraphCanvas
      v-if="dir && dirTables.length"
      ref="graphCanvasRef"
      :nodes="nodes"
      :edges="visibleEdges"
      :anchor-table="anchorTable"
      :levels="levels"
      :parent-of="parentOf"
      :sizes="sizes"
      layout="force"
      :colors="colors"
      :selected="selectedId"
      :highlight="highlight"
      :edge-width="edgeWidth"
      :label-opacity="labelOpacity"
      :center-strength="centerStrength"
      :charge-strength="chargeStrength"
      :link-strength="linkStrength"
      @node-click="onNodeClick"
      @node-open="goTable"
      @canvas-click="closePanel"
    >
      <template #toolbar>
        <el-tooltip content="图谱样式与力导参数" placement="bottom">
          <el-button size="small" :icon="Setting" @click="debugVisible = !debugVisible" />
        </el-tooltip>
      </template>
    </TableGraphCanvas>
    <!-- 调试面板(左下角悬浮卡片,与表详情「图谱」页签同款):边长/节点尺寸分档/力导三力 → 图数据重建、仿真重排;
         连线粗细/文本透明度为纯渲染口径 → 画布 repaint 原地刷样式;「重置」恢复默认并强制整体重绘一步到位,「刷新」按当前配置重绘(带动画) -->
    <div v-if="debugVisible" class="odg-debug">
      <div class="odg-debug-row">
        <span class="odg-debug-label">目录↔目录 S1</span>
        <el-input-number v-model="linkS1" :min="60" :max="600" :step="10" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">目录→表 S2</span>
        <el-input-number v-model="linkS2" :min="20" :max="300" :step="5" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">目录节点</span>
        <el-input-number v-model="dirNodeSize" :min="16" :max="160" :step="4" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">表节点</span>
        <el-input-number v-model="tableNodeSize" :min="12" :max="120" :step="2" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">连线粗细</span>
        <el-input-number v-model="edgeWidth" :min="0.5" :max="6" :step="0.2" :precision="1" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">文本透明度</span>
        <el-input-number v-model="labelOpacity" :min="0.1" :max="1" :step="0.05" :precision="2" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">图谱向心力</span>
        <el-input-number v-model="centerStrength" :min="0" :max="1" :step="0.05" :precision="2" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">节点排斥力</span>
        <el-input-number v-model="chargeStrength" :min="0" :max="3" :step="0.1" :precision="1" size="small" />
      </div>
      <div class="odg-debug-row">
        <span class="odg-debug-label">连线吸引力</span>
        <el-input-number v-model="linkStrength" :min="0" :max="2" :step="0.1" :precision="1" size="small" />
      </div>
      <div class="odg-debug-footer">
        <el-button size="small" @click="resetDebugParams">重置</el-button>
        <el-button size="small" :icon="Refresh" @click="redrawGraph">刷新</el-button>
      </div>
    </div>
    <el-empty v-else-if="!loading" :description="emptyText" :image-size="80" />
    <!-- 节点点击面板:右上角悬浮卡片(与表详情「图谱」页签节点面板同款)——
         表节点:注释标题 + 表名 + 字段列表(点开时懒拉)+「查看表详情」;目录节点:目录名 + 子树统计 -->
    <div v-if="panelTable || panelDir" class="graph-node-panel">
      <div class="graph-node-panel-head">
        <span class="graph-node-panel-title" :title="panelTitle">{{ panelTitle }}</span>
        <el-icon class="graph-node-panel-close" @click="closePanel"><Close /></el-icon>
      </div>
      <div class="graph-node-panel-sub" :title="panelSub">{{ panelSub }}</div>
      <template v-if="panelTable">
        <div v-loading="panelColumnsLoading" class="graph-node-panel-cols">
          <el-table v-if="panelColumns.length" :data="panelColumns" size="small" border max-height="220">
            <el-table-column prop="name" label="英文名" min-width="96" show-overflow-tooltip />
            <el-table-column prop="comment" label="中文名" min-width="84" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.comment">{{ row.comment }}</span>
                <span v-else style="color: var(--el-text-color-placeholder)">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="displayType" label="类型" min-width="76" show-overflow-tooltip />
          </el-table>
          <div v-else-if="!panelColumnsLoading" class="graph-node-panel-cols-empty">暂无字段元数据</div>
        </div>
        <div class="graph-node-panel-actions">
          <el-button size="small" @click="goTable(panelTable.tableName)">查看表详情</el-button>
        </div>
      </template>
      <div v-else-if="panelDir" class="graph-node-panel-dir">
        挂载表 {{ countDirTables(panelDir) }} 张 · 子目录 {{ (panelDir.children || []).length }} 个
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Close, Refresh, Setting } from '@element-plus/icons-vue'
import request, { listRelations } from '../api'
import TableGraphCanvas from './TableGraphCanvas.vue'

// 口径:目录子树挂载表 + 登记关系表之间已推导的 ER 关系(仅已确认边,候选关系不混入图谱);
//  表跨多个 (库,schema) 时按组分别拉关系再合并;节点以表名为键,跨 schema 同名表合并(与 RelationGraphCanvas 口径一致)
const props = defineProps({
  dsId: { type: [String, Number], required: true },
  // 选中目录(ObjectDirNode,可空);含子目录递归收集挂载表
  dir: { type: Object, default: null }
})

const router = useRouter()
const loading = ref(false)

// ---------- 目录表集合 ----------
/** 递归收集目录子树的挂载表 */
function collectTables(dir, out = []) {
  for (const t of dir?.tables || []) out.push(t)
  for (const c of dir?.children || []) collectTables(c, out)
  return out
}

const dirTables = computed(() => collectTables(props.dir))

// 登记关系表清单:[{ owner(挂载表), rel(关系表) }],关系表节点与挂载表→关系表边都由此出
const dirRels = computed(() => {
  const out = []
  for (const t of dirTables.value) {
    for (const r of t.relations || []) out.push({ owner: t, rel: r })
  }
  return out
})

// ---------- 目录节点(目录本身也算图节点:选中目录为锚点居中,子目录/挂载表环绕) ----------
/** 目录节点的图内 id(加前缀避免与表名撞键);显示名走 comment(画布标签取 comment || name) */
const dirNodeId = (d) => `dir:${d.id}`

// 目录子树拍平:[{ dir, parent }]
const dirList = computed(() => {
  const out = []
  const walk = (d, parent) => {
    if (!d) return
    out.push({ dir: d, parent })
    for (const c of d.children || []) walk(c, d)
  }
  walk(props.dir, null)
  return out
})

// 锚点 = 选中目录(星型图中心节点)
const anchorTable = computed(() => (props.dir ? dirNodeId(props.dir) : ''))

// 径向布局父子关系:子目录→父目录、挂载表→所在目录、登记关系表→所属挂载表
// (同名节点已被目录/挂载表占位时保留原父子关系,关系表多属主时取先遇到的)
const parentOf = computed(() => {
  const m = {}
  for (const { dir, parent } of dirList.value) {
    if (dir === props.dir) continue // 锚点自身无父级
    m[dirNodeId(dir)] = parent ? dirNodeId(parent) : anchorTable.value
    for (const t of dir.tables || []) {
      if (!(t.tableName in m)) m[t.tableName] = dirNodeId(dir)
    }
  }
  for (const { owner, rel } of dirRels.value) {
    if (!(rel.tableName in m)) m[rel.tableName] = owner.tableName
  }
  return m
})
// 节点层级(一圈一个层级):选中目录=0 居中;子目录=相对深度;挂载表=所在目录深度+1;登记关系表=挂载表层级+1
// (同名节点已是目录/挂载表时保留更内层级,关系表多属主时取先遇到的)
const levels = computed(() => {
  const m = {}
  const walk = (d, depth) => {
    if (!d) return
    m[dirNodeId(d)] = depth
    for (const t of d.tables || []) {
      if (m[t.tableName] == null) m[t.tableName] = depth + 1
    }
    for (const c of d.children || []) walk(c, depth + 1)
  }
  walk(props.dir, 0)
  for (const { owner, rel } of dirRels.value) {
    if (m[rel.tableName] == null) m[rel.tableName] = (m[owner.tableName] ?? 1) + 1
  }
  return m
})

// 力导节点尺寸分档(知识图谱口径:目录大节点下挂小节点表):锚点目录 = 1.5 × 目录节点 > 表节点
// (挂载表与登记关系表同尺寸,仅靠颜色区分;同名节点已被目录/挂载表占位时保留更大档,与节点去重口径一致;
//  目录/表节点尺寸由工具栏「调试」面板实时调节,默认值 50/30 → 锚点目录 75)
const DIR_SIZE_DEFAULT = 50
const TABLE_SIZE_DEFAULT = 30
const dirNodeSize = ref(DIR_SIZE_DEFAULT)
const tableNodeSize = ref(TABLE_SIZE_DEFAULT)
const sizes = computed(() => {
  const m = {}
  for (const { dir } of dirList.value) {
    m[dirNodeId(dir)] = dir === props.dir ? Math.round(dirNodeSize.value * 1.5) : dirNodeSize.value
  }
  for (const t of dirTables.value) {
    if (m[t.tableName] == null) m[t.tableName] = tableNodeSize.value
  }
  for (const { rel } of dirRels.value) {
    if (m[rel.tableName] == null) m[rel.tableName] = tableNodeSize.value
  }
  return m
})

// 图节点 = 目录节点 + 表节点(挂载表 + 登记关系表,按表名去重,comment 取已补齐的注释;同名时挂载表优先)
const nodes = computed(() => {
  const dirs = dirList.value.map(({ dir }) => ({ name: dirNodeId(dir), comment: dir.name }))
  const seen = new Map()
  for (const t of dirTables.value) {
    if (!seen.has(t.tableName)) seen.set(t.tableName, { name: t.tableName, comment: t.comment || '' })
  }
  for (const { rel } of dirRels.value) {
    if (!seen.has(rel.tableName)) seen.set(rel.tableName, { name: rel.tableName, comment: rel.comment || '' })
  }
  return [...dirs, ...seen.values()]
})

// 节点颜色:目录=主题 warning 色,关系表=success 色,挂载表默认主题色,三类区分
const colors = computed(() => {
  const cs = getComputedStyle(document.documentElement)
  const warn = cs.getPropertyValue('--el-color-warning').trim() || '#e6a23c'
  const ok = cs.getPropertyValue('--el-color-success').trim() || '#67c23a'
  const m = {}
  for (const { dir } of dirList.value) m[dirNodeId(dir)] = warn
  const mounted = new Set(dirTables.value.map((t) => t.tableName))
  for (const { rel } of dirRels.value) {
    if (!mounted.has(rel.tableName)) m[rel.tableName] = ok
  }
  return m
})

// 挂载/归属边:父目录→子目录、目录→挂载表、挂载表→登记关系表(status=MOUNT:画布按灰色实线渲染,与 ER 关系边区分)
// 力导边长分档(distance 由画布 link 力按边 id 回查,语义 = 可见连线长度/两圆边缘间距):
// 目录↔目录 S1 拉开簇间距,目录→挂载表/挂载表→关系表 S2;两档可由工具栏「调试」面板实时调节
const LINK_S1_DEFAULT = 150
const LINK_S2_DEFAULT = 50
const linkS1 = ref(LINK_S1_DEFAULT)
const linkS2 = ref(LINK_S2_DEFAULT)
const debugVisible = ref(false)
const graphCanvasRef = ref(null)
// 样式/力导参数(默认值 = 调试面板调定固化,不再取画布组件缺省——画布缺省 连线 1.4/向心 0.1/排斥 1/吸引 1
// 偏稀疏,图谱口径偏紧凑聚拢):
// 连线粗细(px,候选/MOUNT 边基准;确认边 +0.4)、文本透明度(0~1)、向心力(d3 forceX/forceY 强度 0~1)、
// 排斥力/吸引力倍率(乘在画布 manyBody/link strength 分档上)
const EDGE_WIDTH_DEFAULT = 1.0
const LABEL_OPACITY_DEFAULT = 1
const CENTER_STRENGTH_DEFAULT = 0.05
const CHARGE_STRENGTH_DEFAULT = 2.0
const LINK_STRENGTH_DEFAULT = 0.5
const edgeWidth = ref(EDGE_WIDTH_DEFAULT)
const labelOpacity = ref(LABEL_OPACITY_DEFAULT)
const centerStrength = ref(CENTER_STRENGTH_DEFAULT)
const chargeStrength = ref(CHARGE_STRENGTH_DEFAULT)
const linkStrength = ref(LINK_STRENGTH_DEFAULT)
/** 调试面板「重置」:全部参数恢复默认 + 强制整体重绘——redraw 与参数变更走同一条重建路径,
 *  一次 setData+render 到位(力导按默认配置重跑仿真),不做「先原地刷默认样式再重排」的两段式 */
function resetDebugParams() {
  linkS1.value = LINK_S1_DEFAULT
  linkS2.value = LINK_S2_DEFAULT
  dirNodeSize.value = DIR_SIZE_DEFAULT
  tableNodeSize.value = TABLE_SIZE_DEFAULT
  edgeWidth.value = EDGE_WIDTH_DEFAULT
  labelOpacity.value = LABEL_OPACITY_DEFAULT
  centerStrength.value = CENTER_STRENGTH_DEFAULT
  chargeStrength.value = CHARGE_STRENGTH_DEFAULT
  linkStrength.value = LINK_STRENGTH_DEFAULT
  graphCanvasRef.value?.redraw()
}
/** 调试面板「刷新」:参数不动,按当前配置强制整体重绘(力导重跑仿真,带动画) */
function redrawGraph() {
  graphCanvasRef.value?.redraw()
}
const mountEdges = computed(() => {
  const edges = []
  for (const { dir, parent } of dirList.value) {
    if (parent) {
      edges.push({ id: `mdir-${parent.id}-${dir.id}`, oneTable: dirNodeId(parent), manyTable: dirNodeId(dir), status: 'MOUNT', distance: linkS1.value })
    }
    for (const t of dir.tables || []) {
      edges.push({ id: `mt-${dir.id}-${t.id}`, oneTable: dirNodeId(dir), manyTable: t.tableName, status: 'MOUNT', distance: linkS2.value })
    }
  }
  for (const { owner, rel } of dirRels.value) {
    edges.push({ id: `rel-${owner.id}-${rel.id}`, oneTable: owner.tableName, manyTable: rel.tableName, status: 'MOUNT', distance: linkS2.value })
  }
  return edges
})
// 表名 → 表对象(挂载表 + 登记关系表,跳字段明细页路由参数/节点面板用;同名表取首个,挂载表优先)
const tableByName = computed(() => {
  const m = new Map()
  for (const t of dirTables.value) {
    if (!m.has(t.tableName)) m.set(t.tableName, t)
  }
  for (const { rel } of dirRels.value) {
    if (!m.has(rel.tableName)) m.set(rel.tableName, rel)
  }
  return m
})

// ---------- 关系数据 ----------
const allEdges = ref([])
// ER 推导边固定只显示已确认关系(候选关系去 ER 推导页处理,图谱不混入)
const visibleEdges = computed(() => [...mountEdges.value, ...allEdges.value.filter((e) => e.status === 'CONFIRMED')])

// 防陈旧响应:目录快速切换时旧请求后返回不覆盖新数据
let loadSeq = 0

/** 按 (库,schema) 分组拉关系,过滤两端表都在目录表集合(挂载表 + 登记关系表)内的边 */
async function loadRelations() {
  const seq = ++loadSeq
  const tables = [...dirTables.value, ...dirRels.value.map((x) => x.rel)]
  if (!props.dir || !tables.length) {
    allEdges.value = []
    return
  }
  loading.value = true
  try {
    const groups = new Map()
    for (const t of tables) {
      const key = `${t.dbName || ''}|${t.schemaName}`
      if (!groups.has(key)) groups.set(key, { dbName: t.dbName || '', schemaName: t.schemaName })
    }
    const nameSet = new Set(tables.map((t) => t.tableName))
    const lists = await Promise.all(
      [...groups.values()].map((g) =>
        listRelations({
          datasourceId: props.dsId,
          dbName: g.dbName || undefined,
          schemaName: g.schemaName
        }).catch(() => [])
      )
    )
    if (seq !== loadSeq) return
    const merged = []
    const seenIds = new Set()
    for (const list of lists) {
      for (const e of list || []) {
        if (e.id != null && seenIds.has(e.id)) continue
        if (e.id != null) seenIds.add(e.id)
        if (nameSet.has(e.oneTable) && nameSet.has(e.manyTable)) merged.push(e)
      }
    }
    allEdges.value = merged
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

// 目录/数据源变化(目录对象在整树重拉后引用必变)→ 重拉关系
watch(() => [props.dir, props.dsId], loadRelations, { immediate: true })

// ---------- 交互 ----------
const emptyText = computed(() => {
  if (!props.dir) return '请选择左侧目录'
  return '该目录暂无挂载表,先在「列表」页签挂载'
})

// ---------- 节点点击面板(选中态 + 右上角悬浮卡片,与表详情「图谱」页签同款) ----------
const selectedId = ref('') // 画布选中节点 id(深描边选中态)
const panelTable = ref(null) // 面板表节点(挂载表对象)
const panelDir = ref(null) // 面板目录节点(ObjectDirNode)
const panelColumns = ref([])
const panelColumnsLoading = ref(false)

// 选中高亮:选中节点 + 直接相连节点(挂载/归属边与 ER 边两端) + 沿归属层级(parentOf)从自身
// 一路到中心锚点路径上的所有节点 保持原色,其余节点/边降透明度
// (复用画布 highlight 筛选口径;空 = 无筛选全部正常显示)
const highlight = computed(() => {
  const id = selectedId.value
  if (!id) return null
  const set = new Set([id])
  for (const e of visibleEdges.value) {
    if (e.oneTable === id) set.add(e.manyTable)
    else if (e.manyTable === id) set.add(e.oneTable)
  }
  // 自身 → 中心锚点的路径(树结构沿父链唯一;set 判重防环)
  let p = parentOf.value[id]
  while (p && !set.has(p)) {
    set.add(p)
    p = parentOf.value[p]
  }
  return [...set]
})

const panelTitle = computed(() =>
  panelTable.value ? panelTable.value.comment || panelTable.value.tableName : panelDir.value?.name || '')
const panelSub = computed(() => (panelTable.value ? panelTable.value.tableName : '目录'))

/** 目录子树挂载表总数(与目录树节点计数同口径) */
function countDirTables(dir) {
  let n = (dir?.tables || []).length
  for (const c of dir?.children || []) n += countDirTables(c)
  return n
}

/** 单击节点:选中描边 + 开面板;表节点懒拉字段列表 */
function onNodeClick(id) {
  selectedId.value = id
  if (id.startsWith('dir:')) {
    panelDir.value = dirList.value.find((x) => dirNodeId(x.dir) === id)?.dir || null
    panelTable.value = null
    return
  }
  const t = tableByName.value.get(id)
  if (!t) return
  panelTable.value = t
  panelDir.value = null
  loadPanelColumns(t)
}

function closePanel() {
  selectedId.value = ''
  panelTable.value = null
  panelDir.value = null
}

/** 面板字段列表:按表拉元数据字段(与字段明细页同一接口);连点不同节点时只回填最后一次点击的表 */
async function loadPanelColumns(t) {
  panelColumns.value = []
  panelColumnsLoading.value = true
  try {
    const q = t.dbName ? `?db=${encodeURIComponent(t.dbName)}` : ''
    const cols = await request
      .get(`/datasources/${props.dsId}/schemas/${encodeURIComponent(t.schemaName)}/tables/${encodeURIComponent(t.tableName)}/columns${q}`)
      .catch(() => [])
    if (panelTable.value === t) panelColumns.value = cols || []
  } finally {
    if (panelTable.value === t) panelColumnsLoading.value = false
  }
}

// 切换目录/数据源:面板随图数据失效,关掉
watch(() => [props.dir, props.dsId], closePanel)

/** 双击节点表名:跳该表字段明细页(同表列表页跳 TableDetail 的路由参数形态) */
function goTable(table) {
  const t = tableByName.value.get(table)
  if (!t) return
  const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(t.schemaName)}/tables/${encodeURIComponent(t.tableName)}`
  router.push(t.dbName ? `${base}?db=${encodeURIComponent(t.dbName)}` : base)
}
</script>

<style scoped>
/* 吃满页签内容区高度(父级 .om-tab-pane 保证),画布容器需要显式高度 */
.odg-pane {
  height: 100%;
  min-height: 0;
  position: relative;
}
/* 节点点击面板(画布右上角悬浮卡片,与表详情「图谱」页签 node-panel 同款);
   半透明毛玻璃底与工具栏缩放控制条同口径(55% 底色 + 12px 背景模糊),图元素压到面板下不挡阅读 */
.graph-node-panel {
  position: absolute;
  /* 顶部工具栏(约 44px)之下,避免压住右侧缩放控制条 */
  top: 52px;
  right: 12px;
  width: 360px;
  background: color-mix(in srgb, var(--el-bg-color) 55%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
  padding: 12px;
  z-index: 10;
}
/* 面板内字段表格去底色:el-table 默认白底会盖住毛玻璃,表格各行/表头/单元格全部透明 */
.graph-node-panel :deep(.el-table),
.graph-node-panel :deep(.el-table__inner-wrapper),
.graph-node-panel :deep(.el-table tr),
.graph-node-panel :deep(.el-table th.el-table__cell),
.graph-node-panel :deep(.el-table td.el-table__cell) {
  background: transparent;
}
/* 透明底下默认边框色对比度不够,边框/表头文字各加深一档 */
.graph-node-panel :deep(.el-table) {
  --el-table-border-color: var(--el-border-color-darker);
  --el-table-header-text-color: var(--el-text-color-primary);
}
.graph-node-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
/* 标题(中文注释优先)完整显示:长文本自动换行,不截断 */
.graph-node-panel-title {
  font-weight: 600;
  word-break: break-all;
  line-height: 1.4;
}
.graph-node-panel-close {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  margin-top: 2px;
}
.graph-node-panel-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
/* 字段列表区:固定最小高度承载 loading,空数据居中提示;单元格 padding 收紧让三列更紧凑 */
.graph-node-panel-cols {
  margin-top: 10px;
  min-height: 40px;
}
.graph-node-panel-cols :deep(.el-table .el-table__cell) {
  padding: 4px 0;
}
.graph-node-panel-cols :deep(.el-table .cell) {
  padding: 0 6px;
  line-height: 1.4;
}
.graph-node-panel-cols-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: center;
  padding: 12px 0;
}
/* 目录节点面板内容:子树统计一行 */
.graph-node-panel-dir {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.graph-node-panel-actions {
  margin-top: 10px;
}
/* 调试面板(左下角悬浮卡片,毛玻璃底与节点面板同口径):力导边长/尺寸分档 + 样式/力导参数 */
.odg-debug {
  position: absolute;
  left: 12px;
  bottom: 12px;
  /* 按内容收缩(行 = 标签 96 + 间距 10 + 小号数字框 120),不写死宽度防右侧留白 */
  width: max-content;
  background: color-mix(in srgb, var(--el-bg-color) 55%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
  padding: 10px 12px;
  z-index: 10;
}
.odg-debug-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.odg-debug-row + .odg-debug-row {
  margin-top: 4px;
}
.odg-debug-label {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  width: 96px;
}
.odg-debug-footer {
  margin-top: 8px;
  /* 相邻 el-button 的默认 12px 左 margin 去掉,用 gap 控制「重置/刷新」间距 */
  display: flex;
  gap: 8px;
}
.odg-debug-footer .el-button + .el-button {
  margin-left: 0;
}
</style>
