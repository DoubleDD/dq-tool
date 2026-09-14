<script setup>
/**
 * 表级四栏级联选择器(新建比对任务第一步「选择基准表」、第三步「选择比对系统」共用)。
 *
 * 栏位:数据源 → 数据库 → 模式(按数据库类型动态显示)→ 表(选项带注释)。
 * - 多库方言(SQL Server / Kingbase):`/databases` 出库清单,再 `/schemas?db=` 出模式清单,四栏齐全;
 * - 其余方言(SQL Server/Kingbase 之外)没有独立的模式层,库/schema 合并落在第二栏,第三栏隐藏——即「三栏级联」。
 * 每一栏都可输入关键字过滤(表栏匹配表名或注释);只有唯一选项的库/模式层自动选中,减少点击。
 *
 * 状态全部通过 `v-model:xxx` 回传父级,父级仍持有 form;表变更额外 emit table-change 供父级补默认任务名。
 */
import { computed, reactive, ref, watch } from 'vue'
import { Minus, Plus, Search } from '@element-plus/icons-vue'
import request from '../api'
import DbTypeIcon from './DbTypeIcon.vue'

const props = defineProps({
  // 可选数据源清单(由父级拉取,口径与页面工具栏一致)
  datasources: { type: Array, default: () => [] },
  datasourceId: { type: String, default: '' },
  db: { type: String, default: '' },
  schema: { type: String, default: '' },
  table: { type: String, default: '' },
  // 拉到表清单后若命中该表名且当前未选表则自动选中(比对任务目标侧「默认选同名表」用)
  preferTable: { type: String, default: '' },
  // 当前数据源+库/schema 下禁止选择的表名(比对任务目标侧:基准表本身不可作为目标),
  // 命中项置灰并标「基准表」,不可点选、也不会被 preferTable 自动选中
  disabledTables: { type: Array, default: () => [] },
  // 底部摘要与空态提示里的对象名(第一步「基准表」,第三步「比对系统」)
  label: { type: String, default: '基准表' },
  // 是否展示底部「已选 xxx:...」摘要(第三步右侧已有任务摘要,重复,故关掉)
  showSelected: { type: Boolean, default: true },
  // 逐表勾选模式(比对任务第三步):表行右侧显示 +/− —— 未加过显示 +,已加过显示绿色 −,
  // 点行或点图标都 toggle;关闭时维持单选(点行即选中)语义,供第一步选基准表使用
  toggleable: { type: Boolean, default: false },
  // 当前数据源+库/schema 下已加入目标的表名(toggleable 时用来把行渲染成「已加」绿色 − 态)
  addedTables: { type: Array, default: () => [] }
})
const emit = defineEmits([
  'update:datasourceId', 'update:db', 'update:schema', 'update:table', 'table-change', 'toggle'
])

const dsKeyword = ref('')
const dbKeyword = ref('')
const schemaKeyword = ref('')
const tableKeyword = ref('')

const databases = ref([])
const schemas = ref([])
const tables = ref([])
const dbLoading = ref(false)
const schemaLoading = ref(false)
const tableLoading = ref(false)

const currentDs = computed(() => props.datasources.find((d) => String(d.id) === String(props.datasourceId)))
// 多库方言(SQL Server/Kingbase)先选库再选模式;与对象管理选表组件同一判定
const multiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(currentDs.value?.dbType))

/** 第二栏数据:多库方言是库清单,其余方言直接是库/schema 清单 */
const lane2Options = computed(() => (multiDb.value ? databases.value : schemas.value))

function includesText(value, keyword) {
  return String(value ?? '').toLowerCase().includes(keyword)
}

const dsOptions = computed(() => {
  const k = dsKeyword.value.trim().toLowerCase()
  if (!k) return props.datasources
  return props.datasources.filter((d) => includesText(d.name, k) || includesText(d.dbType, k))
})
const dbOptions = computed(() => {
  const k = dbKeyword.value.trim().toLowerCase()
  if (!k) return lane2Options.value
  return lane2Options.value.filter((d) => includesText(d, k))
})
const schemaOptions = computed(() => {
  const k = schemaKeyword.value.trim().toLowerCase()
  if (!k) return schemas.value
  return schemas.value.filter((s) => includesText(s, k))
})
const tableOptions = computed(() => {
  const k = tableKeyword.value.trim().toLowerCase()
  if (!k) return tables.value
  return tables.value.filter((t) => includesText(t.name, k) || includesText(t.comment, k))
})

