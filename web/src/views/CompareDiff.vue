<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">差异明细</h3>
      <div class="toolbar-actions">
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

          <!-- 筛选行:差异类型 + 厂商 + 关键字 -->
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
              <el-radio-button value="">全部厂商</el-radio-button>
              <el-radio-button v-for="t in doneTargets" :key="t.id" :value="String(t.id)">{{ t.dsName || `数据源 ${t.datasourceId}` }}</el-radio-button>
            </el-radio-group>
            <el-input v-model="kwInput" size="small" clearable placeholder="对象名称或编码" style="width: 220px" />
          </div>

          <!--
            明细表格:行 = 对象(各 target 按 objectKey 合并后的并集),列 = 对象 / 基准信息 / 每厂商一列状态。
            实现取舍:diffs API 按 target 过滤分页,多厂商同屏逐行请求太重;这里对每个 DONE target 全量拉取
            (分页 5000/页循环拉完,上限与后端一致 50 万行),前端按 objectKey 内存合并、过滤与分页。
            典型量级(几百~几万行)下一次性加载比分页逐查更简单可控;超大数据量时加载会慢,以 loading 态兜底。
          -->
          <el-table :data="pagedRows" v-loading="diffsLoading" border row-key="objectKey">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand-body">
                  <!-- 字段级明细:字段清单 = 选中的全部比对字段;DIFF/MISSING/EXTRA 行的 diff_json 都是整行快照,
                       所以每格都显示真实值(该侧确实不存在值时显示「(空)」);问题单元格整格底色高亮:
                       DIFF=不一致字段的目标格;缺失=目标整列(空侧);多余=基准整列(空侧) -->
                  <el-table v-if="expandRows(row).length" :data="expandRows(row)" size="small" border :cell-class-name="diffCellClass">
                    <el-table-column type="index" label="序号" width="60" align="center" />
                    <el-table-column prop="field" label="字段" width="180" />
                    <el-table-column label="基准值" min-width="160">
                      <template #default="{ row: r }">{{ r.baseKnown ? displayVal(r.base) : '—' }}</template>
                    </el-table-column>
                    <el-table-column v-for="t in visibleTargets" :key="t.id" :label="t.dsName || `数据源 ${t.datasourceId}`" min-width="160">
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
            <el-table-column label="对象" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <!-- 对象名是定位差异行的主信息,加粗着重;编码保持次级小字 -->
                <div class="object-name">{{ row.objectName || '(无名称)' }}</div>
                <div class="object-key">
                  {{ row.objectKey }}
                  <!-- 匹配逻辑 2/3 下标注该对象是靠名称还是大模型对齐上的,便于人工复核配对是否合理 -->
                  <el-tag v-if="matchByOf(row)" size="small" type="warning" effect="plain" class="match-by-tag">
                    {{ matchByOf(row) }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="基准信息" min-width="180" show-overflow-tooltip>
              <template #header>
                <div>基准信息</div>
                <div class="col-sub">{{ baseTableLabel }}</div>
              </template>
              <template #default="{ row }">
                <!-- 基准信息:DIFF 行摘取首个「真正不一致」字段的基准值;缺失/多余/一致行回落到主键定位串 -->
                <span v-if="firstDiff(row)">{{ firstDiff(row).field }} = {{ displayVal(firstDiff(row).base) }}</span>
                <span v-else style="color: var(--el-text-color-secondary)">{{ job.keyField }} = {{ row.objectKey }}</span>
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
                  <el-tooltip :content="tableLabel(t)" placement="top" :show-after="300">
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
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { getCompareJob, getCompareReport, listCompareDiffs, rerunCompareJob } from '../api'
import { formatDateTime, formatNumber } from '../utils/format'

const route = useRoute()
const router = useRouter()
const jobId = route.params.id

const job = ref(null)
const targets = ref([])
const report = ref(null)
const loading = ref(false)
const diffsLoading = ref(false)
let timer = null

// 筛选:差异类型(''/SAME/NOT_SAME/DIFF/MISSING/EXTRA,默认 NOT_SAME「差异」只展示非完全一致行)、
// 厂商('' = 全部)、关键字(防抖 300ms);NOT_SAME 为组合筛选:该对象在所选范围内存在任一非 SAME 的厂商(含 DIFF/MISSING/EXTRA)
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

// 合并后的全部行:[{ objectKey, objectName, byTarget: { targetId: { diffType, diffs } } }]
const mergedRows = ref([])

const doneTargets = computed(() => targets.value.filter((t) => t.status === 'DONE'))
const failedTargets = computed(() => targets.value.filter((t) => t.status === 'FAILED'))
// 选了具体厂商时只显示该厂商列
const visibleTargets = computed(() =>
  targetFilter.value ? doneTargets.value.filter((t) => String(t.id) === targetFilter.value) : doneTargets.value
)

/** 表定位串:db.schema.table(库/模式可空时省略对应段);表头第二行与厂商单元格 tooltip 共用 */
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
  EXACT: '编码+名称都相等',
  CODE_THEN_NAME: '先编码后名称',
  CODE_NAME_LLM: '编码/名称+大模型归一化'
}

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

/** 厂商单元格状态 tag:一致绿 / N 项不一致橙 / 缺失红 / 多余灰 */
function statusTag(t) {
  switch (t.diffType) {
    case 'SAME': return { type: 'success', text: '一致' }
    case 'DIFF': return { type: 'warning', text: `${(t.diffs || []).filter(isFieldMismatch).length} 项不一致` }
    case 'MISSING': return { type: 'danger', text: '缺失' }
    case 'EXTRA': return { type: 'info', text: '多余' }
    default: return { type: 'info', text: t.diffType || '—' }
  }
}

