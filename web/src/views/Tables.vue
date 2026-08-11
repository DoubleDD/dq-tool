<template>
  <div class="page-card">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div>
        <el-button @click="goBack">返回</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshTables">刷新</el-button>
        <el-button :loading="exporting" @click="exportReport">导出报告</el-button>
        <el-button @click="$router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/scans${dbQuery()}`)">扫描记录</el-button>
        <AiConfigDialog />
        <template v-if="!filterTagId">
          <el-button :disabled="!selectedTables.length" :loading="batchDocLoading" @click="generateDocsBatch">
            生成描述{{ selectedTables.length ? `(${selectedTables.length})` : '' }}
          </el-button>
          <el-button type="primary" @click="openScanDialog">开始扫描</el-button>
        </template>
      </div>
    </div>

    <!-- 按标记筛选的只读模式提示 -->
    <el-alert v-if="filterTagId" type="warning" :closable="false" style="margin-bottom: 12px">
      <span style="margin-right: 16px">按标记「{{ filterTagName }}」筛选中 · 只读</span>
      <el-button link type="primary" @click="clearTagFilter">清除筛选</el-button>
    </el-alert>

    <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
      <el-input v-model="keyword" placeholder="按表名或注释搜索" clearable style="width: 280px" />
      <el-select
        v-if="!filterTagId"
        v-model="selectedTagIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        placeholder="按标记筛选"
        style="width: 240px"
      >
        <el-option v-for="tag in availableTags" :key="tag.id" :label="tag.name" :value="String(tag.id)">
          <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle" :style="{ background: tag.color }" />
          {{ tag.name }}
        </el-option>
      </el-select>
      <el-checkbox v-model="onlyEmpty">只看空表(行数为 0)</el-checkbox>
    </div>

    <el-alert v-if="tables.length" type="success" :closable="false" style="margin-bottom: 12px">
      <span style="margin-right: 24px">总行数: 约 {{ formatNumber(totalEstRows) }}</span>
      <span style="margin-right: 24px">总大小: {{ formatBytes(totalSizeBytes) }}</span>
      <span style="margin-right: 24px">表数量: {{ tables.length }}</span>
      <span style="margin-right: 24px">字段数量: {{ columnCount === null ? '-' : formatNumber(columnCount) }}</span>
      <span>
        空表数量:
        <el-link type="primary" :disabled="!emptyTables.length" @click="onlyEmpty = true">{{ emptyTables.length }}</el-link>
      </span>
    </el-alert>

    <el-table :data="filteredTables" v-loading="loading" border @selection-change="onSelectionChange">
      <el-table-column v-if="!filterTagId" type="selection" width="45" />
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="name" label="表名" min-width="180" sortable show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goTableDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="comment" label="注释" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.comment">{{ row.comment }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="标记" min-width="160">
        <template #default="{ row }">
          <template v-if="(tableTags[row.name] || []).length">
            <el-tag
              v-for="tag in tableTags[row.name]"
              :key="tag.id"
              size="small"
              class="table-tag"
              :type="tag.kind === 'EMPTY' ? 'info' : undefined"
              :effect="tag.kind === 'EMPTY' ? 'plain' : 'dark'"
              :color="tag.kind === 'EMPTY' ? undefined : tag.color"
              :style="tag.kind === 'EMPTY' ? {} : { borderColor: tag.color }"
            >{{ tag.name }}</el-tag>
          </template>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="220">
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>由大模型根据表结构(表名/字段/注释)生成的描述,比表注释更能体现表的用途</div>
              <div>生成只发送表结构元数据,不发送业务数据</div>
            </template>
            <span>描述 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <div class="doc-cell">
            <!-- 已有描述:刷新按钮重新生成;无描述:显示生成入口;标记筛选只读模式下隐藏生成/编辑入口 -->
            <template v-if="!filterTagId">
              <el-tooltip v-if="docs[row.name]" content="重新生成描述(基于最新表结构,覆盖现有描述)" placement="top" :show-after="200">
                <el-button link type="primary" :loading="docLoading[row.name]" @click="generateDoc(row)">
                  <el-icon v-if="!docLoading[row.name]"><Refresh /></el-icon>
                </el-button>
              </el-tooltip>
              <el-button v-else link type="primary" :loading="docLoading[row.name]" @click="generateDoc(row)">生成描述</el-button>
            </template>
            <el-tooltip v-if="docs[row.name]" :content="docs[row.name]" placement="top" :show-after="200">
              <span class="doc-text" :style="filterTagId ? 'cursor: default' : ''" @click="openDocEdit(row)">{{ docs[row.name] }}</span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="storageInfo" label="引擎/表空间" width="130">
        <template #default="{ row }">
          <span v-if="row.storageInfo">{{ row.storageInfo }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="行数" width="140" sortable :sort-method="(a, b) => (effectiveRows(a).value ?? -1) - (effectiveRows(b).value ?? -1)">
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>已全量扫描的表显示 COUNT(*) 精确值;未扫描或采样扫描的表显示元数据估算值(带"约"前缀)</div>
              <div>超过采样阈值(默认 100 万行或 10GB,可在数据源配置)的表,扫描时默认只采样统计</div>
            </template>
            <span>行数 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <template v-if="effectiveRows(row).value !== null && effectiveRows(row).value !== undefined">
            <span v-if="effectiveRows(row).exact">{{ formatNumber(effectiveRows(row).value) }}</span>
            <span v-else>约 {{ formatNumber(effectiveRows(row).value) }}</span>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="总大小" width="140" sortable :sort-method="(a, b) => (effectiveSize(a) ?? -1) - (effectiveSize(b) ?? -1)">
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>数据 + 索引占用;已扫描的表取最近一次扫描时记录的值,未扫描的表取当前元数据值</div>
            </template>
            <span>总大小 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">{{ formatBytes(effectiveSize(row)) }}</template>
      </el-table-column>
      <el-table-column label="最近扫描时间" width="170" sortable :sort-method="(a, b) => latestScanTime(a) - latestScanTime(b)">
        <template #default="{ row }">
          <span v-if="latestScans[row.name]">{{ formatDateTime(latestScans[row.name].finishedAt) }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <!-- 正在扫描:显示分段进度,点击跳到任务详情;排队中的表显示 0% -->
          <el-progress
            v-if="runningScans[row.name]"
            :percentage="scanPercent(runningScans[row.name])"
            :stroke-width="10"
            style="cursor: pointer"
            @click="goRunningJob(row)"
          />
          <template v-else-if="!filterTagId">
            <el-button link type="primary" @click="openTagDialog(row)">打标</el-button>
            <el-button link type="primary" @click="scanSingle(row)">扫描</el-button>
          </template>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="scanDialogVisible" title="开始扫描" width="640px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="扫描范围">
          <span v-if="singleTable">仅扫描表:{{ singleTable }}</span>
          <span v-else-if="selectedTables.length">已选 {{ selectedTables.length }} 张表</span>
          <span v-else>未选择表,将扫描全库</span>
        </el-form-item>
        <el-form-item label="强制全量">
          <el-switch v-model="scanForm.forceFull" />
          <div class="form-tip">超过阈值的表不做采样,逐行精确统计</div>
        </el-form-item>
        <el-form-item label="表大小上限">
          <div style="width: 100%">
            <el-input-number v-model="scanForm.maxSizeValue" :min="1" placeholder="不限制"
                             controls-position="right" style="width: 160px" />
            <el-select v-model="scanForm.maxSizeUnit" style="width: 80px; margin-left: 8px">
              <el-option label="MB" value="MB" />
              <el-option label="GB" value="GB" />
            </el-select>
            <div class="form-tip">只扫描不超过该大小的表(按元数据估算的数据+索引大小),留空表示不限制</div>
            <div v-if="skippedBySize" class="form-tip" style="color: var(--el-color-warning)">
              当前范围内有 {{ skippedBySize }} 张表超过上限,将被跳过
            </div>
          </div>
        </el-form-item>
        <el-form-item label="AI 自动打标">
          <el-checkbox v-model="scanForm.autoTag">扫描完成后由大模型自动打标</el-checkbox>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>每张表扫描完成后,由大模型根据表注释/字段注释/表描述,从标记列表中选择最合适的标记自动打上(只增不删,已有标记的表不覆盖)</div>
              <div>表无任何注释时会抽样 100 行业务数据一并发送给大模型;未配置大模型时自动跳过</div>
            </template>
            <el-icon style="vertical-align: -2px; margin-left: 4px"><QuestionFilled /></el-icon>
          </el-tooltip>
        </el-form-item>
        <el-form-item label="空值规则">
          <div style="width: 100%">
            <div v-for="(rule, idx) in scanForm.nullRules" :key="idx" class="rule-row">
              <el-input v-model="rule.column" placeholder="列名(* 表示所有列)" style="width: 180px" />
              <el-input v-model="rule.valuesText" placeholder="视为空的取值,逗号分隔" style="flex: 1" />
              <el-button link type="danger" @click="scanForm.nullRules.splice(idx, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="scanForm.nullRules.push({ column: '', valuesText: '' })">
              + 添加规则
            </el-button>
            <div class="form-tip">例如:列名 *,取值 0,-1 表示所有列中值为 0 或 -1 的也视为空</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scanDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitScan">提交扫描</el-button>
      </template>
    </el-dialog>

    <!-- 手动编辑表描述 -->
    <el-dialog v-model="docEditVisible" :title="`编辑描述 - ${docEditTable}`" width="560px" append-to-body>
      <el-input v-model="docEditText" type="textarea" :rows="5" maxlength="2000" show-word-limit
                placeholder="输入该表的用途描述" />
      <template #footer>
        <el-button @click="docEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="docEditSaving" @click="saveDocEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 打标弹窗(含标记集中管理) -->
    <TableTagDialog
      v-model="tagDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :table-name="tagDialogTable"
      :current-tags="tableTags[tagDialogTable] || []"
      @saved="onTagsSaved"
      @tags-changed="reloadTableTags"
    />
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { QuestionFilled, Refresh } from '@element-plus/icons-vue'
import request, { submitReportExport } from '../api'
import AiConfigDialog from '../components/AiConfigDialog.vue'
import TableTagDialog from '../components/TableTagDialog.vue'
import Breadcrumb from '../components/Breadcrumb.vue'
import { ensureDsName, getDsName, syncTab } from '../stores/tabs'
import { formatBytes, formatDateTime, formatNumber } from '../utils/format'
import { goBack as historyBack } from '../utils/back'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schema = route.params.schema
const db = route.query.db || ''

const tables = ref([])
const loading = ref(false)
// 手动刷新表结构缓存中状态
const refreshing = ref(false)
// Word 报告导出中状态(只导出当前库,未扫描时后端拦截提示)
const exporting = ref(false)

/** 导出当前库的 Word 数据调研报告(异步任务,单库范围;提交后到「导出任务」页查看) */
async function exportReport() {
  exporting.value = true
  try {
    await submitReportExport(dsId, db, [schema])
    ElMessage.success('导出任务已提交,可在「导出任务」页签查看进度')
  } catch {
    // 提交失败由响应拦截器弹窗
  } finally {
    exporting.value = false
  }
}
// schema 下所有基表的字段总数(业务库元数据查询,失败时显示 -)
const columnCount = ref(null)
const keyword = ref('')
// 本地标记多选筛选:选中的标记 id(字符串数组),OR 逻辑--任一命中即展示
const selectedTagIds = ref([])
const selectedTables = ref([])
// 每张表最近一次 DONE 扫描的信息(表名 -> { jobId, finishedAt, totalRows, sizeBytes, sampled }),
// 有值的表名渲染为链接;非采样表的 totalRows 为精确行数,优先于元数据估算展示
const latestScans = ref({})
// 运行中任务里每张未完成表的分段进度(表名 -> { jobId, status, doneChunks, totalChunks })
const runningScans = ref({})
// AI 生成的表说明(表名 -> 说明文字),本地 H2 查询
const docs = ref({})
// 行内「说明」按钮的生成中状态(表名 -> bool)
const docLoading = ref({})
const batchDocLoading = ref(false)
// 手动编辑描述弹窗
const docEditVisible = ref(false)
const docEditTable = ref('')
const docEditText = ref('')
const docEditSaving = ref(false)

// 整个库的表→标记 map(表名 -> [{id,name,color,kind}]),含系统驱动的空表标记
const tableTags = ref({})
// 当前库下已使用的标记列表(从 tableTags 提取去重,按 id 排序),供标记筛选下拉
const availableTags = computed(() => {
  const seen = new Map()
  for (const tags of Object.values(tableTags.value)) {
    for (const tag of tags) {
      if (!seen.has(tag.id)) seen.set(tag.id, tag)
    }
  }
  return [...seen.values()].sort((a, b) => a.id - b.id)
})
// 打标弹窗
const tagDialogVisible = ref(false)
const tagDialogTable = ref('')

// 按标记筛选的只读模式:路由 query 带 tagId 时只显示打了该标记的表,并禁用所有操作类交互
const filterTagId = computed(() => (route.query.tagId ? String(route.query.tagId) : ''))
const filterTagName = computed(() => route.query.tagName || '')

// 清除标记筛选:回到无 tagId 的路由
function clearTagFilter() {
  router.push({ path: route.path, query: db ? { db } : {} })
}

function openTagDialog(row) {
  tagDialogTable.value = row.name
  tagDialogVisible.value = true
}

// 打标保存成功:回填该表最新标记数组
function onTagsSaved(tags) {
  tableTags.value[tagDialogTable.value] = tags
}

// 弹窗内管理操作(新建/改名/改色/删除)后,整库标记 map 需重拉(名称/颜色/打标关系可能变了)
async function reloadTableTags() {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  tableTags.value = await request.get(`${base}/table-tags${dbQuery()}`).catch(() => ({}))
}

const scanDialogVisible = ref(false)
const submitting = ref(false)
// maxSizeValue 为空(null)表示不限制表大小
const scanForm = reactive({ forceFull: false, nullRules: [], maxSizeValue: null, maxSizeUnit: 'GB', autoTag: false })
// 行内"扫描"按钮带出的单表目标;为空则按勾选/全库走
const singleTable = ref('')

// 大模型配置是否可用(合并默认值后完整),决定「AI 自动打标」复选框默认勾选;首次打开扫描对话框时拉取并缓存
const aiAvailable = ref(false)
let aiConfigFetched = false
async function fetchAiAvailable() {
  if (aiConfigFetched) return
  aiConfigFetched = true
  try {
    const cfg = await request.get('/ai-config')
    aiAvailable.value = !!cfg.available
    scanForm.autoTag = aiAvailable.value
  } catch {
    // 拉取失败按不可用处理,复选框默认不勾
  }
}

// 行数取值:非采样的最新完成扫描给的是 COUNT(*) 精确值,优先于元数据估算;
// 采样表的 totalRows 只是采样行数,不能当作全表行数,仍用估算值
function effectiveRows(row) {
  const s = latestScans.value[row.name]
  if (s && !s.sampled && s.totalRows !== null && s.totalRows !== undefined) {
    return { value: s.totalRows, exact: true }
  }
  return { value: row.estRows, exact: false }
}

// 大小取值:已扫描的表用最近一次扫描时记录的快照(比当前元数据更接近扫描口径),否则用元数据
function effectiveSize(row) {
  const s = latestScans.value[row.name]
  return s && s.sizeBytes !== null && s.sizeBytes !== undefined ? s.sizeBytes : row.sizeBytes
}

// 空表:行数为 0(含未知)的表,已全量扫描的按精确值算
const onlyEmpty = ref(false)
const emptyTables = computed(() => tables.value.filter((t) => !effectiveRows(t).value))
// 库级汇总:行数/大小按各表有效值(扫描准确值优先)求和
const totalEstRows = computed(() => tables.value.reduce((sum, t) => sum + (effectiveRows(t).value || 0), 0))
const totalSizeBytes = computed(() => tables.value.reduce((sum, t) => sum + (effectiveSize(t) || 0), 0))

// 当前扫描范围内的表:单表 > 勾选 > 全库
const scopeTables = computed(() => {
  if (singleTable.value) return tables.value.filter((t) => t.name === singleTable.value)
  return selectedTables.value.length ? selectedTables.value : tables.value
})

// 表大小上限换算成字节;未设置返回 null
function maxSizeBytes() {
  if (!scanForm.maxSizeValue) return null
  return scanForm.maxSizeValue * (scanForm.maxSizeUnit === 'GB' ? 1073741824 : 1048576)
}

// 范围内将被大小上限跳过的表数量(大小未知的表不参与统计)
const skippedBySize = computed(() => {
  const limit = maxSizeBytes()
  if (!limit) return 0
  return scopeTables.value.filter((t) => t.sizeBytes != null && t.sizeBytes > limit).length
})

const filteredTables = computed(() => {
  let list = onlyEmpty.value ? emptyTables.value : tables.value
  // 标记筛选只读模式:该库 table-tags map 中含该 tagId 的表
  if (filterTagId.value) {
    list = list.filter((t) =>
      (tableTags.value[t.name] || []).some((tag) => String(tag.id) === filterTagId.value))
  }
  // 本地标记多选筛选:选中的标记中任一命中即展示(OR)
  if (selectedTagIds.value.length) {
    list = list.filter((t) =>
      (tableTags.value[t.name] || []).some((tag) => selectedTagIds.value.includes(String(tag.id))))
  }
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((t) =>
      t.name.toLowerCase().includes(kw) || (t.comment || '').toLowerCase().includes(kw))
  }
  return list
})

async function load(refresh = false) {
  loading.value = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    // refresh=true 时表清单强制从业务库拉最新结构并覆盖本地缓存;其余数据为本地 H2/实时查询,不受 refresh 影响
    const q = dbQuery()
    const tablesUrl = `${base}/tables${q}${refresh ? (q ? '&' : '?') + 'refresh=true' : ''}`
    // 最新扫描映射/表说明查的是本地 H2,失败时仅影响表名是否可点与说明展示,不阻塞表列表
    // 字段总数走业务库元数据,失败时也不阻塞表列表(显示 -)
    const [tableList, latest, tableDocs, colCount, tagMap] = await Promise.all([
      request.get(tablesUrl),
      request.get(`${base}/latest-scan-jobs${dbQuery()}`).catch(() => ({})),
      request.get(`${base}/table-docs${dbQuery()}`).catch(() => ({})),
      request.get(`${base}/column-count${dbQuery()}`).catch(() => null),
      request.get(`${base}/table-tags${dbQuery()}`).catch(() => ({}))
    ])
    tables.value = tableList
    latestScans.value = latest || {}
    docs.value = tableDocs || {}
    columnCount.value = colCount
    tableTags.value = tagMap || {}
  } finally {
    loading.value = false
  }
}