const currentTable = computed(() => tables.value.find((t) => t.name === props.table))

/** 底部已选摘要:多库方言展示「库.模式.表」,其余方言展示「库/schema.表」 */
const selectedLabel = computed(() => {
  if (!currentDs.value) return ''
  const head = props.db ? `${props.db}.${props.schema}` : props.schema
  const tail = props.table ? `${props.table}${currentTable.value?.comment ? `(${currentTable.value.comment})` : ''}` : ''
  return [currentDs.value.name, [head, tail].filter(Boolean).join('.')].filter(Boolean).join(' · ')
})

async function loadDatabases() {
  databases.value = []
  if (!props.datasourceId || !multiDb.value) return
  const id = props.datasourceId
  dbLoading.value = true
  try {
    const list = await request.get(`/datasources/${id}/databases`).catch(() => [])
    // 快速连点数据源时丢弃过期响应
    if (id !== props.datasourceId) return
    databases.value = list
  } finally {
    dbLoading.value = false
  }
}

async function loadSchemas() {
  schemas.value = []
  if (!props.datasourceId) return
  if (multiDb.value && !props.db) return
  const id = props.datasourceId
  const db = props.db
  schemaLoading.value = true
  try {
    const q = db ? `?db=${encodeURIComponent(db)}` : ''
    const list = await request.get(`/datasources/${id}/schemas${q}`).catch(() => [])
    if (id !== props.datasourceId || db !== props.db) return
    schemas.value = list
  } finally {
    schemaLoading.value = false
  }
}

async function loadTables() {
  tables.value = []
  if (!props.datasourceId || !props.schema) return
  const id = props.datasourceId
  const db = props.db
  const schema = props.schema
  tableLoading.value = true
  try {
    const q = db ? `?db=${encodeURIComponent(db)}` : ''
    const list = await request.get(
      `/datasources/${id}/schemas/${encodeURIComponent(schema)}/tables${q}`
    ).catch(() => [])
    if (id !== props.datasourceId || db !== props.db || schema !== props.schema) return
    tables.value = list
    // 目标侧默认选同名表:仅在用户尚未选表、清单里确有该表且该表未被禁用时补选(不覆盖用户已选)
    if (!props.table && props.preferTable && !isTableDisabled(props.preferTable)) {
      const hit = tables.value.find((t) => t.name === props.preferTable)
      if (hit) pickTable(hit)
    }
  } finally {
    tableLoading.value = false
  }
}

// ---------- 选择动作:只回传父级,后续栏位的清空与加载由下方 watch 统一驱动 ----------

function pickDatasource(ds) {
  const id = String(ds.id)
  if (id === String(props.datasourceId)) return
  dsKeyword.value = ''
  emit('update:datasourceId', id)
  emit('update:db', '')
  emit('update:schema', '')
  emit('update:table', '')
  emit('table-change', null)
}

function pickLane2(name) {
  if (multiDb.value) {
    if (name === props.db) return
    emit('update:db', name)
    emit('update:schema', '')
    emit('update:table', '')
    emit('table-change', null)
  } else {
    pickSchema(name)
  }
}

function pickSchema(name) {
  if (name === props.schema) return
  dbKeyword.value = ''
  emit('update:schema', name)
  emit('update:table', '')
  emit('table-change', null)
}

function pickTable(t) {
  if (t.name === props.table || isTableDisabled(t.name)) return
  emit('update:table', t.name)
  emit('table-change', t)
}

/** 该表是否在当前数据源+库/schema 下被禁用(目标侧基准表本身不可选) */
function isTableDisabled(name) {
  return props.disabledTables.includes(name)
}

/** 该表是否已加入目标(toggleable 模式下的绿色 − 态) */
function isTableAdded(name) {
  return props.addedTables.includes(name)
}

/** 表行点击:勾选模式走 toggle(禁用项不响应),单选模式走选中 */
function onTableClick(t) {
  if (isTableDisabled(t.name)) return
  if (props.toggleable) {
    emit('toggle', t)
    return
  }
  pickTable(t)
}

