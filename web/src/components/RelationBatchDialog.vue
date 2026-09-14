<template>
  <!-- 关系批量处理对话框:整库关系列表(状态默认全部)+ 表筛选(状态/标签/搜索/仅重复连线)+ 多选批量确认/否决,
       「选中多余连线」把同一对表之间的重复连线按表对分组、每组保留一条并选中其余,配合批量否决一键清理;
       最后一列「备注」为输入框,人工填写否决原因随本次确认/否决一并落库;
       勾选态副表列高亮;点击行(备注列/勾选框列除外)切换勾选,方便逐条快速圈选;
       勾选 reserve-selection,任何筛选都不丢勾选;已勾选被筛掉的行红色提醒,可一键置顶展示(固定在列表顶部);
       列表按副表表名排序;hover 副表时,所有同名表(≥2 行)的名称绿色高亮,孤行不高亮;
       操作成功后 emit done 由父级刷新图数据(ER 关系页与字段明细页 ER 页签共用) -->
  <el-dialog
    :model-value="modelValue"
    title="关系批量处理"
    width="80%"
    style="min-width: 1180px"
    top="6vh"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @open="loadList(true)"
  >
    <!-- 筛选行:状态(默认全部,选项带计数)+ 标签(按表标记,两端任一命中)+ 关键字搜索(表名/注释/字段名/备注,前端过滤不回源)+ 仅重复连线 -->
    <div class="rb-filter">
      <el-select v-model="statusFilter" style="width: 150px">
        <el-option v-for="opt in statusOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
      </el-select>
      <el-select
        v-model="selectedTagIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        filterable
        clearable
        placeholder="按标记筛选(两端任一命中)"
        style="width: 220px"
      >
        <el-option v-for="opt in tagOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
      </el-select>
      <el-input v-model="tableFilter" placeholder="搜索表名/注释/字段/备注" clearable style="width: 240px" />
      <el-checkbox v-model="dupFilter" class="rb-dup" title="同一对表之间存在多条连线(不同字段各连一条)时,只显示这些多余的连线;已否决的关系不参与">
        仅重复 ({{ dupRowCount }})
      </el-checkbox>
      <span class="rb-count">共 {{ filteredRows.length }} 条</span>
      <span v-if="centerTable" class="rb-scope">仅本表相关关系</span>
    </div>

    <!-- 批量操作条:随选中数联动;仅保留确认/否决,备注随本次操作一并保存;
         已勾选被当前筛选滤除时,末尾红色提醒 + 置顶展示那几条不匹配行(固定列表顶部,仍保留勾选) -->
    <div class="rb-bar">
      <span>已选 {{ selection.length }} 条</span>
      <el-button size="small" type="primary" :disabled="!selection.length" :loading="opLoading" @click="doBatch('confirm')">
        批量确认
      </el-button>
      <el-button size="small" type="warning" :disabled="!selection.length" :loading="opLoading" @click="doBatch('reject')">
        批量否决
      </el-button>
      <el-button
        size="small"
        :disabled="!redundantCount"
        title="按当前筛选结果把同一对表的连线分组,每组保留一条(已确认优先),选中其余,配合「批量否决」一键清理重复连线"
        @click="selectRedundant"
      >
        选中多余连线 ({{ redundantCount }})
      </el-button>
      <span class="rb-tip">备注列可填写否决原因,随本次确认/否决一并保存;人工审核后的关系重新推导不会再变回候选</span>
      <template v-if="mismatchCount">
        <span class="rb-hidden-warn">已勾选 {{ mismatchCount }} 条被筛选滤除</span>
        <el-button size="small" type="danger" plain @click="showMismatch = !showMismatch">
          {{ showMismatch ? '取消置顶(仍保留勾选)' : `置顶显示这 ${mismatchCount} 条` }}
        </el-button>
      </template>
    </div>

    <!-- 各列定宽(备注列 min-width 弹性),合计超出弹窗宽度时表格原生横向滚动,不挤压不截断;
         展示数据 = 筛选结果(按副表名排序)+ 可选置顶的不匹配已勾选行 -->
    <el-table
      ref="tableRef"
      :data="displayedRows"
      v-loading="loading"
      size="small"
      row-key="id"
      max-height="calc(100vh - 360px)"
      empty-text="没有符合条件的关系记录"
      :cell-class-name="cellClassName"
      :row-class-name="rowClassName"
      @selection-change="(rows) => (selection = rows)"
      @row-click="onRowClick"
    >
      <!-- reserve-selection:勾选只随用户点选变化,任何筛选/表格重算都不丢(批量操作对全部勾选生效,含被滤除的) -->
      <el-table-column type="selection" width="42" reserve-selection />
      <!-- 主表列:中心表(无中心表时为关系的一端)恒在此列;第一行中文表名,第二行「表名 [字段名]」;行尾右对齐标 1/N -->
      <el-table-column label="主表" min-width="300">
        <template #default="{ row }">
          <div class="tn-row">
            <div class="tn-cell">
              <div class="tn-cn">{{ commentOf(row.center.name) || row.center.name }}</div>
              <div class="tn-en">{{ tableWithColumn(row.center.name, row.center.column) }}</div>
            </div>
            <span class="tn-mark tn-mark-end" :class="markClass(row.card, row.center, 'self')">{{ sideMark(row.card, row.center, 'self') }}</span>
          </div>
        </template>
      </el-table-column>
      <!-- 副表列:关系另一端;格式与主表列一致,行首左对齐标 1/N(即「关系」列前);
           hover 本单元格时,列表里所有同名表(≥2 行)的名称绿色高亮,看清同名行的分布 -->
      <el-table-column label="副表" min-width="300">
        <template #default="{ row }">
          <div
            class="tn-row"
            :class="{ 'is-related-hover': hoverRelated === row.related.name && (relatedNameCounts.get(row.related.name) || 0) > 1 }"
            @mouseenter="onRelatedEnter(row)"
            @mouseleave="onRelatedLeave"
          >
            <span class="tn-mark tn-mark-start" :class="markClass(row.card, row.center, 'other')">{{ sideMark(row.card, row.center, 'other') }}</span>
            <div class="tn-cell">
              <div class="tn-cn">{{ commentOf(row.related.name) || row.related.name }}</div>
              <div class="tn-en">{{ tableWithColumn(row.related.name, row.related.column) }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <!-- 两端表的标记:一眼看出该关系涉及的表属于哪些标记(与表列表页标记列同款样式,空表标记浅色区分) -->
      <el-table-column label="标记" min-width="180">
        <template #default="{ row }">
          <template v-if="rowTags(row).length">
            <el-tag
              v-for="tag in rowTags(row)"
              :key="`${tag.id}`"
              size="small"
              class="rb-tag"
              :type="tag.kind === 'EMPTY' ? 'info' : undefined"
              :effect="tag.kind === 'EMPTY' ? 'plain' : 'dark'"
              :color="tag.kind === 'EMPTY' ? undefined : tag.color"
              :style="tag.kind === 'EMPTY' ? {} : { borderColor: tag.color }"
            >{{ tag.name }}</el-tag>
          </template>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">{{ sourceText(row.source) }}</template>
      </el-table-column>
      <el-table-column label="备注" min-width="220" class-name="rb-remark-cell">
        <template #default="{ row }">
          <el-input
            v-model="remarkEdits[row.id]"
            size="small"
            clearable
            placeholder="人工备注 / 否决原因"
          />
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, nextTick } from 'vue'
import { ElMessage } from '../utils/notify'
import request, { batchConfirmRelations, batchRejectRelations, listRelations } from '../api'

