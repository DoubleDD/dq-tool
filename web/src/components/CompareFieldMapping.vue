<script setup>
/**
 * 字段映射(新建比对任务第四步):用 ER 关系图那套画布(`RelationGraphCanvas` 的 mapping 模式)人工连线,
 * 确定「基准表字段 ↔ 各对比表字段」的对应关系。复用的能力:
 * - 表节点 = 同 ER 图一致的表卡片(标题 + 字段行 + 类型 + 注释,**字段全量展开**、不做「+N 个字段」折叠),
 *   锚点(基准表)主题色高亮;
 * - 边 = 字段对齐曲线(端点精确对齐到字段行),已连字段行按主题色加粗,一眼看出连了哪些;
 * - 布局 = mapping-row:基准表锚定左侧,各对比表在右侧一行水平等距排开、卡片中心同线,可用滚轮/工具栏缩放、
 *   拖动画布,节点可拖动 —— 表多时靠画布缩放/平移而不是让某一列单独滚动(这正是 ER 图的操作方式);
 * - 交互:走 G6 内置 `create-edge`(trigger=click)——点一侧字段行(节点)再点另一侧即连线,
 *   画布自带橡皮筋辅助边;点连线本身删除;工具条可按名称自动匹配/清空。
 *
 * v-model = 数组(与 targets 同序),元素为 { 基准字段名: 目标列名 };未映射的基准字段比对时按「列缺失」计。
 */
import { computed, nextTick, ref, watch } from 'vue'
import request from '../api'
import RelationGraphCanvas from './RelationGraphCanvas.vue'