/** 库/模式层只有一个候选时自动选中,省一次点击(表不自动选,避免误提交) */
function autoPickOne() {
  if (multiDb.value && props.db && !props.schema && schemas.value.length === 1) pickSchema(schemas.value[0])
  if (!props.db && !props.schema && lane2Options.value.length === 1) pickLane2(lane2Options.value[0])
}

// 数据源变化:重拉第二栏(多库方言拉库清单,其余方言拉库/schema 清单)
watch(() => props.datasourceId, async (id) => {
  databases.value = []
  schemas.value = []
  tables.value = []
  dbKeyword.value = ''
  schemaKeyword.value = ''
  tableKeyword.value = ''
  if (!id) return
  if (multiDb.value) await loadDatabases()
  else await loadSchemas()
  autoPickOne()
}, { immediate: true })

// 数据源清单异步到达后,若已选数据源但第二栏为空则补拉一次(多库判定依赖 dbType)
watch(() => props.datasources, async (list) => {
  if (!props.datasourceId || !list?.length) return
  if (lane2Options.value.length) return
  if (multiDb.value) await loadDatabases()
  else await loadSchemas()
  autoPickOne()
})

// 库变化(仅多库方言):重拉模式清单
watch(() => props.db, async (db) => {
  schemas.value = []
  tables.value = []
  schemaKeyword.value = ''
  tableKeyword.value = ''
  if (!db || !multiDb.value) return
  await loadSchemas()
  autoPickOne()
})

// 模式变化:重拉表清单
watch(() => props.schema, async (schema) => {
  tables.value = []
  tableKeyword.value = ''
  if (!schema) return
  await loadTables()
})

// ---------- 栏宽拖拽 ----------
// 默认栏宽走 CSS 比例(左三栏各 20%、表栏吃剩余);拖动某一栏的分隔条后,该侧栏位改为显式像素宽度,
// 最右栏始终吃剩余空间(不写死),所以窗口变宽也不会在右侧留白。
const cascadeRef = ref(null)
const widths = reactive({ ds: 0, db: 0, schema: 0, table: 0 })
const MIN_COL_PX = 110
const MIN_TABLE_PX = 180

/** 当前可见栏的 key 顺序(与模板中的栏位一致) */
const visibleKeys = computed(() => (multiDb.value ? ['ds', 'db', 'schema', 'table'] : ['ds', 'db', 'table']))

/** 栏位行内样式:0 表示未拖过,交给 CSS 默认比例;最右栏恒不写宽,吃剩余 */
function colStyle(key) {
  const w = widths[key]
  if (!w || key === visibleKeys.value[visibleKeys.value.length - 1]) return {}
  return { flex: `0 0 ${w}px` }
}