// 关系批量处理(自成闭环):打开时拉关系清单(状态/标签/关键字过滤在前端做,切换不回源);
// 有中心表(表详情页 ER 关系入口)时**接口只拉该表参与的关系**(GET /api/relations?table=中心表),
// 不是拉全库再前端过滤——清空弹窗上全部筛选条件后,列表呈现的就是该中心表的全部相关关系;
// 独立 ER 关系页无中心表概念,退化为整库清单(不传 table 参数,与接口既有的可选过滤口径一致)
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  dsId: { type: [String, Number], required: true },
  db: { type: String, default: '' },
  schema: { type: String, required: true },
  // 中心节点表(可选):表详情页 ER 关系页签入口传入,同时作为接口 table 过滤参数、端一列列头与两端重数标记的依据;空=整库清单
  centerTable: { type: String, default: '' }
})

// done:批量操作成功 { action: confirm/reject, updated: 实际影响数 },父级刷新图
const emit = defineEmits(['update:modelValue', 'done'])

const rows = ref([])
const loading = ref(false)
const opLoading = ref(false)
// 状态默认「全部」:弹窗打开即该中心表的完整关系清单,候选/确认/否决只是可选收窄手段
const statusFilter = ref('')
const tableFilter = ref('')
// 已选标记 id(字符串形态,与 el-option 的 value 对齐);多选为 OR:两端表任一命中即展示
const selectedTagIds = ref([])
// 仅重复连线:同一对表之间存在多条候选/确认连线时只显示这些行(已否决的图上不画线,不参与计数也不再展示)
const dupFilter = ref(false)
const selection = ref([])
const tableRef = ref(null)
// 关系 id -> 备注输入框内容(以行 id 为键,表格重算/筛选不会丢编辑);落库只在批量确认/否决时发生
const remarkEdits = ref({})
// 已勾选行的 id 集合,供副表列高亮判定(cell-class-name 每轮渲染重算,勾选变化即刷新)
const selectedIds = computed(() => new Set(selection.value.map((r) => r.id)))
// 表名 -> 注释(含小写键兜底,忽略大小写命中);供端一/端二两列第一行与列头显示中文表名
const tableComments = ref({})
// 表名 -> 标记数组(含系统驱动的空表标记);供标记列与标记筛选
const tableTags = ref({})

