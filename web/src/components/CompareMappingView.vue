<script setup>
/**
 * 字段映射关系只读视图(差异明细页「字段映射关系」弹窗用):
 * 复用 ER 画布 `RelationGraphCanvas` 的 mapping 模式,展示「基准表字段 ↔ 各对比表字段」的连线,
 * 与新建比对向导第三步同一块画布、同一种观感,但不挂连线/删线事件,纯展示
 * (画布的 create-edge 产生的临时边会因不在 props.edges 里被差集同步自动收掉,不会留下痕迹)。
 *
 * 映射来源:任务详情 targets[].mapping(建任务时人工/AI 连好后随任务保存);
 * 老任务 mapping 为 null(比对时按字段名忽略大小写自动匹配)——这里按同一口径自动连线兜底,
 * 顶部给提示,避免老任务打开是一张「没有线」的图。
 */
import { computed, ref, watch } from 'vue'
import request from '../api'
import RelationGraphCanvas from './RelationGraphCanvas.vue'

const props = defineProps({
  // 比对任务:baseDatasourceId/baseDb/baseSchema/baseTable/keyField
  job: { type: Object, required: true },
  // 任务对比目标:[{ datasourceId, db, schema, table, dsName(快照), displayName(自定义名,可空), schemaDesc(库描述,可空), mapping }]
  targets: { type: Array, default: () => [] }
})

// 图节点 id 用「数据源|库|模式|表」保证唯一(与 CompareFieldMapping 同口径)
const baseSpec = computed(() => ({
  datasourceId: props.job.baseDatasourceId,
  db: props.job.baseDb || '',
  schema: props.job.baseSchema,
  table: props.job.baseTable
}))

function nodeKey(spec) {
  return [spec?.datasourceId, spec?.db || '', spec?.schema || '', spec?.table || ''].join('|')
}

const baseKey = computed(() => nodeKey(baseSpec.value))
const targetKeys = computed(() => props.targets.map((t) => nodeKey(t)))

/** 目标展示名:自定义显示名(V72)> 库描述(schema_doc)> 数据源名 · 库.模式.表(与新建向导 targetLabel 同口径) */
function targetLabel(t) {
  const schemaPart = t.db ? `${t.db}.${t.schema || ''}` : (t.schema || '')
  const loc = `${schemaPart ? schemaPart + '.' : ''}${t.table}`
  const name = t.displayName || t.schemaDesc || t.dsName
  return name ? `${name} · ${loc}` : loc
}

/** 基准表定位串:db.schema.table(库/模式可空时省略对应段) */
const baseTableLabel = computed(() => {
  const j = props.job
  const schemaPart = j.baseDb ? `${j.baseDb}.${j.baseSchema || ''}` : (j.baseSchema || '')
  return schemaPart ? `${schemaPart}.${j.baseTable}` : j.baseTable
})

const baseFields = ref([])
const targetFields = ref([])
// 表中文注释(画布标题「中文名加粗第一行、英文名第二行」用):字段接口不含表注释,按 schema 分组拉表清单取
const baseTableComment = ref('')
const targetTableComments = ref([])
const loading = ref(false)
// 字段加载完成前不挂载画布(节点高度依赖字段行数,先建后补会让坐标与高度对不上)
const loadedOnce = ref(false)
const canvasReady = computed(() => loadedOnce.value && baseFields.value.length > 0
  && targetFields.value.length === props.targets.length)

function columnsUrl(spec) {
  const q = spec.db ? `?db=${encodeURIComponent(spec.db)}` : ''
  return `/datasources/${spec.datasourceId}/schemas/${encodeURIComponent(spec.schema)}/tables/${encodeURIComponent(spec.table)}/columns${q}`
}

function tablesUrl(spec) {
  const q = spec.db ? `?db=${encodeURIComponent(spec.db)}` : ''
  return `/datasources/${spec.datasourceId}/schemas/${encodeURIComponent(spec.schema)}/tables${q}`
}

/** 拉基准表与各对比表字段 + 各表中文注释(同库多目标共享一次表清单请求,与 CompareFieldMapping 同逻辑) */
async function loadColumns() {
  loading.value = true
  try {
    const specs = [baseSpec.value, ...props.targets]
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
    baseTableComment.value = commentOf[nodeKey(baseSpec.value)] || ''
    targetTableComments.value = props.targets.map((t) => commentOf[nodeKey(t)] || '')
    loadedOnce.value = true
  } finally {
    loading.value = false
  }
}

// ---------- 图数据 ----------

/** 按名称忽略大小写自动匹配:老任务无保存映射时的兜底口径(与后端比对默认行为一致) */
function autoMatchFor(ti) {
  const byLower = new Map((targetFields.value[ti] || []).map((c) => [String(c.name).toLowerCase(), c.name]))
  const out = {}
  for (const bf of baseFields.value) {
    const hit = byLower.get(String(bf.name).toLowerCase())
    if (hit) out[bf.name] = hit
  }
  return out
}

/** 该对比表实际用于画图的映射:有保存映射用保存的,没有(老任务)用自动匹配兜底 */
function mappingOf(ti) {
  const m = props.targets[ti]?.mapping
  return m && Object.keys(m).length ? m : autoMatchFor(ti)
}

// 按自动匹配兜底展示的对比表数量(>0 时顶部提示,避免误以为这些线是当时人工连的)
const autoMatchCount = computed(() =>
  props.targets.filter((t) => !(t.mapping && Object.keys(t.mapping).length)).length
)

// 参与连线的基准字段并集:交给画布 highlightColumns 常亮(主题色+加粗),一眼看出哪些基准字段参与比对
const connectedBaseColumns = computed(() => {
  const set = new Set()
  props.targets.forEach((t, ti) => {
    for (const bf of Object.keys(mappingOf(ti))) set.add(bf)
  })
  return [...set]
})

const nodes = computed(() => {
  // 显式依赖字段清单:字段加载完成后节点高度才定型,触发画布重建并按最终高度重新布局
  void baseFields.value.length
  void targetFields.value.length
  void baseTableComment.value
  void targetTableComments.value.length
  const base = {
    name: baseKey.value,
    label: `基准表 · ${baseTableLabel.value}`,
    comment: props.job.baseTable,
    tableComment: baseTableComment.value
  }
  const rest = props.targets.map((t, i) => ({
    name: targetKeys.value[i],
    label: targetLabel(t),
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

/** 映射 → 图边(one=基准表字段,many=对比表字段;基数恒 1:1,状态恒 CONFIRMED 走实线,按目标表着色) */
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

watch(() => [props.job?.id, props.targets.length].join('|'), () => loadColumns(), { immediate: true })
</script>

<template>
  <div v-loading="loading" class="cmv-wrap">
    <div v-if="autoMatchCount" class="cmv-tip">
      有 {{ autoMatchCount }} 个对比表未保存映射(老任务),图中按字段名自动匹配展示
    </div>
    <!-- 只读展示:不传 mapping-connect/edge-click 事件,连线不可增删;画布缩放/拖动/全屏/小地图均可用 -->
    <RelationGraphCanvas
      v-if="canvasReady"
      class="cmv-canvas"
      mode="mapping"
      :nodes="nodes"
      :edges="edges"
      :anchor-table="baseKey"
      :highlight-columns="connectedBaseColumns"
      level="all"
      :columns-map="columnsMap"
      field-name-mode="both"
      edge-type="curve"
      :default-zoom="1"
    />
  </div>
</template>

<style scoped>
.cmv-wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.cmv-tip {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-color-warning);
}
/* 画布需要显式高度:撑满弹窗主体 */
.cmv-canvas {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