/** 手动刷新:从业务库拉最新表结构并覆盖本地缓存 */
async function refreshTables() {
  refreshing.value = true
  try {
    await load(true)
    ElMessage.success('已从数据源刷新表结构缓存')
  } finally {
    refreshing.value = false
  }
}

// 轮询运行中扫描进度(本地 H2 查询,开销极小);没有运行中任务时也低频探一次,能发现别处发起的扫描
let pollTimer = null
async function fetchRunning() {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  runningScans.value = await request.get(`${base}/running-scans${dbQuery()}`).catch(() => ({}))
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(fetchRunning, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 表级分段进度百分比
function scanPercent(s) {
  if (!s.totalChunks) return 0
  return Math.min(100, Math.round((s.doneChunks / s.totalChunks) * 100))
}

// 点击进度条跳到正在扫描的任务详情
function goRunningJob(row) {
  router.push(`/scans/${runningScans.value[row.name].jobId}?schema=${encodeURIComponent(db ? `${db}.${schema}` : schema)}`)
}

// 行内"扫描"按钮:只扫这一张表
function scanSingle(row) {
  openScanDialog()
  singleTable.value = row.name
}

// 生成单表 AI 说明;大模型响应较慢,单请求超时放宽到 130s(后端读超时 120s)
async function generateDoc(row) {
  docLoading.value[row.name] = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    const res = await request.post(`${base}/tables/${encodeURIComponent(row.name)}/doc${dbQuery()}`, null, { timeout: 130000 })
    docs.value[row.name] = res.description
    ElMessage.success(`已生成「${row.name}」的表说明`)
  } finally {
    docLoading.value[row.name] = false
  }
}

// 打开手动编辑描述弹窗(标记筛选只读模式下不可编辑)
function openDocEdit(row) {
  if (filterTagId.value) return
  docEditTable.value = row.name
  docEditText.value = docs.value[row.name] || ''
  docEditVisible.value = true
}

// 保存手动编辑的描述
async function saveDocEdit() {
  const text = docEditText.value.trim()
  if (!text) {
    ElMessage.warning('描述不能为空')
    return
  }
  docEditSaving.value = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    await request.put(`${base}/tables/${encodeURIComponent(docEditTable.value)}/doc${dbQuery()}`, { description: text })
    docs.value[docEditTable.value] = text
    docEditVisible.value = false
    ElMessage.success('已保存')
  } finally {
    docEditSaving.value = false
  }
}