/** 拖动第 idx 栏与第 idx+1 栏之间的分隔条:只有这一栏变,后一栏吃差额(最右栏天然吃剩余) */
function startResize(key, e) {
  e.preventDefault()
  const container = cascadeRef.value
  if (!container) return
  const keys = visibleKeys.value
  const idx = keys.indexOf(key)
  if (idx < 0 || idx >= keys.length - 1) return
  const startW = [...container.querySelectorAll('.cascade-col')].map((el) => el.getBoundingClientRect().width)
  const startX = e.clientX
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  const onMove = (ev) => {
    const nextMin = keys[idx + 1] === keys[keys.length - 1] ? MIN_TABLE_PX : MIN_COL_PX
    const left = Math.min(startW[idx] + startW[idx + 1] - nextMin, Math.max(MIN_COL_PX, startW[idx] + ev.clientX - startX))
    const right = startW[idx] + startW[idx + 1] - left
    keys.forEach((k, i) => {
      if (i === keys.length - 1) return // 最右栏自适应
      if (i === idx) widths[k] = left
      else if (i === idx + 1) widths[k] = right
      else widths[k] = startW[i]
    })
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

/** 双击分隔条恢复默认比例 */
function resetWidths() {
  widths.ds = widths.db = widths.schema = widths.table = 0
}
</script>

<template>
  <!-- 单根 flex 容器:父级 step-body 撑满页面剩余高度时,面板与列表随之撑满,不留大片空白 -->
  <div class="cascade-wrap">
    <!-- 四栏级联面板:选中项高亮,下一栏随上一栏联动刷新;栏间分隔条可拖动调宽(双击恢复默认) -->
    <div ref="cascadeRef" class="cascade">
      <!-- 第一栏:数据源 -->
      <div class="cascade-col col-ds" :style="colStyle('ds')">
        <div class="cascade-head">数据源<span class="cascade-count">{{ dsOptions.length }}</span></div>
        <div class="cascade-search">
          <el-input v-model="dsKeyword" size="small" clearable placeholder="过滤数据源" :prefix-icon="Search" />
        </div>
        <div class="cascade-list">
          <div v-for="ds in dsOptions" :key="ds.id" class="cascade-item"
               :class="{ active: String(ds.id) === String(datasourceId) }" @click="pickDatasource(ds)">
            <DbTypeIcon :type="ds.dbType" :size="14" />
            <span class="cascade-name">{{ ds.name }}</span>
          </div>
          <div v-if="!dsOptions.length" class="cascade-empty">无匹配数据源</div>
        </div>
      </div>
      <div class="cascade-resizer" title="拖动调整栏宽(双击恢复默认)" @mousedown="startResize('ds', $event)" @dblclick="resetWidths" />

      <!-- 第二栏:数据库(其余方言没有独立模式层,库/schema 清单直接落在这里) -->
      <div class="cascade-col col-db" :style="colStyle('db')">
        <div class="cascade-head">数据库<span class="cascade-count">{{ dbOptions.length }}</span></div>
        <div class="cascade-search">
          <el-input v-model="dbKeyword" size="small" clearable :placeholder="multiDb ? '过滤数据库' : '过滤库/schema'"
                    :disabled="!datasourceId" :prefix-icon="Search" />
        </div>
        <div v-loading="dbLoading || (!multiDb && schemaLoading)" class="cascade-list">
          <div v-for="d in dbOptions" :key="d" class="cascade-item"
               :class="{ active: multiDb ? d === db : d === schema }" @click="pickLane2(d)">
            <span class="cascade-name">{{ d }}</span>
          </div>
          <div v-if="!dbOptions.length" class="cascade-empty">
            {{ !datasourceId ? '请先选择数据源' : (multiDb ? '该数据源下没有库' : '该数据源下没有库/schema') }}
          </div>
        </div>
      </div>
      <div class="cascade-resizer" title="拖动调整栏宽(双击恢复默认)" @mousedown="startResize('db', $event)" @dblclick="resetWidths" />

      <!-- 第三栏:模式(仅多库方言动态显示) -->
      <div v-if="multiDb" class="cascade-col col-schema" :style="colStyle('schema')">
        <div class="cascade-head">模式<span class="cascade-count">{{ schemaOptions.length }}</span></div>
        <div class="cascade-search">
          <el-input v-model="schemaKeyword" size="small" clearable placeholder="过滤模式" :disabled="!db" :prefix-icon="Search" />
        </div>
        <div v-loading="schemaLoading" class="cascade-list">
          <div v-for="s in schemaOptions" :key="s" class="cascade-item" :class="{ active: s === schema }" @click="pickSchema(s)">
            <span class="cascade-name">{{ s }}</span>
          </div>
          <div v-if="!schemaOptions.length" class="cascade-empty">{{ db ? '该库下没有模式' : '请先选择数据库' }}</div>
        </div>
      </div>
      <div v-if="multiDb" class="cascade-resizer" title="拖动调整栏宽(双击恢复默认)" @mousedown="startResize('schema', $event)" @dblclick="resetWidths" />

      <!-- 第四栏:表(选项带注释);toggleable 时每行右侧给 +/− 做「加入/移出目标」 -->
      <div class="cascade-col col-table" :style="colStyle('table')">
        <div class="cascade-head">表<span class="cascade-count">{{ tableOptions.length }}</span></div>
        <div class="cascade-search">
          <el-input v-model="tableKeyword" size="small" clearable placeholder="过滤表名/注释" :disabled="!schema" :prefix-icon="Search" />
        </div>
        <div v-loading="tableLoading" class="cascade-list">
          <div v-for="t in tableOptions" :key="t.name" class="cascade-item"
               :class="{ active: !toggleable && t.name === table, disabled: isTableDisabled(t.name), added: toggleable && isTableAdded(t.name) }"
               @click="onTableClick(t)">
            <span class="cascade-name">{{ t.name }}</span>
            <span class="cascade-comment">{{ t.comment || '无注释' }}</span>
            <span v-if="isTableDisabled(t.name)" class="cascade-flag">基准表</span>
            <el-tooltip v-else-if="toggleable" :content="isTableAdded(t.name) ? '移出比对系统' : '加入比对系统'" placement="left" :show-after="200">
              <span class="cascade-toggle" :class="{ remove: isTableAdded(t.name) }" @click.stop="emit('toggle', t)">
                <el-icon><Minus v-if="isTableAdded(t.name)" /><Plus v-else /></el-icon>
              </span>
            </el-tooltip>
          </div>
          <div v-if="!tableOptions.length" class="cascade-empty">{{ schema ? '该模式/库下没有表' : '请先选择库/schema' }}</div>
        </div>
      </div>
    </div>
    <div v-if="showSelected" class="cascade-selected">
      <template v-if="selectedLabel">已选{{ label }}:{{ selectedLabel }}</template>
      <template v-else>
        请按「数据源 → 数据库 → 模式 → 表」逐栏选择{{ label }}{{ datasourceId && !multiDb ? '(该数据源无独立模式层,第三栏隐藏)' : '' }}
      </template>
    </div>
  </div>
</template>

<style scoped>
/* 外层竖向 flex:面板占满父级剩余高度,底部已选摘要固定高度 */
.cascade-wrap {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}
.cascade {
  display: flex;
  align-items: stretch;
  flex: 1 1 auto;
  min-height: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: hidden;
  background: var(--el-fill-color-blank);
}
.cascade-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
/* 栏间分隔条:零宽不参与排版(不挤压百分比栏宽),靠伪元素画 1px 线并向外扩命中区 */
.cascade-resizer {
  position: relative;
  flex: 0 0 0;
  width: 0;
  cursor: col-resize;
  z-index: 1;
}
.cascade-resizer::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -0.5px;
  width: 1px;
  background: var(--el-border-color-lighter);
}
.cascade-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -4px;
  right: -4px;
}
.cascade-resizer:hover::before {
  left: -1px;
  width: 2px;
  background: var(--el-color-primary-light-5);
}
/* 宽度口径:默认左侧栏各占 20%,表栏吃剩余(四栏时即 40%;无模式栏时更宽);
   拖动分隔条后对应栏位由行内 style 写死像素宽度,最右栏仍自适应 */