/** 表注释查询(忽略大小写兜底);无注释返回空串 */
function commentOf(table) {
  return tableComments.value[table] ?? tableComments.value[String(table).toLowerCase()] ?? ''
}

/** 表格第二行文本「表名 [字段名]」(不用点号连接);无字段名时只显示表名(1:1 关系可能缺一端字段) */
function tableWithColumn(table, column) {
  return column ? `${table} [${column}]` : table
}

/** 主/副表重数标记:1=唯一端,N=重复端。
 *  入参 card 是展示行的基数简称(源字段名是 cardinality,经 toViewRow 归一,别再写回 row.card 之外的名字);
 *  ONE_TO_MANY 的 one 侧恒为唯一方(存储口径);中心表可能本身就在 many 侧(此时主表列就是 N),
 *  which='self' 取中心端、'other' 取另一端;ONE_TO_ONE / 疑似多对多方向无语义,两端都是 1 */
function sideMark(card, center, which) {
  if (card !== 'ONE_TO_MANY') return '1'
  const centerIsOne = center.side === 'one'
  const wantOne = which === 'self' ? centerIsOne : !centerIsOne
  return wantOne ? '1' : 'N'
}

/** 标记配色:N(多端)用橙色与 1(唯一端)区分,避免「哪端是多」看串;样式与尺寸不变 */
function markClass(card, center, which) {
  return sideMark(card, center, which) === 'N' ? 'is-many' : 'is-one'
}

/** 表标记查询(忽略大小写兜底);无标记返回空数组 */
function tagsOf(table) {
  return tableTags.value[table] ?? tableTags.value[String(table).toLowerCase()] ?? []
}

/** 关系行的标记(两端合并去重,按 id 升序);供表格「标记」列展示 */
function rowTags(row) {
  const seen = new Map()
  for (const tag of [...tagsOf(row.oneTable), ...tagsOf(row.manyTable)]) {
    if (!seen.has(tag.id)) seen.set(tag.id, tag)
  }
  return [...seen.values()].sort((a, b) => a.id - b.id)
}