// 批量生成勾选的表说明:并发 2 逐表调单表接口,单表失败不影响其他
async function generateDocsBatch() {
  const queue = [...selectedTables.value]
  batchDocLoading.value = true
  let ok = 0
  let fail = 0
  async function worker() {
    while (queue.length) {
      const row = queue.shift()
      try {
        const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
        const res = await request.post(`${base}/tables/${encodeURIComponent(row.name)}/doc${dbQuery()}`, null, { timeout: 130000 })
        docs.value[row.name] = res.description
        ok++
      } catch {
        fail++ // 单表错误消息已由拦截器弹出
      }
    }
  }
  try {
    await Promise.all([worker(), worker()])
    ElMessage.success(`表说明生成完成:成功 ${ok} 张,失败 ${fail} 张`)
  } finally {
    batchDocLoading.value = false
  }
}

// 排序用:该表最近扫描完成时间的毫秒值,未扫描过排最前
function latestScanTime(row) {
  const t = latestScans.value[row.name]?.finishedAt
  return t ? new Date(t).getTime() : 0
}

// ---------- 面包屑 ----------
const dsName = computed(() => getDsName(dsId) || `数据源 ${dsId}`)
const schemaLabel = computed(() => (db ? `${db}.${schema}` : schema))
const breadcrumbItems = computed(() => [
  { label: '数据源列表', to: '/datasources' },
  { label: dsName.value, to: `/datasources/${dsId}/schemas` },
  { label: schemaLabel.value }
])

