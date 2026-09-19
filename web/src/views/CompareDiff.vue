<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">差异明细</h3>
      <div class="toolbar-actions">
        <el-button v-if="job" @click="mappingVisible = true">字段映射关系</el-button>
        <el-button type="primary" plain @click="router.push(`/compare/${jobId}/report`)">查看质量报告 →</el-button>
        <el-button v-if="job && (job.status === 'DONE' || job.status === 'FAILED')" @click="confirmRerun">重新比对</el-button>
        <!-- 导出 xlsx:首 sheet「总览」一行一个系统,其后每个差异行一个 sheet 展开字段级明细 -->
        <el-button v-if="job && job.status === 'DONE'" @click="exportDiffs">导出比对报告</el-button>
      </div>
    </div>

    <div v-loading="loading">
      <template v-if="job">
        <!-- 任务信息条 -->
        <div class="info-bar">
          <span>任务:{{ job.name }}(T-{{ job.id }})</span>
          <span>数据源:{{ job.baseDatasourceName || `数据源 ${job.baseDatasourceId}` }}</span>
          <span>基准表:{{ baseTableLabel }}</span>
          <span>比对主键:{{ job.keyField }}</span>
          <span>对象名称:{{ job.displayField || '自动(第一个文本型字段)' }}</span>
          <span>匹配逻辑:{{ matchModeLabel(job.matchMode) }}</span>
          <span>对比模式:{{ job.compareMode === 'COLUMN' ? '行级+列级对比' : '行级对比' }}</span>
          <span>上次比对:{{ formatDateTime(job.finishedAt) }}</span>
        </div>

        <!-- 运行中:进度 + 1s 轮询直到结束 -->
        <el-result v-if="job.status === 'RUNNING'" icon="info" title="比对进行中" :sub-title="`${job.stage || '运行中'} · ${job.progressPercent || 0}%`">
          <template #extra>
            <el-progress :percentage="job.progressPercent || 0" style="width: 320px" />
          </template>
        </el-result>
        <el-result v-else-if="job.status === 'FAILED'" icon="error" title="比对失败" :sub-title="job.error || '未知错误'" />
        <el-result v-else-if="job.status === 'CANCELED'" icon="warning" title="任务已取消" sub-title="可在任务列表重新比对" />

        <template v-else-if="job.status === 'DONE'">
          <!-- 汇总四卡(来自 report 接口) -->
          <div v-if="report" class="stat-cards">
            <div class="stat-card">
              <div class="stat-num">{{ formatNumber(report.baseCount) }}</div>
              <div class="stat-label">基准对象</div>
            </div>
            <div class="stat-card">
              <div class="stat-num" style="color: var(--el-color-success)">{{ formatNumber(report.sameCount) }}</div>
              <div class="stat-label">完全一致</div>
            </div>
            <div class="stat-card">
              <div class="stat-num" style="color: var(--el-color-warning)">{{ formatNumber(report.diffObjectCount) }}</div>
              <div class="stat-label">存在差异</div>
            </div>
            <div class="stat-card">
              <div class="stat-num">{{ percent(report.avgFieldConsistency) }}</div>
              <div class="stat-label">平均字段一致率</div>
            </div>
          </div>

          <el-alert v-if="failedTargets.length" type="warning" :closable="false" style="margin-bottom: 12px">
            <template #title>
              {{ failedTargets.map((t) => t.dsName || `数据源 ${t.datasourceId}`).join('、') }} 比对失败,未参与下方明细展示
            </template>
          </el-alert>

          <!-- 筛选行:差异类型 + 业务系统 + 关键字 -->
          <div class="filter-bar">
            <el-radio-group v-model="diffType" size="small">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="SAME">完全一致</el-radio-button>
              <el-radio-button value="NOT_SAME">差异</el-radio-button>
              <el-radio-button value="DIFF">字段不一致</el-radio-button>
              <el-radio-button value="MISSING">对象缺失</el-radio-button>
              <el-radio-button value="EXTRA">对象多余</el-radio-button>
            </el-radio-group>
            <el-radio-group v-model="targetFilter" size="small">
              <el-radio-button value="">全部业务系统</el-radio-button>
              <el-radio-button v-for="t in doneTargets" :key="t.id" :value="String(t.id)">{{ t.dsName || `数据源 ${t.datasourceId}` }}</el-radio-button>
            </el-radio-group>
            <el-input v-model="kwInput" size="small" clearable placeholder="对象名称或编码" style="width: 220px" />
          </div>

          <!--
            明细表格:行 = 对象(各 target 按 objectKey 合并后的并集),列 = 对象(基准信息) / 每业务系统一列状态。
            实现取舍:diffs API 按 target 过滤分页,多业务系统同屏逐行请求太重;这里对每个 DONE target 全量拉取
            (分页 5000/页循环拉完,上限与后端一致 50 万行),前端按 objectKey 内存合并、过滤与分页。
            典型量级(几百~几万行)下一次性加载比分页逐查更简单可控;超大数据量时加载会慢,以 loading 态兜底。
          -->
          <el-table :data="pagedRows" v-loading="diffsLoading" border :row-key="(row) => row.rowKey">
            <!-- 展开列定宽 48:子表「序号+字段+基准值」三列总宽 = 48 + 对象列宽(360) = 408,
                 两侧业务系统列 flex 规则相同(min-width 140),展开子表与父表的列边界才能对齐 -->
            <el-table-column type="expand" width="48">
              <template #default="{ row }">
                <div class="expand-body">
                  <!-- 字段级明细:字段清单 = 选中的全部比对字段;DIFF/MISSING/EXTRA 行的 diff_json 都是整行快照,
                       所以每格都显示真实值(该侧确实不存在值时显示「(空)」);问题单元格整格底色高亮:
                       红底=不一致字段的目标格 / 缺失=目标整列(空侧)/ 多余=基准整列(空侧);
                       蓝灰底=字段缺失(目标表无此列,«字段缺失»);绿底绿字=一致 -->
                  <el-table v-if="expandRows(row).length" :data="expandRows(row)" size="small" border :cell-class-name="diffCellClass">
                    <el-table-column type="index" label="序号" width="60" align="center" />
                    <el-table-column prop="field" label="字段" width="180" />
                    <!-- 60 + 180 + 168 = 408 = 父表展开列(48) + 对象列(360),基准值右边界与父表对象列右边界对齐 -->
                    <el-table-column label="基准值" width="168" show-overflow-tooltip>
                      <template #default="{ row: r }">{{ r.baseKnown ? displayVal(r.base) : '—' }}</template>
                    </el-table-column>
                    <!-- 业务系统列 min-width 与父表一致(140):两表同宽且 flex 规则相同,右边界逐列对齐 -->
                    <el-table-column v-for="t in visibleTargets" :key="t.id" :label="t.dsName || `数据源 ${t.datasourceId}`" min-width="140">
                      <template #default="{ row: r }">
                        <span v-if="!r.perTarget[t.id]" style="color: var(--el-text-color-secondary)">—</span>
                        <span v-else-if="!r.perTarget[t.id].known" style="color: var(--el-text-color-secondary)">一致</span>
                        <span v-else>{{ displayVal(r.perTarget[t.id].value) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="expand-tip">{{ expandTip(row) }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="对象" width="360" show-overflow-tooltip>
              <template #header>
                <!-- 对象列即基准侧对象:表头直接带基准表定位串(原「基准信息」列已并入本列) -->
                <div>基准信息</div>
                <div class="col-sub">{{ baseTableLabel }}</div>
              </template>
              <template #default="{ row }">
                <!-- 对象名是定位差异行的主信息,加粗着重;编码保持次级小字 -->
                <div class="object-name">{{ row.objectName || '(无名称)' }}</div>
                <div class="object-key">
                  {{ row.objectKey }}
                  <!-- 「先编码后名称+大模型归一化」下标注该对象是靠名称还是大模型对齐上的,便于人工复核配对是否合理 -->
                  <el-tag v-if="matchByOf(row)" size="small" type="warning" effect="plain" class="match-by-tag">
                    {{ matchByOf(row) }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column v-for="t in visibleTargets" :key="t.id" :label="t.dsName || `数据源 ${t.datasourceId}`" min-width="140" align="center">
              <template #header>
                <!-- 同一数据源可能有多个目标表,表头补一行目标表定位串(悬浮看全串) -->
                <el-tooltip :content="tableLabel(t)" placement="top" :show-after="300">
                  <div>
                    <div>{{ t.dsName || `数据源 ${t.datasourceId}` }}</div>
                    <div class="col-sub">{{ tableLabel(t) }}</div>
                  </div>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <template v-if="row.byTarget[t.id]">
                  <!-- DIFF 行:字段级统计拆成 3 个小 tag 一排展示(一致绿/不一致橙/字段缺失灰);
                       无快照(老数据)或其他状态仍用状态 tag -->
                  <div v-if="fieldStatParts(row.byTarget[t.id])" class="cell-field-stat">
                    <el-tag v-for="p in fieldStatParts(row.byTarget[t.id])" :key="p.text" :type="p.type" size="small">{{ p.text }}</el-tag>
                  </div>
                  <el-tooltip v-else :content="tableLabel(t)" placement="top" :show-after="300">
                    <el-tag :type="statusTag(row.byTarget[t.id]).type" size="small">{{ statusTag(row.byTarget[t.id]).text }}</el-tag>
                  </el-tooltip>
                </template>
                <span v-else style="color: var(--el-text-color-secondary)">—</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有符合条件的差异记录" :image-size="60" />
            </template>
          </el-table>
          <div class="pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="size"
              :total="filteredRows.length"
              :page-sizes="[20, 50, 100, 200]"
              layout="total, sizes, prev, pager, next, jumper"
              background
            />
          </div>
        </template>
      </template>
    </div>

    <!-- 字段映射关系:只读展示基准表 ↔ 各对比表的字段连线(与新建向导第三步同一块画布);
         v-if 懒挂载——每次打开都重新拉字段与注释,关掉即销毁 -->
    <el-dialog v-model="mappingVisible" title="字段映射关系" width="94%" top="5vh" class="mapping-dialog">
      <CompareMappingView v-if="mappingVisible && job" :job="job" :targets="targets" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { getCompareJob, getCompareReport, listCompareDiffs, rerunCompareJob, exportCompareReport } from '../api'
import { formatDateTime, formatNumber } from '../utils/format'
import { notifyExportSaved } from '../utils/download'
import { ackTask } from '../stores/backgroundTasks'
import CompareMappingView from '../components/CompareMappingView.vue'

const route = useRoute()
const router = useRouter()
const jobId = route.params.id

const job = ref(null)
const targets = ref([])
const report = ref(null)
const loading = ref(false)
const diffsLoading = ref(false)
// 「字段映射关系」弹窗开关(只读图,映射数据来自任务详情 targets[].mapping)
const mappingVisible = ref(false)
let timer = null

// 筛选:差异类型(''/SAME/NOT_SAME/DIFF/MISSING/EXTRA,默认 NOT_SAME「差异」只展示非完全一致行)、
// 业务系统('' = 全部)、关键字(防抖 300ms);NOT_SAME 为组合筛选:该对象在所选范围内存在任一非 SAME 的业务系统(含 DIFF/MISSING/EXTRA)
const diffType = ref('NOT_SAME')
const targetFilter = ref('')
const kwInput = ref('')
const kw = ref('')
let kwTimer = null
watch(kwInput, (v) => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => { kw.value = v }, 300)
})

// 前端分页
const page = ref(1)
const size = ref(20)

// 合并后的全部行:[{ rowKey(对象标识[+目标内同名序号],同目标多条同名不折叠、跨系统仍并一行多列), objectKey, objectName, byTarget: { targetId: { diffType, diffs } } }]
const mergedRows = ref([])

const doneTargets = computed(() => targets.value.filter((t) => t.status === 'DONE'))
const failedTargets = computed(() => targets.value.filter((t) => t.status === 'FAILED'))
// 选了具体业务系统时只显示该业务系统列
const visibleTargets = computed(() =>
  targetFilter.value ? doneTargets.value.filter((t) => String(t.id) === targetFilter.value) : doneTargets.value
)

/** 表定位串:db.schema.table(库/模式可空时省略对应段);表头第二行与业务系统单元格 tooltip 共用 */
function tableLabel(t) {
  const schemaPart = t.db ? `${t.db}.${t.schema || ''}` : (t.schema || '')
  return schemaPart ? `${schemaPart}.${t.table}` : t.table
}

const baseTableLabel = computed(() => {
  if (!job.value) return ''
  const j = job.value
  return tableLabel({ db: j.baseDb, schema: j.baseSchema, table: j.baseTable })
})

/** 0~1 小数 → 百分比文案(后端四比率均为小数) */
function percent(v) {
  return v == null ? '—' : `${(v * 100).toFixed(1)}%`
}

function displayVal(v) {
  return v == null || v === '' ? '(空)' : v
}

/**
 * 字段是否不一致:新契约看显式 matched;旧数据/中间版本(matched 缺失)按旧契约
 * 「value 非空即不一致」解读,保证历史任务展示不回归。
 */
function isFieldMismatch(d) {
  return d.matched == null ? d.value != null : d.matched === false
}

/** 匹配逻辑文案(与后端 CompareService.MatchMode 同一取值;老任务 null = 仅编码) */
const MATCH_MODE_LABELS = {
  EXACT: '编码+名称',
  CODE_THEN_NAME: '先编码后名称',
  CODE_NAME_LLM: '先编码后名称+大模型归一化'
}

// 目标表缺列的取值标记(与后端 CompareService.MISSING_COLUMN_MARK 同一字符串)
const MISSING_COLUMN_MARK = '«字段缺失»'

function matchModeLabel(mode) {
  return MATCH_MODE_LABELS[mode] || '仅编码(老任务)'
}

/** 该对象的对齐来源标注:NAME=按名称补配、LLM=大模型归一化补配;编码对齐(或老数据)不标注 */
function matchByOf(row) {
  for (const t of doneTargets.value) {
    const by = row.byTarget[t.id]?.matchBy
    if (by === 'NAME') return '名称配对'
    if (by === 'LLM') return 'AI 配对'
  }
  return ''
}

/**
 * 字段级统计(DIFF 行的整行快照):一致 / 不一致 / 字段缺失(目标表无此列,值 = «字段缺失»)。
 * 字段缺失不算进「不一致」——列都没有,谈不上值不一致;无快照(SAME 行/老数据)返回 null
 */
function fieldStat(st) {
  const diffs = st?.diffs
  if (st?.diffType !== 'DIFF' || !diffs?.length) return null
  let same = 0, diff = 0, missing = 0
  for (const d of diffs) {
    if (d.value === MISSING_COLUMN_MARK) missing++
    else if (isFieldMismatch(d)) diff++
    else same++
  }
  return { same, diff, missing }
}

/** 单元格统计分段:DIFF 行拆 3 个小 tag(一致绿/不一致橙/字段缺失灰);字段缺失为 0 时省略该项 */
function fieldStatParts(st) {
  const s = fieldStat(st)
  if (!s) return null
  const parts = [
    { text: `一致 ${s.same}`, type: 'success' },
    { text: `不一致 ${s.diff}`, type: 'warning' }
  ]
  if (s.missing) parts.push({ text: `字段缺失 ${s.missing}`, type: 'info' })
  return parts
}

/** 业务系统单元格状态 tag:一致绿 / N 项不一致橙(不含字段缺失,缺失列数在统计行单独列) / 缺失红 / 多余灰 */
function statusTag(t) {
  switch (t.diffType) {
    case 'SAME': return { type: 'success', text: '一致' }
    case 'DIFF': {
      const s = fieldStat(t)
      const n = s ? s.diff : (t.diffs || []).filter(isFieldMismatch).length
      return { type: 'warning', text: `${n} 项不一致` }
    }
    case 'MISSING': return { type: 'danger', text: '缺失' }
    case 'EXTRA': return { type: 'info', text: '多余' }
    default: return { type: 'info', text: t.diffType || '—' }
  }
}

/**
 * 展开行 → 字段级明细:字段 / 基准值 / 各业务系统值。
 * DIFF/MISSING/EXTRA 行的 diff_json 都是整行快照,因此每格都能取到真实值
 * (该侧确实不存在的值为 null,界面显示「(空)」);SAME 行不落快照,取不到值时显示「一致」。
 * 无任何快照(全 SAME / 旧格式的 MISSING、EXTRA)时返回空数组,回落到 expandTip 文案。
 */
function expandRows(row) {
  const fields = job.value?.fields || []
  const snapOf = (t) => row.byTarget[t.id]?.diffs || []
  // 该行是否存在整行快照
  if (!doneTargets.value.some((t) => snapOf(t).length)) return []
  // 各字段基准值:任一业务系统快照里的 base(各业务系统基准一致)
  const baseOf = {}
  for (const t of doneTargets.value) {
    for (const d of snapOf(t)) {
      if (!(d.field in baseOf)) baseOf[d.field] = d.base
    }
  }
  return fields.map((f) => {
    const perTarget = {}
    let baseMismatch = false
    for (const t of doneTargets.value) {
      const st = row.byTarget[t.id]
      if (!st) continue
      if (st.diffType === 'SAME') {
        // SAME 行不落快照,没有真实值可示;整行一致,格子按一致(绿)标
        perTarget[t.id] = { known: false, mismatch: false, match: true }
        continue
      }
      const d = snapOf(t).find((x) => x.field === f)
      if (!d) {
        // 快照里没有该字段(老格式),界面显示「一致」,同样按一致(绿)标;多余行不标绿
        perTarget[t.id] = { known: false, mismatch: false, match: st.diffType === 'DIFF' }
        continue
      }
      if (st.diffType === 'MISSING') {
        // 目标整行不存在:目标侧显示「(空)」并高亮「空的那一侧」
        perTarget[t.id] = { known: true, value: null, mismatch: true }
      } else if (st.diffType === 'EXTRA') {
        // 基准整行不存在:基准侧显示「(空)」并高亮「空的那一侧」(目标侧是基准没有的值,不标绿)
        baseMismatch = true
        perTarget[t.id] = { known: true, value: d.value, mismatch: false }
      } else {
        // 旧契约(matched 缺失)中 value=null 表示「与基准一致」,回填基准值,老任务也能显示真实值
        const value = d.matched == null && d.value == null ? d.base : d.value
        // 目标表无此列(«字段缺失»):单独标记,单元格用蓝灰底色与红底的不一致区分
        const missingColumn = d.value === MISSING_COLUMN_MARK
        const mismatch = isFieldMismatch(d)
        perTarget[t.id] = { known: true, value, mismatch, missingColumn, match: !mismatch && !missingColumn }
      }
    }
    return { field: f, base: baseOf[f] ?? null, baseKnown: f in baseOf, baseMismatch, perTarget }
  })
}

/**
 * 字段级明细单元格样式:问题单元格给整格底色高亮(与导出核对表同款观感)。
 * columnIndex 0=序号 / 1=字段 / 2=基准值 / 3 起为业务系统列,顺序与 visibleTargets 一致
 * (列结构改动时这里要同步改;前置列数 = 3)
 * 高亮口径:基准值列=该行多余(基准侧为空);业务系统列=字段缺失(蓝灰,目标表无此列)优先,
 * 其次不一致字段或该对象缺失(目标侧为空,红底),一致的格子绿底绿字(与统计 tag 一致)
 */
function diffCellClass({ row: r, columnIndex }) {
  if (columnIndex === 2) return r.baseMismatch ? 'mismatch-cell' : ''
  const t = visibleTargets.value[columnIndex - 3]
  const cell = t && r.perTarget[t.id]
  if (!cell) return ''
  if (cell.missingColumn) return 'missing-col-cell'
  if (cell.mismatch) return 'mismatch-cell'
  return cell.match ? 'match-cell' : ''
}

/** 无字段明细可展示时的提示(按该行各业务系统状态归纳) */
function expandTip(row) {
  const types = new Set(Object.values(row.byTarget).map((s) => s.diffType))
  if (types.size === 1 && types.has('SAME')) return '该对象在所有比对业务系统完全一致'
  const parts = []
  for (const t of doneTargets.value) {
    const st = row.byTarget[t.id]
    const name = t.dsName || `数据源 ${t.datasourceId}`
    if (st?.diffType === 'MISSING') parts.push(`${name} 缺失该对象(基准有、目标无)`)
    if (st?.diffType === 'EXTRA') parts.push(`${name} 多余该对象(目标有、基准无)`)
  }
  return parts.length ? parts.join(';') : '无字段级差异明细'
}

/**
 * 差异类型命中:NOT_SAME(差异)= 该对象在筛选范围内存在任一非 SAME 的业务系统;
 * 指定业务系统时只看该业务系统(该业务系统无此行时不命中),全部业务系统时任一业务系统命中即保留
 */
function typeHit(row, type) {
  if (type === 'NOT_SAME') {
    if (targetFilter.value) {
      const st = row.byTarget[targetFilter.value]
      return !!st && st.diffType !== 'SAME'
    }
    return Object.values(row.byTarget).some((s) => s.diffType !== 'SAME')
  }
  if (targetFilter.value) return row.byTarget[targetFilter.value]?.diffType === type
  return Object.values(row.byTarget).some((s) => s.diffType === type)
}

/** 筛选后的行:指定业务系统时按该业务系统状态过滤,全部业务系统时任一业务系统命中即保留 */
const filteredRows = computed(() => {
  const k = kw.value.trim().toLowerCase()
  return mergedRows.value.filter((row) => {
    if (k) {
      const hit = (row.objectName || '').toLowerCase().includes(k) || (row.objectKey || '').toLowerCase().includes(k)
      if (!hit) return false
    }
    if (!diffType.value) return true
    return typeHit(row, diffType.value)
  })
})

const pagedRows = computed(() => {
  const start = (page.value - 1) * size.value
  return filteredRows.value.slice(start, start + size.value)
})

// 筛选条件变化回到第一页
watch([diffType, targetFilter, kw], () => { page.value = 1 })

/** 本页已渲染到任务终态时登记 ack:全局后台任务跟踪器不再对其弹完成/失败通知(本页自己看得见) */
function ackIfTerminal() {
  if (job.value && job.value.status !== 'RUNNING') ackTask('compare', jobId)
}

async function load() {
  loading.value = true
  try {
    const d = await getCompareJob(jobId)
    job.value = d?.job || null
    targets.value = d?.targets || []
    ackIfTerminal()
    if (job.value?.status === 'RUNNING') {
      startPolling()
    } else if (job.value?.status === 'DONE') {
      await Promise.all([loadReport(), loadAllDiffs()])
    }
  } finally {
    loading.value = false
  }
}

async function loadReport() {
  try {
    report.value = await getCompareReport(jobId)
  } catch { /* 拦截器已提示 */ }
}

/** 拉取全部 DONE 业务系统的差异明细并内存合并(每业务系统 5000/页循环拉完) */
async function loadAllDiffs() {
  diffsLoading.value = true
  mergedRows.value = []
  try {
    const perTarget = await Promise.all(doneTargets.value.map((t) => fetchTargetDiffs(t.id)))
    const map = new Map()
    // 同对象在同目标内可能有多条(如编码全空时多个同名水库各自成行,只按 objectKey 合并会折叠丢行):
    // 合并键 = 对象标识 + 该目标内同名序号;序号 0 不带后缀,跨系统仍按对象标识并成一行多列
    const seen = new Map()
    doneTargets.value.forEach((t, i) => {
      for (const r of perTarget[i]) {
        const key = String(r.objectKey ?? '')
        const seenKey = `${t.id}:${key}`
        const occ = seen.get(seenKey) || 0
        seen.set(seenKey, occ + 1)
        const mapKey = occ === 0 ? key : `${key}#${occ}`
        let row = map.get(mapKey)
        if (!row) {
          row = { rowKey: mapKey, objectKey: key, objectName: r.objectName || '', byTarget: {} }
          map.set(mapKey, row)
        }
        if (!row.objectName && r.objectName) row.objectName = r.objectName
        row.byTarget[t.id] = { diffType: r.diffType, diffs: r.diffs || null, matchBy: r.matchBy || null }
      }
    })
    mergedRows.value = [...map.values()]
  } finally {
    diffsLoading.value = false
  }
}

/** 单业务系统全量差异:5000/页循环拉取(与后端单侧 50 万行上限配套,封顶 100 页防失控) */
async function fetchTargetDiffs(targetId) {
  const all = []
  const PAGE_SIZE = 5000
  for (let p = 1; p <= 100; p++) {
    const res = await listCompareDiffs(jobId, { targetId, page: p, size: PAGE_SIZE })
    all.push(...(res?.rows || []))
    if (all.length >= (res?.total || 0) || !(res?.rows || []).length) break
  }
  return all
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    const d = await getCompareJob(jobId, true).catch(() => null)
    if (!d?.job) return
    job.value = d.job
    targets.value = d.targets || []
    if (job.value.status !== 'RUNNING') {
      stopPolling()
      ackIfTerminal()
      if (job.value.status === 'DONE') await Promise.all([loadReport(), loadAllDiffs()])
    }
  }, 1000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 重新比对:确认后重跑并回任务列表观察进度 */
async function confirmRerun() {
  try {
    await ElMessageBox.confirm(
      `将按任务「${job.value.name}」原目标清单重新比对,现有差异明细与报告会被覆盖。`,
      '重新比对',
      { type: 'warning', confirmButtonText: '重新比对', cancelButtonText: '取消' }
    )
  } catch { /* 用户取消 */ return }
  await rerunCompareJob(jobId)
  ElMessage.success(`任务 T-${jobId} 已开始重新比对`)
  router.push('/compare')
}

/** 导出比对报告:总览 sheet + 每差异行一 sheet;服务端直存数据目录/compare(任务 ID 前缀命名,同名覆盖),
 * 完成后通知(可打开文件/文件夹);与任务列表「导出表格」同一接口 */
async function exportDiffs() {
  const loading = await ElMessage.info('正在导出,请稍候…', { duration: 0 })
  try {
    const saved = await exportCompareReport(jobId)
    notifyExportSaved(saved.path)
  } catch { /* 拦截器已弹错误提示 */ } finally {
    loading?.close?.()
  }
}

onMounted(load)

// 详情页签走 keep-alive:失活停轮询,回来重新拉任务状态并按需恢复
onActivated(load)
onDeactivated(stopPolling)
onUnmounted(() => {
  stopPolling()
  clearTimeout(kwTimer)
})
</script>

<style scoped>
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}

/* 任务信息条:一行平铺,次级色分隔 */
.info-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 10px 12px;
  margin-bottom: 16px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  color: var(--el-text-color-regular);
}