.col-ds,
.col-db,
.col-schema {
  flex: 0 0 20%;
}
.col-table {
  flex: 1 1 40%;
}
.cascade-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.cascade-count {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
/* 表行右侧「+/−」:未加入显示 +,已加入显示绿色 −(点行或点图标都能切换) */
.cascade-toggle {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 3px;
  border: 1px solid var(--el-border-color);
  color: var(--el-text-color-regular);
  cursor: pointer;
  transition: all 0.15s;
}
.cascade-toggle:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.cascade-toggle.remove {
  border-color: var(--el-color-success-light-5);
  color: var(--el-color-success);
}
.cascade-toggle.remove:hover {
  border-color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
/* 已加入目标的表:整行浅绿底 + 绿色表名,一眼区分 */
.cascade-item.added {
  background: var(--el-color-success-light-9);
}
.cascade-item.added .cascade-name {
  color: var(--el-color-success);
}
.cascade-search {
  padding: 6px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.cascade-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 4px;
}
.cascade-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}
.cascade-item:hover {
  background: var(--el-fill-color-light);
}
.cascade-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
/* 禁用项(目标侧基准表本身):置灰不可点,右侧标「基准表」 */
.cascade-item.disabled {
  cursor: not-allowed;
  color: var(--el-text-color-disabled);
}
.cascade-item.disabled:hover {
  background: transparent;
}
.cascade-item.disabled .cascade-comment {
  color: var(--el-text-color-disabled);
}
.cascade-flag {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.cascade-name {
  flex: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.col-table .cascade-name {
  flex: 0 1 auto;
}
.cascade-comment {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cascade-empty {
  padding: 12px 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.cascade-selected {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