/** 行首个「真正不一致」字段,用于「基准信息」摘要(一致字段不再抢占摘要位) */
function firstDiff(row) {
  for (const t of visibleTargets.value) {
    const d = (row.byTarget[t.id]?.diffs || []).find(isFieldMismatch)
    if (d) return d
  }
  return null
}

/**
 * 展开行 → 字段级明细:字段 / 基准值 / 各厂商值。
 * DIFF/MISSING/EXTRA 行的 diff_json 都是整行快照,因此每格都能取到真实值
 * (该侧确实不存在的值为 null,界面显示「(空)」);SAME 行不落快照,取不到值时显示「一致」。
 * 无任何快照(全 SAME / 旧格式的 MISSING、EXTRA)时返回空数组,回落到 expandTip 文案。
 */
function expandRows(row) {
  const fields = job.value?.fields || []
  const snapOf = (t) => row.byTarget[t.id]?.diffs || []
  // 该行是否存在整行快照
  if (!doneTargets.value.some((t) => snapOf(t).length)) return []
  // 各字段基准值:任一厂商快照里的 base(各厂商基准一致)
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
        // SAME 行不落快照,没有真实值可示
        perTarget[t.id] = { known: false, mismatch: false }
        continue
      }
      const d = snapOf(t).find((x) => x.field === f)
      if (!d) {
        perTarget[t.id] = { known: false, mismatch: false }
        continue
      }
      if (st.diffType === 'MISSING') {
        // 目标整行不存在:目标侧显示「(空)」并高亮「空的那一侧」
        perTarget[t.id] = { known: true, value: null, mismatch: true }
      } else if (st.diffType === 'EXTRA') {
        // 基准整行不存在:基准侧显示「(空)」并高亮「空的那一侧」
        baseMismatch = true
        perTarget[t.id] = { known: true, value: d.value, mismatch: false }
      } else {
        // 旧契约(matched 缺失)中 value=null 表示「与基准一致」,回填基准值,老任务也能显示真实值
        const value = d.matched == null && d.value == null ? d.base : d.value
        perTarget[t.id] = { known: true, value, mismatch: isFieldMismatch(d) }
      }
    }
    return { field: f, base: baseOf[f] ?? null, baseKnown: f in baseOf, baseMismatch, perTarget }
  })
}

/**
 * 字段级明细单元格样式:问题单元格给整格底色高亮(与导出核对表同款观感)。
 * columnIndex 0=序号 / 1=字段 / 2=基准值 / 3 起为厂商列,顺序与 visibleTargets 一致
 * (列结构改动时这里要同步改;前置列数 = 3)
 * 高亮口径:基准值列=该行多余(基准侧为空);厂商列=不一致字段,或该对象缺失(目标侧为空)
 */
function diffCellClass({ row: r, columnIndex }) {
  if (columnIndex === 2) return r.baseMismatch ? 'mismatch-cell' : ''
  const t = visibleTargets.value[columnIndex - 3]
  return t && r.perTarget[t.id]?.mismatch ? 'mismatch-cell' : ''
}

/** 无字段明细可展示时的提示(按该行各厂商状态归纳) */
function expandTip(row) {
  const types = new Set(Object.values(row.byTarget).map((s) => s.diffType))
  if (types.size === 1 && types.has('SAME')) return '该对象在所有比对厂商完全一致'
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
 * 差异类型命中:NOT_SAME(差异)= 该对象在筛选范围内存在任一非 SAME 的厂商;
 * 指定厂商时只看该厂商(该厂商无此行时不命中),全部厂商时任一厂商命中即保留
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

/** 筛选后的行:指定厂商时按该厂商状态过滤,全部厂商时任一厂商命中即保留 */
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

async function load() {
  loading.value = true
  try {
    const d = await getCompareJob(jobId)
    job.value = d?.job || null
    targets.value = d?.targets || []
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

/** 拉取全部 DONE 厂商的差异明细并内存合并(每厂商 5000/页循环拉完) */
async function loadAllDiffs() {
  diffsLoading.value = true
  mergedRows.value = []
  try {
    const perTarget = await Promise.all(doneTargets.value.map((t) => fetchTargetDiffs(t.id)))
    const map = new Map()
    doneTargets.value.forEach((t, i) => {
      for (const r of perTarget[i]) {
        const key = String(r.objectKey ?? '')
        let row = map.get(key)
        if (!row) {
          row = { objectKey: key, objectName: r.objectName || '', byTarget: {} }
          map.set(key, row)
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

/** 单厂商全量差异:5000/页循环拉取(与后端单侧 50 万行上限配套,封顶 100 页防失控) */
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

/** 导出比对报告:总览 sheet + 每差异行一 sheet(GET xlsx 流,不走 axios) */
function exportDiffs() {
  const a = document.createElement('a')
  a.href = `/api/compare-jobs/${jobId}/export`
  a.click()
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

/* 表头第二行:表定位串(基准表/各目标表),常规字重 + 次级色(表头本身是 600 粗体) */
.col-sub {
  font-size: 11px;
  font-weight: 400;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}

.expand-body {
  padding: 8px 24px;
}
.expand-tip {
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

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