// 状态下拉(选项带各状态计数,"全部"不计入标签避免冗长)
const statusOptions = computed(() => {
  const count = (s) => rows.value.filter((r) => r.status === s).length
  return [
    { value: 'CANDIDATE', label: `仅候选 (${count('CANDIDATE')})` },
    { value: 'CONFIRMED', label: `已确认 (${count('CONFIRMED')})` },
    { value: 'REJECTED', label: `已否决 (${count('REJECTED')})` },
    { value: '', label: '全部状态' }
  ]
})

// 标记下拉:只列当前关系清单两端表实际用到的标记(与表列表页 availableTags 同口径),避免整库标记全列导致冗长
const tagOptions = computed(() => {
  const seen = new Map()
  for (const r of rows.value) {
    for (const tag of [...tagsOf(r.oneTable), ...tagsOf(r.manyTable)]) {
      if (!seen.has(tag.id)) seen.set(tag.id, tag)
    }
  }
  return [...seen.values()].sort((a, b) => a.id - b.id)
    .map((t) => ({ value: String(t.id), label: t.name }))
})

/** 方向无关的表对 key:两端表名按字典序拼接(字段不参与——同表对不同字段各连一条才是要清理的重复连线) */
function tablePairKey(r) {
  const a = r.oneTable
  const b = r.manyTable
  return a <= b ? `${a}|${b}` : `${b}|${a}`
}

// 图上有线的关系(候选/确认)按表对计数,出现 ≥2 条的表对即「重复连线」表对;已否决的不画线不计入
const dupPairKeys = computed(() => {
  const counts = new Map()
  for (const r of rows.value) {
    if (r.status === 'REJECTED') continue
    const k = tablePairKey(r)
    counts.set(k, (counts.get(k) || 0) + 1)
  }
  const keys = new Set()
  for (const [k, c] of counts) if (c > 1) keys.add(k)
  return keys
})

// 勾选框计数:全量清单里落在重复表对上的有效连线条数(与状态下拉各选项的计数口径一致)
const dupRowCount = computed(() =>
  rows.value.filter((r) => r.status !== 'REJECTED' && dupPairKeys.value.has(tablePairKey(r))).length
)

