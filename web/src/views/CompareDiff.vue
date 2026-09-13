<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">差异明细</h3>
      <div class="toolbar-actions">
        <el-button type="primary" plain @click="router.push(`/compare/${jobId}/report`)">查看质量报告 →</el-button>
        <el-button v-if="job && (job.status === 'DONE' || job.status === 'FAILED')" @click="confirmRerun">重新比对</el-button>
        <el-button v-if="job && job.status === 'DONE'" @click="exportDiffs">导出差异表</el-button>
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
                  <!-- 字段级明细:仅 DIFF 行 diffs 有值(后端契约);能取到基准值的字段全量展示,不一致值红色高亮 -->
                  <el-table v-if="expandRows(row).length" :data="expandRows(row)" size="small" border>
                    <el-table-column prop="field" label="字段" width="180" />
                    <el-table-column label="基准值" min-width="160">
                      <template #default="{ row: r }">{{ r.baseKnown ? displayVal(r.base) : '—' }}</template>
                    </el-table-column>
                    <el-table-column v-for="t in visibleTargets" :key="t.id" :label="t.dsName || `数据源 ${t.datasourceId}`" min-width="160">
                      <template #default="{ row: r }">
                        <span v-if="!r.perTarget[t.id]" style="color: var(--el-text-color-secondary)">—</span>
                        <span v-else-if="r.perTarget[t.id].missing" style="color: var(--el-text-color-secondary)">缺失</span>
                        <span v-else-if="r.perTarget[t.id].extra" style="color: var(--el-text-color-secondary)">多余</span>
                        <span v-else-if="!r.perTarget[t.id].known" style="color: var(--el-text-color-secondary)">—</span>
                        <span v-else :class="{ 'mismatch-text': r.perTarget[t.id].mismatch }">{{ displayVal(r.perTarget[t.id].value) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="expand-tip">{{ expandTip(row) }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="对象" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.objectName || '(无名称)' }}</div>
                <div style="color: var(--el-text-color-secondary); font-size: 12px">{{ row.objectKey }}</div>
              </template>
            </el-table-column>
            <el-table-column label="基准信息" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <!-- 基准值只在 DIFF 行 diffs 里有;摘取首个不一致字段的基准值,其余行显示主键值 -->
                <span v-if="firstDiff(row)">{{ firstDiff(row).field }} = {{ displayVal(firstDiff(row).base) }}</span>
                <span v-else style="color: var(--el-text-color-secondary)">{{ job.keyField }} = {{ row.objectKey }}</span>
              </template>
            </el-table-column>
            <el-table-column v-for="t in visibleTargets" :key="t.id" :label="t.dsName || `数据源 ${t.datasourceId}`" min-width="120" align="center">
              <template #default="{ row }">
                <template v-if="row.byTarget[t.id]">
                  <el-tooltip :content="`${t.db ? t.db + '.' : ''}${t.schema || ''}.${t.table}`" placement="top" :show-after="300">
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

// 筛选:差异类型(''/SAME/DIFF/MISSING/EXTRA)、厂商('' = 全部)、关键字(防抖 300ms)
const diffType = ref('')
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

const baseTableLabel = computed(() => {
  if (!job.value) return ''
  const j = job.value
  const schemaPart = j.baseDb ? `${j.baseDb}.${j.baseSchema || ''}` : (j.baseSchema || '')
  return schemaPart ? `${schemaPart}.${j.baseTable}` : j.baseTable
})

/** 0~1 小数 → 百分比文案(后端四比率均为小数) */
function percent(v) {
  return v == null ? '—' : `${(v * 100).toFixed(1)}%`
}

function displayVal(v) {
  return v == null || v === '' ? '(空)' : v
}

/** 厂商单元格状态 tag:一致绿 / N 项不一致橙 / 缺失红 / 多余灰 */
function statusTag(t) {
  switch (t.diffType) {
    case 'SAME': return { type: 'success', text: '一致' }
    case 'DIFF': return { type: 'warning', text: `${(t.diffs || []).length} 项不一致` }
    case 'MISSING': return { type: 'danger', text: '缺失' }
    case 'EXTRA': return { type: 'info', text: '多余' }
    default: return { type: 'info', text: t.diffType || '—' }
  }
}

/** 行首个不一致字段(取第一个有 diffs 的厂商),用于「基准信息」摘要 */
function firstDiff(row) {
  for (const t of visibleTargets.value) {
    const d = row.byTarget[t.id]?.diffs
    if (d && d.length) return d[0]
  }
  return null
}

/** 展开行 → 字段级明细:字段 / 基准值 / 各厂商值;值只在 DIFF 行 diffs 里,一致字段无基准值可示(标「—」) */
function expandRows(row) {
  const fields = job.value?.fields || []
  // 各字段基准值:任一厂商 diffs 里的 base(各厂商基准一致)
  const baseOf = {}
  for (const t of doneTargets.value) {
    for (const d of row.byTarget[t.id]?.diffs || []) {
      if (!(d.field in baseOf)) baseOf[d.field] = d.base
    }
  }
  const rows = []
  for (const f of fields) {
    const perTarget = {}
    let anyMismatch = false
    for (const t of doneTargets.value) {
      const st = row.byTarget[t.id]
      if (!st) continue
      if (st.diffType === 'MISSING') { perTarget[t.id] = { missing: true }; continue }
      if (st.diffType === 'EXTRA') { perTarget[t.id] = { extra: true }; continue }
      const d = (st.diffs || []).find((x) => x.field === f)
      if (d) {
        perTarget[t.id] = { value: d.value, mismatch: true, known: true }
        anyMismatch = true
      } else {
        // 该厂商该字段一致:值等于基准值(基准值未知时标 known=false,界面显示「—」)
        perTarget[t.id] = { value: baseOf[f] ?? null, mismatch: false, known: f in baseOf }
      }
    }
    // 只展示至少一个厂商不一致、或能取到基准值的字段;全未知字段无信息可示
    if (anyMismatch || f in baseOf) {
      rows.push({ field: f, base: baseOf[f] ?? null, baseKnown: f in baseOf, perTarget })
    }
  }
  return rows
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

/** 筛选后的行:指定厂商时按该厂商状态过滤,全部厂商时任一厂商命中即保留 */
const filteredRows = computed(() => {
  const k = kw.value.trim().toLowerCase()
  return mergedRows.value.filter((row) => {
    if (k) {
      const hit = (row.objectName || '').toLowerCase().includes(k) || (row.objectKey || '').toLowerCase().includes(k)
      if (!hit) return false
    }
    if (!diffType.value) return true
    if (targetFilter.value) return row.byTarget[targetFilter.value]?.diffType === diffType.value
    return Object.values(row.byTarget).some((s) => s.diffType === diffType.value)
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
        row.byTarget[t.id] = { diffType: r.diffType, diffs: r.diffs || null }
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

/** 导出差异表:浏览器直接下载(GET xlsx 流),不走 axios */
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

.expand-body {
  padding: 8px 24px;
}
.expand-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 不一致值红色高亮 */
.mismatch-text {
  color: var(--el-color-danger);
  font-weight: 600;
}

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
