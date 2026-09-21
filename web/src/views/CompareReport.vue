<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">质量报告</h3>
      <div class="toolbar-actions">
        <el-button @click="router.push(`/compare/${jobId}/diff`)">← 返回差异明细</el-button>
        <el-button plain @click="aiTraceVisible = true">AI 判定</el-button>
        <el-button v-if="job && job.status === 'DONE' && !job.archived" type="warning" plain @click="archive(true)">归档报告</el-button>
        <el-button v-if="job && job.status === 'DONE' && job.archived" plain @click="archive(false)">取消归档</el-button>
      </div>
    </div>
    <!-- AI 判定明细(任务级):大模型补配/消歧/映射/时间列/佐证字段逐批调用记录与判定结果 -->
    <CompareAiTraceDialog v-model="aiTraceVisible" :job-id="jobId" />

    <div v-loading="loading">
      <template v-if="job">
        <div class="sub-title">
          {{ job.name }} · 基准表 {{ baseTableLabel }}<template v-if="report"> · 共 {{ formatNumber(report.baseCount) }} 条</template>
        </div>

        <el-result v-if="job.status === 'RUNNING'" icon="info" title="比对进行中" sub-title="报告将在比对完成后生成" />
        <el-result v-else-if="job.status === 'FAILED'" icon="error" title="比对失败" :sub-title="job.error || '未知错误'" />
        <el-result v-else-if="job.status === 'CANCELED'" icon="warning" title="任务已取消" sub-title="可在任务列表重新比对" />

        <template v-else-if="report">
          <!-- 业务系统质量指标卡 -->
          <el-card shadow="never" class="report-card">
            <template #header>
              <div class="card-header">
                <span>业务系统质量指标</span>
              </div>
            </template>
            <el-table :data="report.targets" border>
              <el-table-column label="业务系统" min-width="140">
                <template #default="{ row }">
                  <div>{{ displayNameOf(row) }}</div>
                  <div style="color: var(--el-text-color-secondary); font-size: 12px">{{ (row.db ? row.db + '.' : '') + (row.schema || '') + '.' + row.table }}</div>
                </template>
              </el-table-column>
              <!-- 对象对齐构成:命中总数 + 编码/名称/大模型各自配上的数量(「先编码后名称+大模型归一化」下可核对补配规模) -->
              <el-table-column label="对象命中" width="120" align="center">
                <template #default="{ row }">
                  <span v-if="row.matchedCount == null" style="color: var(--el-text-color-secondary)">—</span>
                  <el-tooltip v-else placement="top" :show-after="200">
                    <template #content>
                      <div>编码对齐:{{ row.codeMatchedCount ?? row.matchedCount }} 个</div>
                      <div>名称补配:{{ row.nameMatchedCount ?? 0 }} 个</div>
                      <div>大模型补配:{{ row.aiMatchedCount ?? 0 }} 个</div>
                    </template>
                    <span>{{ row.matchedCount }} 个</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="对象覆盖率" width="110" align="center">
                <template #default="{ row }"><MetricCell :target="row" :value="percent(row.coverage)" /></template>
              </el-table-column>
              <el-table-column label="字段一致率" width="110" align="center">
                <template #default="{ row }"><MetricCell :target="row" :value="percent(row.fieldConsistency)" /></template>
              </el-table-column>
              <el-table-column label="数据完整率" width="110" align="center">
                <template #default="{ row }"><MetricCell :target="row" :value="percent(row.completeness)" /></template>
              </el-table-column>
              <el-table-column label="记录缺失" width="90" align="right">
                <template #default="{ row }"><MetricCell :target="row" :value="num(row.missingCount)" :danger="(row.missingCount ?? 0) > 0" /></template>
              </el-table-column>
              <el-table-column label="多余记录" width="90" align="right">
                <template #default="{ row }"><MetricCell :target="row" :value="num(row.extraCount)" /></template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- 问题字段排行卡:横向条,按不一致次数降序 -->
          <el-card shadow="never" class="report-card">
            <template #header>
              <div class="card-header"><span>问题字段排行</span><span class="card-tip">按各业务系统字段不一致次数合计降序(前 10)</span></div>
            </template>
            <div v-if="report.fieldIssues?.length" class="bar-list">
              <div v-for="f in report.fieldIssues" :key="f.field" class="bar-row">
                <span class="bar-name field-name" :title="f.comment ? `${f.field}(${f.comment})` : f.field">
                  {{ f.field }}<span v-if="f.comment" class="field-comment">({{ f.comment }})</span>
                </span>
                <div class="bar-track">
                  <div class="bar-fill bar-fill-danger" :style="{ width: barWidth(f.count, maxIssueCount) }" />
                </div>
                <span class="bar-num">{{ formatNumber(f.count) }}</span>
              </div>
            </div>
            <el-empty v-else description="没有不一致字段,各业务系统字段全部一致" :image-size="60" />
          </el-card>

          <!-- 记录缺失/多余卡:按业务系统两条横向条(缺失红/多余绿),宽度按相对最大值归一 -->
          <el-card shadow="never" class="report-card">
            <template #header>
              <div class="card-header"><span>记录缺失 / 多余</span><span class="card-tip">按基准表主键对齐:缺失=基准有目标无,多余=目标有基准无</span></div>
            </template>
            <div v-if="missExtraRows.length" class="bar-list">
              <div v-for="r in missExtraRows" :key="r.id" class="me-row">
                <span class="bar-name me-name" :title="r.name">{{ r.name }}</span>
                <div class="me-bars">
                  <div class="bar-track">
                    <div class="bar-fill bar-fill-danger" :style="{ width: barWidth(r.missing, maxMissExtra) }" />
                  </div>
                  <div class="bar-track">
                    <div class="bar-fill bar-fill-success" :style="{ width: barWidth(r.extra, maxMissExtra) }" />
                  </div>
                </div>
                <span class="bar-num">缺失 {{ formatNumber(r.missing) }} · 多余 {{ formatNumber(r.extra) }}</span>
              </div>
            </div>
            <el-empty v-else description="各业务系统记录与基准表完全对齐,无缺失/多余" :image-size="60" />
          </el-card>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, h, onActivated, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElTooltip } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { getCompareJob, getCompareReport, setCompareArchived } from '../api'