// 过滤:状态 + 标记(两端任一命中)+ 仅重复连线 + 关键字(忽略大小写,表名/注释/两端字段名/备注);
// 命中行按副表表名排序——副表是批量圈选的主要操作对象,同名聚拢便于逐条处理
const filteredRows = computed(() => {
  const kw = tableFilter.value.trim().toLowerCase()
  const list = rows.value.filter((r) => {
    if (statusFilter.value && r.status !== statusFilter.value) return false
    if (dupFilter.value && (r.status === 'REJECTED' || !dupPairKeys.value.has(tablePairKey(r)))) return false
    if (selectedTagIds.value.length) {
      const ids = new Set(rowTags(r).map((t) => String(t.id)))
      if (!selectedTagIds.value.some((id) => ids.has(String(id)))) return false
    }
    if (kw) {
      const hay = [
        r.oneTable, commentOf(r.oneTable), r.oneColumn,
        r.manyTable, commentOf(r.manyTable), r.manyColumn,
        r.remark
      ].filter(Boolean).join('\n').toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  }).map(toViewRow)
  return sortByRelatedName(list)
})

/** 按副表表名排序(忽略大小写,同名按 id 稳定次序),返回新数组 */
function sortByRelatedName(list) {
  return [...list].sort((a, b) => {
    const x = a.related.name.toLowerCase()
    const y = b.related.name.toLowerCase()
    if (x !== y) return x < y ? -1 : 1
    return a.id - b.id
  })
}

// 当前筛选命中行的 id 集合;已勾选行不在这里即「被筛选滤除」
const filteredIds = computed(() => new Set(filteredRows.value.map((r) => r.id)))
// 已勾选但不匹配当前筛选的行:筛选不掉勾选(reserve-selection),这些行被滤掉时红色提醒、可置顶展示
const mismatchedSelected = computed(() => selection.value.filter((r) => !filteredIds.value.has(r.id)))
const mismatchCount = computed(() => mismatchedSelected.value.length)
const mismatchPinnedIds = computed(() => new Set(mismatchedSelected.value.map((r) => r.id)))
// 「置顶显示不匹配行」开关:打开时这几条固定展示在列表顶部(浅灰底与正常结果区分),勾选始终保留
const showMismatch = ref(false)

// 表格实际展示:默认=筛选结果;打开置顶开关时,已勾选的不匹配行固定在最前(自身也按副表名排序)
const displayedRows = computed(() => {
  if (!showMismatch.value || !mismatchedSelected.value.length) return filteredRows.value
  return [...sortByRelatedName(mismatchedSelected.value), ...filteredRows.value]
})

// hover 的副表表名:同名表行绿色文字高亮;同名行不足 2 行(孤行)不高亮,移出时清空
const hoverRelated = ref('')
// 展示行里各副表表名的出现次数,供 hover 高亮的「≥2 才生效」判定
const relatedNameCounts = computed(() => {
  const counts = new Map()
  for (const row of displayedRows.value) {
    counts.set(row.related.name, (counts.get(row.related.name) || 0) + 1)
  }
  return counts
})
function onRelatedEnter(row) {
  hoverRelated.value = row.related.name
}
function onRelatedLeave() {
  hoverRelated.value = ''
}

/** 关系行 → 展示行:card 为基数简称(原字段名 cardinality,供 1/N 标记换算);
 *  center 恒为主表列(中心表在库里可能是 many 侧,也要显示在主表列;无中心表时 one 侧作主表,与存储口径一致),
 *  related 为副表列 */
function toViewRow(r) {
  const card = r.cardinality || 'ONE_TO_ONE'
  if (props.centerTable === r.manyTable) {
    return { ...r, card, center: { name: r.manyTable, column: r.manyColumn, side: 'many' }, related: { name: r.oneTable, column: r.oneColumn } }
  }
  return { ...r, card, center: { name: r.oneTable, column: r.oneColumn, side: 'one' }, related: { name: r.manyTable, column: r.manyColumn } }
}

// 「选中多余连线」按钮计数:当前筛选结果按表对分组后,每组超过一条的部分(即点击后会被选中的条数)
const redundantCount = computed(() => {
  const groups = new Map()
  for (const row of filteredRows.value) {
    const k = tablePairKey(row)
    if (!groups.has(k)) groups.set(k, [])
    groups.get(k).push(row)
  }
  let n = 0
  for (const list of groups.values()) if (list.length > 1) n += list.length - 1
  return n
})

/** 勾选行的副表列高亮(选中后「要关联到哪张表」一目了然);其余列不加类名 */
function cellClassName({ row, column }) {
  return column.label === '副表' && selectedIds.value.has(row.id) ? 'rb-related-selected' : ''
}

/** 置顶展示的「已勾选但不匹配筛选」行加浅灰底,与正常筛选结果一眼区分(勾选高亮不受影响) */
function rowClassName({ row }) {
  return showMismatch.value && mismatchPinnedIds.value.has(row.id) ? 'rb-mismatch-row' : ''
}

/** 点击行任意位置切换勾选(备注列除外——那里是输入框,点了要能填字;勾选框列也不处理,避免与原生勾选双触发) */
function onRowClick(row, column) {
  if (column.type === 'selection' || column.label === '备注') return
  tableRef.value?.toggleRowSelection(row)
}

/** 选中多余连线:对当前筛选结果按表对分组,每组保留一条(已确认优先,同级按当前列表顺序),选中其余,
 *  配合「批量否决」一键清理同一对表之间的重复连线;选择前清空旧选中,避免误伤 */
async function selectRedundant() {
  const rank = { CONFIRMED: 0, CANDIDATE: 1, REJECTED: 2 }
  const groups = new Map()
  for (const row of filteredRows.value) {
    const k = tablePairKey(row)
    if (!groups.has(k)) groups.set(k, [])
    groups.get(k).push(row)
  }
  const picked = []
  for (const list of groups.values()) {
    if (list.length < 2) continue
    const kept = [...list].sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9))[0]
    for (const row of list) if (row !== kept) picked.push(row)
  }
  if (!picked.length) return
  tableRef.value?.clearSelection()
  await nextTick()
  for (const row of picked) tableRef.value?.toggleRowSelection(row, true)
}