/* 汇总四卡 */
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.stat-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 14px 16px;
}
.stat-num {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
}
.stat-label {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

/* 对象列:对象名加粗着重(定位差异行的主信息),编码保持次级小字 */
.object-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.match-by-tag {
  margin-left: 6px;
}

.object-key {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 业务系统单元格里的字段级统计(DIFF 行):3 个小 tag 一排,允许换行防撑爆窄列 */
.cell-field-stat {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 4px;
}
.cell-field-stat :deep(.el-tag) {
  margin-left: 0;
}

/* 表头第二行:表定位串(基准表/各目标表),常规字重 + 次级色(表头本身是 600 粗体) */
.col-sub {
  font-size: 11px;
  font-weight: 400;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}

/* 展开行:展开单元格与子表容器都去左右内边距,子表与父表严格同宽,列边界才能对齐 */
:deep(td.el-table__expanded-cell) {
  padding: 0;
}
.expand-body {
  padding: 0;
}
.expand-tip {
  padding: 12px 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/*
 * 字段级明细:不一致单元格整格底色高亮 + 左侧橙色标记条(对齐导出核对表观感,文字保持常规色)。
 * 常态与悬停都要点住:EP 的 hover 行底色选择器优先级不低,这里显式带上 :hover/.hover-row 变体
 */
.expand-body :deep(.el-table__body tr > td.el-table__cell.mismatch-cell),
.expand-body :deep(.el-table__body tr:hover > td.el-table__cell.mismatch-cell),
.expand-body :deep(.el-table__body tr.hover-row > td.el-table__cell.mismatch-cell) {
  background-color: var(--dq-diff-cell-bg);
  box-shadow: inset 2px 0 0 0 var(--dq-diff-cell-accent);
}

/* 字段缺失(目标表无此列):蓝灰底色,与红底的不一致/对象缺失区分;文字用次级色弱化 */
.expand-body :deep(.el-table__body tr > td.el-table__cell.missing-col-cell),
.expand-body :deep(.el-table__body tr:hover > td.el-table__cell.missing-col-cell),
.expand-body :deep(.el-table__body tr.hover-row > td.el-table__cell.missing-col-cell) {
  background-color: var(--dq-missing-col-cell-bg);
  box-shadow: inset 2px 0 0 0 var(--dq-missing-col-cell-accent);
  color: var(--el-text-color-secondary);
}

/* 一致的格子:浅绿底 + 绿字,与字段级统计的「一致」tag 同色(el-tag success light) */
.expand-body :deep(.el-table__body tr > td.el-table__cell.match-cell),
.expand-body :deep(.el-table__body tr:hover > td.el-table__cell.match-cell),
.expand-body :deep(.el-table__body tr.hover-row > td.el-table__cell.match-cell) {
  background-color: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

/* 子表边框/表头底色用主题蓝阶,与父表的中性灰拉开对比,一眼分出父子层级(暗色主题下 EP 变量自动转暗) */
.expand-body :deep(.el-table) {
  --el-table-border-color: var(--el-color-primary-light-7);
  --el-table-header-bg-color: var(--el-color-primary-light-9);
}

/* 展开子表表头:加黑加粗,与灰底的默认表头区分 */
.expand-body :deep(.el-table__header th.el-table__cell) {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>

<style>
/* 字段映射关系弹窗被 teleport 到 body,scoped 选择器够不到弹窗自身结构,按弹窗类名全局写
   (同 Datasources 导入弹窗口径):弹窗固定 90% 高 + 纵向 flex,body 撑满后画布靠根节点 height:100% 占满 */
.mapping-dialog {
  height: 90%;
  margin-bottom: 0; /* 盖掉 EP 默认 50px 下边距,否则 5vh+90%+50px 会溢出视口 */
  display: flex;
  flex-direction: column;
}
.mapping-dialog .el-dialog__body {
  flex: 1;
  min-height: 0;
}
</style>