import { formatNumber } from '../utils/format'
import CompareAiTraceDialog from '../components/CompareAiTraceDialog.vue'

/** 指标单元格:FAILED 目标统一显示「比对失败」tooltip error;danger 时值标红 */
const MetricCell = (props) => {
  const t = props.target
  if (t.status === 'FAILED') {
    return h(ElTooltip, { content: t.error || '比对失败', placement: 'top', showAfter: 200 }, () =>
      h('span', { style: 'color: var(--el-color-danger); font-size: 12px' }, '比对失败'))
  }
  return h('span', props.danger ? { style: 'color: var(--el-color-danger); font-weight: 600' } : {}, props.value)
}
MetricCell.props = { target: Object, value: String, danger: Boolean }

const route = useRoute()
const router = useRouter()
const jobId = route.params.id

const job = ref(null)
const report = ref(null)
const loading = ref(false)
// 「AI 判定」明细弹窗显隐
const aiTraceVisible = ref(false)

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

/** 目标展示名:自定义显示名(V72)> 库描述(schema_doc)> 数据源名快照 > 「数据源 id」兜底 */
function displayNameOf(t) {
  return t.displayName || t.schemaDesc || t.dsName || `数据源 ${t.datasourceId}`
}

function num(v) {
  return v == null ? '—' : formatNumber(v)
}

const maxIssueCount = computed(() => Math.max(1, ...(report.value?.fieldIssues || []).map((f) => f.count)))

// 缺失/多余行(仅完成的业务系统参与)
const missExtraRows = computed(() =>
  (report.value?.targets || [])
    .filter((t) => t.status === 'DONE')
    .map((t) => ({ id: t.id, name: displayNameOf(t), missing: t.missingCount ?? 0, extra: t.extraCount ?? 0 }))
)
const maxMissExtra = computed(() => Math.max(1, ...missExtraRows.value.flatMap((r) => [r.missing, r.extra])))

/** 横向条宽度:按相对最大值归一,0 值给 0 */
function barWidth(v, max) {
  if (!v) return '0%'
  return `${Math.max(2, Math.round((v / max) * 100))}%`
}

async function load() {
  loading.value = true
  try {
    const d = await getCompareJob(jobId)
    job.value = d?.job || null
    if (job.value?.status === 'DONE') {
      report.value = await getCompareReport(jobId)
    }
  } finally {
    loading.value = false
  }
}

/** 归档/取消归档:成功后回任务列表 */
async function archive(archived) {
  await setCompareArchived(jobId, archived)
  ElMessage.success(archived ? '报告已归档,任务列表默认不再显示' : '已取消归档')
  router.push('/compare')
}

onMounted(load)
// 详情页签走 keep-alive:回来时刷新,反映归档/重跑后的最新状态
onActivated(load)
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

.sub-title {
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.report-card {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-tip {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

/* 问题字段排行:字段名 + 中文字段名(字段注释),放宽列宽 */
.field-name {
  width: 260px;
}
.field-comment {
  color: var(--el-text-color-secondary);
}

/* 记录缺失/多余:系统名列占行宽 70%,允许换行完整显示(不省略号截断) */
.me-name {
  width: 70% !important;
  max-width: none;
  white-space: normal !important;
  line-height: 1.4;
}

/* 横向条列表(问题字段排行 / 缺失多余共用) */
.bar-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.bar-row,
.me-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.bar-name {
  flex: none;
  width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.bar-track {
  flex: 1;
  height: 10px;
  border-radius: 5px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 5px;
  transition: width 0.3s;
}
.bar-fill-danger {
  background: var(--el-color-danger);
}
.bar-fill-success {
  background: var(--el-color-success);
}
.bar-num {
  flex: none;
  min-width: 60px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.me-bars {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