/** 拉关系清单:有中心表时接口只返回该表参与的关系(两端任一命中),无中心表退化为整库清单;
 *  applyInitial=true(仅对话框每次打开)时顺带拉整库表清单建注释 map 供端一/端二两列,拉表标记 map(含空表标记)供「标记」列与标记筛选——两者失败均不阻塞(表名照常显示,标记筛选为空);
 *  批量操作后的重拉(applyInitial=false)保留用户当前的状态/标签/关键字过滤,只清空失效选中 */
async function loadList(applyInitial = false) {
  loading.value = true
  try {
    const q = props.db ? `?db=${encodeURIComponent(props.db)}` : ''
    const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
    const tasks = [listRelations({
      datasourceId: props.dsId,
      dbName: props.db || undefined,
      schemaName: props.schema,
      table: props.centerTable || undefined
    })]
    if (applyInitial) {
      tasks.push(request.get(`${base}/tables${q}`).catch(() => []))
      tasks.push(request.get(`${base}/table-tags${q}`).catch(() => ({})))
    }
    const [list, tables, tagMap] = await Promise.all(tasks)
    rows.value = list
    // 备注输入框以接口返回的最终备注为初值(重拉后丢弃未提交的编辑)
    const edits = {}
    for (const r of list) edits[r.id] = r.remark || ''
    remarkEdits.value = edits
    if (applyInitial) {
      const map = {}
      for (const t of tables || []) {
        if (!t?.name) continue
        map[t.name] = t.comment || ''
        map[t.name.toLowerCase()] = t.comment || ''
      }
      tableComments.value = map
      // 标记 map 的键来自 table_tag(可能带大小写差异),补一份小写键兜底
      const tags = {}
      for (const [name, list2] of Object.entries(tagMap || {})) {
        tags[name] = list2 || []
        tags[name.toLowerCase()] = list2 || []
      }
      tableTags.value = tags
      // 每次打开回到无收窄状态:状态「全部」、无标记、无关键字、不勾仅重复连线,列表即中心表/整库的完整关系清单
      statusFilter.value = ''
      selectedTagIds.value = []
      tableFilter.value = ''
      dupFilter.value = false
      showMismatch.value = false
    }
    // 重拉后旧选中行已失效,清空避免误操作;等表格用新数据渲染完再清
    await nextTick()
    tableRef.value?.clearSelection()
  } finally {
    loading.value = false
  }
}

const BATCH_ACTIONS = {
  confirm: { fn: batchConfirmRelations, done: '已确认' },
  reject: { fn: batchRejectRelations, done: '已否决' }
}

async function doBatch(action) {
  const ids = selection.value.map((r) => r.id)
  if (!ids.length) return
  // 只提交本次真正改过的备注:未动过的行不带,避免用空串覆盖推导说明/历史否决原因
  const remarks = {}
  for (const r of selection.value) {
    const edited = remarkEdits.value[r.id] ?? ''
    if (edited !== (r.remark || '')) remarks[r.id] = edited
  }
  opLoading.value = true
  try {
    const res = await BATCH_ACTIONS[action].fn(ids, remarks)
    const updated = res?.updated ?? 0
    ElMessage.success(`${BATCH_ACTIONS[action].done} ${updated} 条关系`)
    emit('done', { action, updated })
    await loadList()
  } finally {
    opLoading.value = false
  }
}