const props = defineProps({
  // { datasourceId, db, schema, table }
  base: { type: Object, required: true },
  baseLabel: { type: String, default: '' },
  // [{ datasourceId, db, schema, table, label }]
  targets: { type: Array, default: () => [] },
  // 比对主键(基准表列名):每个对比表都必须连上它,否则无法按主键对齐
  keyField: { type: String, default: '' },
  // 向导第二步勾选的基准字段(基准表里参与比对的字段):传给画布的 highlightColumns 接口——
  // 曾做常亮底色高亮,用户反馈干扰观看已移除,仅保留传参(提交时后端仍会校验参与字段)
  baseColumns: { type: Array, default: () => [] },
  // [{ name, comment }] 数组(与 targets 同序)
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

// 图节点 id 用「数据源|库|模式|表」保证唯一(同一个表名可能来自不同数据源),标题文案另用 label
const baseKey = computed(() => nodeKey(props.base))
const targetKeys = computed(() => props.targets.map((t) => nodeKey(t)))
const baseReady = computed(() => !!(props.base?.datasourceId && props.base?.schema && props.base?.table))

const baseFields = ref([])
const targetFields = ref([])
// 表中文注释(画布标题与映射管理弹窗「中文名加粗第一行、英文名第二行」用):字段接口不含表注释,
// 按 schema 分组拉表清单取(同库多目标共享一次请求)
const baseTableComment = ref('')
const targetTableComments = ref([])
const loading = ref(false)
// 字段加载完成前不挂载画布(节点高度依赖字段行数,先建后补会让坐标与高度对不上)
const loadedOnce = ref(false)
const canvasReady = computed(() => loadedOnce.value && baseReady.value && baseFields.value.length > 0
  && targetFields.value.length === props.targets.length && targetFields.value.every((list) => list && list.length >= 0))
// 画布 ref(刷新用)
const canvasRef = ref(null)

function nodeKey(spec) {
  return [spec?.datasourceId, spec?.db || '', spec?.schema || '', spec?.table || ''].join('|')
}

function columnsUrl(spec) {
  const q = spec.db ? `?db=${encodeURIComponent(spec.db)}` : ''
  return `/datasources/${spec.datasourceId}/schemas/${encodeURIComponent(spec.schema)}/tables/${encodeURIComponent(spec.table)}/columns${q}`
}

function tablesUrl(spec) {
  const q = spec.db ? `?db=${encodeURIComponent(spec.db)}` : ''
  return `/datasources/${spec.datasourceId}/schemas/${encodeURIComponent(spec.schema)}/tables${q}`
}

/** 按名称忽略大小写自动匹配:与后端「无映射时」的默认口径一致,给用户一个可改的起点 */
function autoMatchFor(ti) {
  const byLower = new Map((targetFields.value[ti] || []).map((c) => [String(c.name).toLowerCase(), c.name]))
  const out = {}
  for (const bf of baseFields.value) {
    const hit = byLower.get(String(bf.name).toLowerCase())
    if (hit) out[bf.name] = hit
  }
  return out
}

/** 拉基准表与各对比表字段 + 各表中文注释(表清单接口,按 schema 分组共享请求);基准表未选全不发请求(第四步用 v-show,向导第一步时组件已挂载) */
async function loadColumns() {
  if (!baseReady.value) {
    baseFields.value = []
    targetFields.value = []
    return
  }
  loading.value = true
  try {
    const specs = [props.base, ...props.targets]
    // schema 去重:同一数据源+库+模式下多张表共享一次表清单请求
    const schemaKeys = []
    const schemaSpecs = []
    const seen = new Set()
    for (const s of specs) {
      const k = [s.datasourceId, s.db || '', s.schema].join('|')
      if (!seen.has(k)) {
        seen.add(k)
        schemaKeys.push(k)
        schemaSpecs.push(s)
      }
    }
    const [columns, tablesLists] = await Promise.all([
      Promise.all(specs.map((s) => request.get(columnsUrl(s)).catch(() => []))),
      Promise.all(schemaSpecs.map((s) => request.get(tablesUrl(s)).catch(() => [])))
    ])
    const commentOf = {}
    schemaKeys.forEach((k, i) => {
      for (const t of tablesLists[i] || []) commentOf[`${k}|${t.name}`] = t.comment || ''
    })
    const [base, ...rest] = columns
    baseFields.value = base || []
    targetFields.value = rest.map((r) => r || [])
    // 注意 commentOf 键 = schema 键 + 表名,恰与 nodeKey(数据源|库|模式|表) 同构,直接查别再拼表名
    baseTableComment.value = commentOf[nodeKey(props.base)] || ''
    targetTableComments.value = props.targets.map((t) => commentOf[nodeKey(t)] || '')
    loadedOnce.value = true
  } finally {
    loading.value = false
  }
}

// ---------- 图数据 ----------

/** 当前映射(以 v-model 为准,缺失按空对象处理) */
function mappingOf(ti) {
  return props.modelValue?.[ti] || {}
}

/** 该字段名在哪个对比表里被连过(反向索引,用于连线判重) */
function targetIndexOf(key) {
  return targetKeys.value.indexOf(key)
}

const nodes = computed(() => {
  // 显式依赖字段清单:字段加载完成后节点高度才定型,触发画布重建并按最终高度重新布局(列才会精确居中)
  void baseFields.value.length
  void targetFields.value.length
  void baseTableComment.value
  void targetTableComments.value.length
  if (!baseReady.value) return []
  const base = {
    name: baseKey.value,
    label: `基准表 · ${props.baseLabel || props.base.table}`,
    comment: props.base.table,
    tableComment: baseTableComment.value
  }
  const rest = props.targets.map((t, i) => ({
    name: targetKeys.value[i],
    label: t.label || t.table,
    comment: t.table,
    tableComment: targetTableComments.value[i] || ''
  }))
  return [base, ...rest]
})

const columnsMap = computed(() => {
  const map = {}
  map[baseKey.value] = (baseFields.value || []).map((c) => ({ name: c.name, type: c.displayType || c.typeName || '', comment: c.comment || '' }))
  targetKeys.value.forEach((k, i) => {
    map[k] = (targetFields.value[i] || []).map((c) => ({ name: c.name, type: c.displayType || c.typeName || '', comment: c.comment || '' }))
  })
  return map
})

/** 映射 → 图边(one=基准表字段,many=对比表字段;基数恒 1:1,状态恒 CONFIRMED 走实线主题色) */
const edges = computed(() => {
  const list = []
  props.targets.forEach((t, ti) => {
    const key = targetKeys.value[ti]
    for (const [bf, tc] of Object.entries(mappingOf(ti))) {
      list.push({
        id: `map:${key}:${bf}`,
        oneTable: baseKey.value,
        oneColumn: bf,
        manyTable: key,
        manyColumn: tc,
        cardinality: 'ONE_TO_ONE',
        status: 'CONFIRMED'
      })
    }
  })
  return list
})

// ---------- 连线增删 ----------

/** 连线:基准字段 ↔ 目标列。同一对比表内一对一(一个基准字段只连一个目标列、一个目标列只被连一次) */
function connect(ti, baseField, targetCol) {
  const next = props.targets.map((t, i) => ({ ...mappingOf(i) }))
  for (const [bf, tc] of Object.entries(next[ti])) {
    if (tc === targetCol && bf !== baseField) delete next[ti][bf]
  }
  next[ti][baseField] = targetCol
  emit('update:modelValue', next)
}

function removeEdge(edge) {
  const ti = targetIndexOf(edge.manyTable)
  if (ti < 0) return
  const next = props.targets.map((t, i) => ({ ...mappingOf(i) }))
  delete next[ti][edge.oneColumn]
  emit('update:modelValue', next)
}

/** create-edge 新建边完成(画布已按字段行记录两端列名):落状态,画布随即按状态重建出正式边 */
function onMappingConnect({ manyTable, oneColumn, manyColumn }) {
  const ti = targetIndexOf(manyTable)
  if (ti < 0 || !oneColumn || !manyColumn) return
  connect(ti, oneColumn, manyColumn)
}

function onEdgeClick(edge) {
  removeEdge(edge)
}

function autoMatchAll() {
  emit('update:modelValue', props.targets.map((t, ti) => autoMatchFor(ti)))
}

function clearAll() {
  emit('update:modelValue', props.targets.map(() => ({})))
}

// ---------- 字段映射管理(清单 + 筛选 + 单删/批删;参考 ER 图「关系管理」的交互) ----------

const manageVisible = ref(false)
const manageTargetFilter = ref(-1)
const manageKeyword = ref('')
const manageSelection = ref([])
const manageTableRef = ref(null)

/** 全量映射清单(与 targets 同序、对象键序即连线先后顺序);dsName/表注释/定位串供首列「数据源名+表名/换行英文名」 */
const manageRows = computed(() => {
  const rows = []
  props.targets.forEach((t, ti) => {
    for (const [bf, tc] of Object.entries(mappingOf(ti))) {
      rows.push({
        key: `${ti}:${bf}`,
        ti,
        baseField: bf,
        targetCol: tc,
        targetLabel: t.label || t.table,
        dsName: t.dsName || '',
        tableComment: targetTableComments.value[ti] || '',
        // 英文定位串(库.模式.表):副行专用——label 里带数据源名前缀,直接展示会把数据源名重复一遍
        qualifiedName: `${t.db ? `${t.db}.` : ''}${t.schema}.${t.table}`
      })
    }
  })
  return rows
})

const filteredManageRows = computed(() => {
  const kw = manageKeyword.value.trim().toLowerCase()
  return manageRows.value.filter((r) => {
    if (manageTargetFilter.value >= 0 && r.ti !== manageTargetFilter.value) return false
    if (!kw) return true
    const bc = columnComment(baseKey.value, r.baseField)
    const tc = columnComment(targetKeys.value[r.ti], r.targetCol)
    return [r.baseField, bc, r.targetCol, tc].some((s) => String(s || '').toLowerCase().includes(kw))
  })
})

/** 字段注释(columnsMap 查不到回退空串) */
function columnComment(tableKey, colName) {
  const hit = (columnsMap.value[tableKey] || []).find((c) => c.name === colName)
  return hit?.comment || ''
}

/** 删一条映射(单行/批量共用):按 v-model 全量重写,画布随状态重建自动断开对应连线 */
function deleteMappings(rows) {
  if (!rows.length) return
  const next = props.targets.map((t, i) => ({ ...mappingOf(i) }))
  for (const r of rows) delete next[r.ti][r.baseField]
  emit('update:modelValue', next)
}

function deleteMapping(row) {
  deleteMappings([row])
}

function batchDeleteMapping() {
  deleteMappings([...manageSelection.value])
  // 手动清 selection 不会通知表格,reserve-selection 的勾选缓存要显式清,否则下次打开还挂着旧勾选
  manageTableRef.value?.clearSelection()
  manageSelection.value = []
}

// ---------- 状态提示与校验 ----------

/** 每个对比表的映射条数与「主键是否已映射」 */
const targetStats = computed(() => props.targets.map((t, ti) => {
  const map = mappingOf(ti)
  const keyed = !!Object.keys(map).some((bf) => bf.toLowerCase() === String(props.keyField).toLowerCase())
  return { count: Object.keys(map).length, keyed }
}))

const missingKeyCount = computed(() => targetStats.value.filter((s) => !s.keyed).length)

watch(() => props.targets.map((t) => nodeKey(t)).join(','), () => loadColumns())
watch(() => [props.base?.datasourceId, props.base?.db, props.base?.schema, props.base?.table].join('|'),
  () => loadColumns(), { immediate: true })

defineExpose({ missingKeyCount })
</script>

<template>
  <div v-loading="loading" class="cm-wrap">
    <div class="cm-toolbar">
      <!-- 向导页经 #toolbar-prepend 插槽把「AI 预生成字段映射」放最左 -->
      <slot name="toolbar-prepend" />
      <el-button size="small" @click="autoMatchAll">按名称自动匹配</el-button>
      <el-button size="small" @click="clearAll">清空连线</el-button>
      <el-button size="small" :disabled="!manageRows.length" @click="manageVisible = true">
        映射管理{{ manageRows.length ? `(${manageRows.length})` : '' }}
      </el-button>
      <span class="cm-tip">
        点一侧字段行、再点另一侧字段行即可连线(整行都可点);点连线本身删除。
      </span>
      <span v-if="missingKeyCount" class="cm-warn">有 {{ missingKeyCount }} 个对比表还没连「{{ keyField }}」</span>
    </div>
    <!-- 复用 ER 关系图画布(mapping 模式):表卡片 + 字段对齐曲线边 + mapping-columns 布局,可缩放/拖动;
         等两侧字段都拉到再挂载:首帧即带完整字段行数,节点高度一次算准 -->
    <RelationGraphCanvas
      v-if="canvasReady"
      ref="canvasRef"
      class="cm-canvas"
      mode="mapping"
      :nodes="nodes"
      :edges="edges"
      :anchor-table="baseKey"
      :highlight-columns="baseColumns"
      level="all"
      :columns-map="columnsMap"
      field-name-mode="both"
      edge-type="curve"
      :default-zoom="1"
      @mapping-connect="onMappingConnect"
      @edge-click="onEdgeClick"
    />
    <!-- 字段映射管理(参考 ER 图「关系管理」):全量映射清单,按对比系统/关键字筛选,单行删除与批量删除;
         删除即断开画布对应连线(直接改 v-model,画布随状态重建),随时可重连 -->
    <el-dialog v-model="manageVisible" title="字段映射管理" width="1080px" top="8vh">
      <div class="cm-mng-filter">
        <el-select v-model="manageTargetFilter" style="width: 230px">
          <el-option label="全部对比系统" :value="-1" />
          <el-option v-for="(t, ti) in targets" :key="ti" :label="t.label || t.table" :value="ti" />
        </el-select>
        <el-input v-model="manageKeyword" placeholder="搜索字段名/注释" clearable style="width: 220px" />
        <span class="cm-tip">共 {{ filteredManageRows.length }} 条</span>
      </div>
      <div class="cm-mng-bar">
        <span>已选 {{ manageSelection.length }} 条</span>
        <el-button size="small" type="danger" :disabled="!manageSelection.length" @click="batchDeleteMapping">
          批量删除
        </el-button>
      </div>
      <el-table
        ref="manageTableRef"
        :data="filteredManageRows"
        size="small"
        row-key="key"
        max-height="50vh"
        empty-text="没有符合条件的映射"
        @selection-change="(v) => (manageSelection = v)"
      >
        <el-table-column type="selection" width="42" reserve-selection />
        <!-- 首列 = 数据源名 + 表名(有中文注释用注释,加粗)/ 换行英文定位串(库.模式.表,不带数据源名——数据源名已在首行);与画布标题同口径 -->
        <el-table-column label="对比系统" min-width="300">
          <template #default="{ row }">
            <div class="cm-mng-title">{{ row.dsName ? `${row.dsName} · ${row.tableComment || row.qualifiedName}` : (row.tableComment || row.targetLabel) }}</div>
            <div v-if="row.tableComment" class="cm-mng-sub">{{ row.qualifiedName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="基准字段" min-width="170">
          <template #default="{ row }">
            <div>{{ columnComment(baseKey, row.baseField) || row.baseField }}</div>
            <div class="cm-mng-sub">{{ row.baseField }}</div>
          </template>
        </el-table-column>
        <el-table-column label="目标字段" min-width="170">
          <template #default="{ row }">
            <div>{{ columnComment(targetKeys[row.ti], row.targetCol) || row.targetCol }}</div>
            <div class="cm-mng-sub">{{ row.targetCol }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button size="small" type="danger" link @click="deleteMapping(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.cm-wrap {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}
.cm-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.cm-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cm-warn {
  font-size: 12px;
  color: var(--el-color-danger);
}
/* 映射管理弹窗:筛选行 + 批量操作条 */
.cm-mng-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.cm-mng-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.cm-mng-sub {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.cm-mng-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
/* 画布需要显式高度:撑满卡片剩余空间 */
.cm-canvas {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