function dbQuery() {
  return db ? `?db=${encodeURIComponent(db)}` : ''
}

// 原路返回;无历史记录(直接打开)时兜底回库列表
function goBack() {
  historyBack(router, `/datasources/${dsId}/schemas`)
}

function onSelectionChange(rows) {
  selectedTables.value = rows
}

// 点击表名统一进入字段明细页:已扫描带 jobId(展示扫描统计),未扫描仅结构
function goTableDetail(row) {
  const s = latestScans.value[row.name]
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(row.name)}${dbQuery()}`
  router.push(s ? `${base}${dbQuery() ? '&' : '?'}jobId=${s.jobId ?? s}` : base)
}

function openScanDialog() {
  singleTable.value = ''
  scanForm.forceFull = false
  scanForm.nullRules = []
  scanForm.maxSizeValue = null
  scanForm.maxSizeUnit = 'GB'
  scanForm.autoTag = aiAvailable.value
  fetchAiAvailable()
  scanDialogVisible.value = true
}

async function submitScan() {
  const nullRules = scanForm.nullRules
    .filter((r) => r.column.trim() && r.valuesText.trim())
    .map((r) => ({
      column: r.column.trim(),
      values: r.valuesText.split(',').map((v) => v.trim()).filter(Boolean)
    }))
    .filter((r) => r.values.length > 0)

  submitting.value = true
  try {
    const res = await request.post('/scans', {
      datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
      schema,
      database: db || null,
      tables: singleTable.value ? [singleTable.value] : (selectedTables.value.length ? selectedTables.value.map((t) => t.name) : null),
      forceFull: scanForm.forceFull,
      nullRules,
      maxTableSizeBytes: maxSizeBytes(),
      autoTag: scanForm.autoTag
    })
    ElMessage.success('扫描任务已提交')
    scanDialogVisible.value = false
    // 带上库名标签,供页签标题展示
    const schemaLabel = db ? `${db}.${schema}` : schema
    router.push(`/scans/${res.jobId}?schema=${encodeURIComponent(schemaLabel)}`)
  } finally {
    submitting.value = false
  }
}

// 首次挂载标记:onActivated 在首次挂载后也会触发,避免与 onMounted 重复加载
const mounted = ref(false)

onMounted(async () => {
  await load()
  mounted.value = true
  fetchRunning()
  startPolling()
  // 数据源名兜底解析:刷新/直达 URL 无 ?name= 时也能恢复真名,并刷新页签标题
  ensureDsName(dsId).then(() => syncTab(route))
})

// 页签下钻时是失活而非卸载:停掉轮询,回来时重载表列表(行数/大小/最近扫描/表说明)并恢复轮询
onActivated(() => {
  if (!mounted.value) return // 首次挂载由 onMounted 处理
  load()
  fetchRunning()
  startPolling()
})

onDeactivated(stopPolling)

onUnmounted(stopPolling)
</script>

<style scoped>
.table-tag {
  margin: 0 4px 2px 0;
}
.doc-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}
.doc-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.rule-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