// 与 RelationEdgeDrawer 同款文案
function statusText(s) {
  return { CANDIDATE: '候选', CONFIRMED: '确认', REJECTED: '否决' }[s] || s
}
function statusTagType(s) {
  return { CANDIDATE: 'warning', CONFIRMED: 'success', REJECTED: 'info' }[s] || 'info'
}
function sourceText(s) {
  return { NAME_MATCH: '名字匹配', SEMANTIC: '语义匹配', MANUAL: '人工补充' }[s] || s
}
</script>

<style scoped>
.rb-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.rb-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* el-checkbox 自带 30px 右外边距,与筛选行 flex 间隙叠加过宽,清零交给 gap 控制 */
.rb-dup {
  margin-right: 0;
  white-space: nowrap;
}
.rb-scope {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rb-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.rb-bar :deep(.el-button) {
  margin-left: 0;
}
.rb-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 已勾选被筛选滤除的红色提醒(与「已选 N 条」并列在操作条) */
.rb-hidden-warn {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-color-danger);
}
/* 端一/端二两行:中文注释加粗为主行,「表名 [字段名]」次级色为副行;均不换行,列定宽 + 表格横向滚动保证不截断 */
.tn-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tn-cell {
  line-height: 1.4;
  min-width: 0;
  flex: 1;
}
.tn-cn {
  font-weight: 600;
  white-space: nowrap;
}
.tn-en {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
/* 重数标记:与「关系」列同款朴素圆角标签,1=唯一端(蓝)、N=多端(橙),
   主表列贴右缘(tn-mark-end)、副表列贴左缘(tn-mark-start,即「关系」列前),视觉上形成 1→N 的方向 */
.tn-mark {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  font-weight: 700;
  font-size: 12px;
  line-height: 1;
  color: var(--el-color-primary);
}
/* N(多端)改用橙色,与 1(唯一端)一眼可分;两列各自显示本端重数,真实方向不会被列位置掩盖 */
.tn-mark.is-many {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}
.tn-mark-start {
  margin-right: 2px;
}
.tn-mark-end {
  margin-left: 2px;
}
/* 标记列:多个标记小间距排列,换行不挤压 */
.rb-tag {
  margin: 0 4px 2px 0;
}
/* 整行可点(点行=切换勾选),手型提示可点;备注列是输入区,恢复默认光标 */
:deep(.el-table__body td.el-table__cell) {
  cursor: pointer;
}
:deep(td.el-table__cell.rb-remark-cell) {
  cursor: default;
}
/* 勾选行的副表列高亮:浅色底 + 中文表名着主题色,选中「这条要关联到哪张表」一眼可见;
   hover 行变色也要让位,保持勾选高亮稳定 */
:deep(td.el-table__cell.rb-related-selected),
:deep(.el-table__body tr:hover > td.el-table__cell.rb-related-selected) {
  background-color: var(--el-color-primary-light-9);
}
:deep(td.el-table__cell.rb-related-selected .tn-cn) {
  color: var(--el-color-primary);
}
/* 置顶展示的「已勾选但不匹配筛选」行:浅灰底标识其特殊性(不满足当前筛选,只因已勾选而固定置顶) */
:deep(.el-table__body tr.rb-mismatch-row > td.el-table__cell) {
  background-color: var(--el-color-info-light-9);
}
/* hover 副表:列表里所有同名表(≥2 行)的名称绿色文字高亮,同名行分布一眼可见;
   选择器带 td.el-table__cell 抬高优先级,已勾选行的主题蓝也要让位,同名高亮不漏行 */
:deep(td.el-table__cell .tn-row.is-related-hover .tn-cn) {
  color: var(--el-color-success);
  font-weight: 600;
}
:deep(td.el-table__cell .tn-row.is-related-hover .tn-en) {
  color: var(--el-color-success);
}
</style>
