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
  // 向导第二步勾选的基准字段(基准表里参与比对的字段):画布上常亮高亮,提示用户这些才需要连线
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

/** 拉基准表与各对比表字段;基准表未选全不发请求(第四步用 v-show,向导第一步时组件已挂载) */
async function loadColumns() {
  if (!baseReady.value) {
    baseFields.value = []
    targetFields.value = []
    return
  }
  loading.value = true
  try {
    const [base, ...rest] = await Promise.all([
      request.get(columnsUrl(props.base)).catch(() => []),
      ...props.targets.map((t) => request.get(columnsUrl(t)).catch(() => []))
    ])
    baseFields.value = base || []
    targetFields.value = rest.map((r) => r || [])
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
  if (!baseReady.value) return []
  const base = { name: baseKey.value, label: `基准表 · ${props.baseLabel || props.base.table}`, comment: props.base.table }
  const rest = props.targets.map((t, i) => ({
    name: targetKeys.value[i],
    label: t.label || t.table,
    comment: t.table
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
      <el-button size="small" @click="autoMatchAll">按名称自动匹配</el-button>
      <el-button size="small" @click="clearAll">清空连线</el-button>
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
/* 画布需要显式高度:撑满卡片剩余空间 */
.cm-canvas {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
