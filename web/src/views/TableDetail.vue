<template>
  <div class="page-card">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div>
        <el-button type="primary" :loading="collectLoading" :disabled="!dsId" @click="toggleCollect">
          {{ collectId ? '取消采集' : '采集' }}
        </el-button>
        <ExportButton v-if="hasJob" :job-id="jobId" label="导出扫描结果" />
        <el-button @click="exportExcel">导出列表</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <!-- 扫描信息:有扫描时展示统计概览;无扫描时提示仅结构 -->
    <el-alert v-if="hasJob && jobTable" type="success" :closable="false" style="margin-bottom: 16px">
      <span style="margin-right: 24px">总行数: {{ formatNumber(jobTable.totalRows ?? jobTable.scannedRows) }}</span>
      <span style="margin-right: 24px">
        统计方式: {{ jobTable.sampled ? '采样' : '全量' }}
        <el-tooltip v-if="jobTable.sampled" placement="top" :show-after="200">
          <template #content>
            <div>该表只统计了样本(默认 10 万行),空值数、有值率等由样本按比例推算,为估算值</div>
            <div>MySQL / 达梦 / OceanBase 为 LIMIT 顺序采样,结果可能有偏</div>
          </template>
          <el-tag type="warning" size="small" style="margin-left: 6px">估算值</el-tag>
        </el-tooltip>
      </span>
      <span v-if="jobTable.sampled" style="margin-right: 24px">采样行数: {{ formatNumber(jobTable.sampleRows) }}</span>
      <span style="margin-right: 24px">耗时: {{ formatDuration(jobTable.startedAt, jobTable.finishedAt) }}</span>
      <span style="margin-right: 24px">字段数量: {{ columns.length }}</span>
      <span>
        空字段数量:
        <el-link type="primary" :disabled="!emptyColumns.length" @click="onlyEmpty = true">{{ emptyColumns.length }}</el-link>
      </span>
    </el-alert>
    <el-alert v-else type="info" :closable="false" style="margin-bottom: 12px">
      该表尚未扫描,以下为数据库元数据中的字段结构与索引结构,不含空值与有值率统计。
      在表列表勾选该表后「开始扫描」,扫描完成即可查看字段级统计。
    </el-alert>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="字段明细" name="columns">
        <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
          <template v-if="columnView === 'table'">
            <el-input v-model="keyword" placeholder="按字段名或注释搜索" clearable style="width: 280px" />
            <el-checkbox v-if="hasJob" v-model="onlyEmpty">只看空字段(有值数为 0)</el-checkbox>
          </template>
          <el-button v-else size="small" type="primary" plain :disabled="!ddlText" @click="copyDdl">复制 DDL</el-button>
          <!-- 表格 / DDL 视图切换(靠右) -->
          <el-radio-group v-model="columnView" size="small" style="margin-left: auto" @change="onColumnViewChange">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="ddl">DDL</el-radio-button>
          </el-radio-group>
        </div>

        <!-- DDL 视图:建表语句(含索引),懒加载实时拉取 -->
        <template v-if="columnView === 'ddl'">
          <el-alert v-if="ddlError" type="error" :closable="false" show-icon :title="ddlError" style="margin-bottom: 12px" />
          <div v-loading="ddlLoading">
            <pre v-if="ddlText" class="ddl-view">{{ ddlText }}</pre>
            <el-empty v-if="!ddlLoading && !ddlError && ddlLoaded && !ddlText" description="未获取到 DDL" :image-size="60" />
          </div>
        </template>

        <!-- 字段列表:基础结构列 + (已扫描时)统计列 -->
        <el-table v-else :data="filteredColumns" v-loading="loading" border>
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column prop="name" label="字段名" min-width="140" sortable show-overflow-tooltip />
          <el-table-column prop="comment" label="注释" min-width="140" sortable show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.comment">{{ row.comment }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="displayType" label="类型" width="150" sortable show-overflow-tooltip />
          <el-table-column label="键" width="70" sortable :sort-method="(a, b) => keyLabel(a).localeCompare(keyLabel(b))">
            <template #default="{ row }">
              <el-tag v-if="keyLabel(row)" size="small" :type="keyLabel(row) === 'PK' ? 'primary' : 'success'">{{ keyLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="可空" width="70" sortable :sort-method="(a, b) => Number(a.nullable ?? true) - Number(b.nullable ?? true)">
            <template #default="{ row }">
              <span v-if="row.nullable === null || row.nullable === undefined">-</span>
              <span v-else>{{ row.nullable ? '是' : '否' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="defaultValue" label="默认值" width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.defaultValue !== null && row.defaultValue !== undefined && row.defaultValue !== ''">{{ row.defaultValue }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <template v-if="hasJob">
            <el-table-column label="空值数(合计)" width="130" sortable :sort-method="sortByNullTotal">
              <template #default="{ row }">
                <span :style="{ color: nullTotal(row) > 0 ? 'var(--el-color-warning)' : 'inherit' }">{{ formatNumber(nullTotal(row)) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="totalRows" label="总行数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.totalRows) }}</template>
            </el-table-column>
            <el-table-column prop="nullCount" label="NULL 数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.nullCount) }}</template>
            </el-table-column>
            <el-table-column prop="emptyCount" label="空串数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.emptyCount) }}</template>
            </el-table-column>
            <el-table-column prop="valueCount" label="有值数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.valueCount) }}</template>
            </el-table-column>
            <el-table-column label="有值率" width="180" sortable :sort-method="(a, b) => (a.fillRate || 0) - (b.fillRate || 0)">
              <template #default="{ row }">
                <el-progress
                  :percentage="row.fillRate || 0"
                  :stroke-width="10"
                  :color="row.fillRate >= 95 ? 'var(--el-color-success)' : row.fillRate >= 80 ? 'var(--el-color-warning)' : 'var(--el-color-danger)'"
                />
              </template>
            </el-table-column>
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="索引结构" name="indexes">
        <el-table :data="indexes" v-loading="indexesLoading" border size="small">
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column prop="name" label="索引名" min-width="180" show-overflow-tooltip />
          <el-table-column label="唯一" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.unique" type="success" size="small">唯一</el-tag>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <el-table-column label="索引列" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="c in row.columns" :key="c" size="small" style="margin: 0 4px 2px 0">{{ c }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="数据预览" name="preview">
        <div style="display: flex; gap: 8px; margin-bottom: 12px">
          <SqlInput v-model="previewWhere" label="WHERE" :columns="completionColumns"
                    placeholder="过滤条件,如 status = 'A' AND age > 18(回车应用)"
                    @enter="applyPreviewFilter" />
          <SqlInput v-model="previewOrderBy" label="ORDER BY" :columns="completionColumns"
                    placeholder="排序,如 id desc(回车应用)"
                    @enter="applyPreviewFilter" />
          <el-button size="small" type="primary" plain @click="applyPreviewFilter">应用</el-button>
        </div>
        <el-alert v-if="previewError" type="error" :closable="false" show-icon :title="previewError" style="margin-bottom: 12px" />
        <el-table v-else :data="previewTableData" v-loading="previewLoading" border size="small" class="preview-table"
                  @cell-click="copyPreviewCell">
          <el-table-column type="index" label="#" width="50" :index="(previewPage - 1) * previewSize + 1" />
          <el-table-column v-for="col in previewColumns" :key="col.key" :prop="col.key" min-width="140" show-overflow-tooltip>
            <template #header>
              <div>{{ col.name }}</div>
              <div style="font-size: 12px; font-weight: normal; color: var(--el-text-color-placeholder)">{{ col.type }}</div>
            </template>
            <template #default="{ row }">
              <span v-if="row[col.key] !== null && row[col.key] !== undefined">{{ row[col.key] }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">NULL</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!previewLoading && !previewError && previewLoaded && !previewRows.length"
                  description="表无数据" :image-size="60" />
        <div v-if="previewLoaded && previewTotal > 0" class="pagination-wrapper">
          <el-pagination v-model:current-page="previewPage" v-model:page-size="previewSize"
                         :page-sizes="[15, 30, 50, 100]" :total="previewTotal"
                         layout="total, sizes, prev, pager, next" background
                         @current-change="loadPreview" @size-change="onPreviewSizeChange" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tags">
        <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
          <el-input v-model="tagKeyword" placeholder="按标记名搜索" clearable style="width: 240px" />
          <el-select v-model="tagSourceFilter" placeholder="打标类型(全部)" clearable style="width: 160px">
            <el-option label="人工打标" value="MANUAL" />
            <el-option label="系统打标" value="SYSTEM" />
          </el-select>
          <el-button type="primary" :disabled="!dsId" style="margin-left: auto" @click="tagDialogVisible = true">打标</el-button>
        </div>
        <!-- 当前表已打标记:含系统自动维护的「空表」标记;手工打标走上方「打标」弹窗 -->
        <el-table :data="filteredTableTags" v-loading="tagsLoading" border>
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column label="标记" min-width="160">
            <template #default="{ row }">
              <el-tag
                :type="row.kind === 'EMPTY' ? 'info' : undefined"
                :effect="row.kind === 'EMPTY' ? 'plain' : 'dark'"
                :color="row.kind === 'EMPTY' ? undefined : row.color"
                :style="row.kind === 'EMPTY' ? {} : { borderColor: row.color }"
              >{{ row.name }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="打标类型" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.source === 'MANUAL'" size="small" type="info" effect="plain">人工打标</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">系统打标</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.description">{{ row.description }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!tagsLoading && tagsLoaded && !tableTags.length"
                  description="该表还没有标记,点右上角「打标」添加" :image-size="60" />
      </el-tab-pane>

    </el-tabs>

    <!-- 打标弹窗(复用表列表页同款组件):勾选 USER 标记 + 就地新建 -->
    <TableTagDialog
      v-model="tagDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :table-name="tableName"
      :current-tags="tableTags"
      @saved="onTagsSaved"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import request from '../api'
import ExportButton from '../components/ExportButton.vue'
import Breadcrumb from '../components/Breadcrumb.vue'
import SqlInput from '../components/SqlInput.vue'
import TableTagDialog from '../components/TableTagDialog.vue'
import { formatDuration, formatNumber } from '../utils/format'
import { cellText, exportListToExcel } from '../utils/listExport'
import { ensureDsName, getDsName, syncTab } from '../stores/tabs'

const route = useRoute()
const tableName = route.params.tableName
// 兼容旧路由 /scans/:jobId/tables/:tableName:数据源上下文从任务接口回填
const isScanRoute = route.path.startsWith('/scans/')

const dsId = ref(isScanRoute ? '' : route.params.id)
const schema = ref(isScanRoute ? '' : route.params.schema)
const db = ref(isScanRoute ? '' : (route.query.db || ''))
const jobId = ref(isScanRoute ? route.params.jobId : (route.query.jobId || ''))

const metaColumns = ref([])   // 结构字段(元数据)
const indexes = ref([])       // 索引结构
const statsColumns = ref([])  // 扫描统计字段(有扫描时)
const jobTable = ref(null)    // 任务中该表的统计概览
const loading = ref(false)
const indexesLoading = ref(false)
const indexesLoaded = ref(false)  // 索引是否已加载(懒加载)
const previewColumns = ref([])    // 预览列定义 [{key, name, type}],key 为 c+序号 映射行数组
const previewRows = ref([])       // 预览行(原始数组,元素为字符串/null)
const previewLoading = ref(false)
const previewLoaded = ref(false)  // 预览是否已加载(懒加载)
const previewError = ref('')      // 预览加载失败的内联错误提示
const previewSize = ref(15)       // 预览每页行数(服务端分页,与全局分页组件一致可选)
const previewPage = ref(1)        // 预览当前页码
const previewTotal = ref(0)       // 预览全表总行数(COUNT(*) 实时)
const previewWhere = ref('')      // 预览过滤条件输入(WHERE,DataGrip 风格原文)
const previewOrderBy = ref('')    // 预览排序输入(ORDER BY 原文)
const ddlText = ref('')           // 建表 DDL 文本(含索引)
const ddlLoading = ref(false)
const ddlLoaded = ref(false)      // DDL 是否已加载(懒加载)
const ddlError = ref('')          // DDL 加载失败的内联错误提示
const appliedWhere = ref('')      // 已应用的过滤条件(翻页用,输入未应用不影响)
const appliedOrderBy = ref('')    // 已应用的排序
// 过滤栏补全字段清单(复用字段明细的元数据,无需额外请求)
const completionColumns = computed(() =>
  metaColumns.value.map((c) => ({ name: c.name, type: c.displayType || '' })))
const activeTab = ref('columns')
const columnView = ref('table')   // 字段明细内视图:table=字段表格,ddl=建表 DDL
const refreshing = ref(false)
const keyword = ref('')
const onlyEmpty = ref(false)
// 当前表的人工采集状态:采集记录 id(null=未采集)
const collectId = ref(null)
const collectLoading = ref(false)
// 「标签」页签:当前表已打标记(含系统空表标记),懒加载
const tableTags = ref([])
const tagsLoading = ref(false)
const tagsLoaded = ref(false)     // 标记是否已加载(懒加载)
const tagKeyword = ref('')        // 标记名过滤
const tagSourceFilter = ref('')   // 打标类型过滤:MANUAL 人工打标 / SYSTEM 系统打标(含 AI 自动打标与空表联动),空=全部
const tagDialogVisible = ref(false)

const hasJob = computed(() => !!jobId.value)

// 空字段:有值数为 0 的字段
const emptyColumns = computed(() => columns.value.filter((c) => (c.valueCount || 0) === 0))

// 字段合并:以结构为准,扫描统计按列名附加
const columns = computed(() => {
  const statsMap = new Map(statsColumns.value.map((c) => [c.columnName, c]))
  return metaColumns.value.map((c) => ({ ...c, ...(statsMap.get(c.name) || {}) }))
})

const filteredColumns = computed(() => {
  let list = onlyEmpty.value ? emptyColumns.value : columns.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((c) =>
      (c.name || '').toLowerCase().includes(kw) || (c.comment || '').toLowerCase().includes(kw))
  }
  return list
})

// 标签页签筛选:名称关键字 + 打标类型(source:MANUAL=人工打标,其余=系统打标)
const filteredTableTags = computed(() => {
  let list = tableTags.value
  const kw = tagKeyword.value.trim().toLowerCase()
  if (kw) list = list.filter((t) => (t.name || '').toLowerCase().includes(kw))
  if (tagSourceFilter.value) {
    list = list.filter((t) => tagSourceFilter.value === 'MANUAL'
      ? t.source === 'MANUAL'
      : t.source !== 'MANUAL')
  }
  return list
})

/** 打标类型展示文案:人工打标(手动勾选)/ 系统打标(AI 自动打标、空表联动) */
function tagSourceLabel(tag) {
  return tag.source === 'MANUAL' ? '人工打标' : '系统打标'
}

// 键约束展示:PK / UNI / 空(结构推导优先,兼容扫描接口旧 keyLabel)
function keyLabel(row) {
  if (row.primaryKey) return 'PK'
  if (row.uniqueIndexFirst) return 'UNI'
  return row.keyLabel || ''
}

function nullTotal(row) {
  return (row.nullCount || 0) + (row.emptyCount || 0) + (row.ruleHitCount || 0)
}

function sortByNullTotal(a, b) {
  return nullTotal(a) - nullTotal(b)
}

async function load(refresh = false) {
  loading.value = true
  try {
    // 旧扫描路由 / 表路由带 jobId:先取任务信息(拿数据源上下文 + 该表统计概览)
    if (isScanRoute || jobId.value) {
      const job = await request.get(`/scans/${jobId.value}`).catch(() => null)
      if (job) {
        if (isScanRoute) {
          dsId.value = job.datasourceId
          schema.value = job.schemaName
          db.value = job.dbName || ''
        }
        jobTable.value = job.tables?.find((t) => t.tableName === tableName) || null
      }
    }
    if (!dsId.value || !schema.value) return

    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (refresh) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`

    // 结构 + 扫描统计并行拉取(索引懒加载,切到索引 tab 时才请求)
    const [cols, stats] = await Promise.all([
      request.get(`${url}/columns${q}`),
      jobId.value
        ? request.get(`/scans/${jobId.value}/tables/${encodeURIComponent(tableName)}/columns`).catch(() => [])
        : Promise.resolve([])
    ])
    metaColumns.value = cols
    statsColumns.value = stats || []
  } finally {
    loading.value = false
  }
}

/** 拉取当前表采集状态(本库采集 map 里按表名取记录 id),失败静默按未采集处理 */
async function loadCollectStatus() {
  if (!dsId.value || !schema.value) return
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
  const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
  const map = await request.get(`${base}/manual-collects${q}`).catch(() => ({}))
  collectId.value = map?.[tableName] ?? null
}

/** 采集/取消采集切换:取消按记录 id 删除,采集走批量接口(单表即一项);本页无表注释,快照传 null */
async function toggleCollect() {
  collectLoading.value = true
  try {
    if (collectId.value) {
      await request.delete(`/manual-collects/${collectId.value}`)
      collectId.value = null
      ElMessage.success(`已取消采集「${tableName}」`)
    } else {
      await request.post('/manual-collects', {
        items: [{
          datasourceId: /^\d+$/.test(String(dsId.value)) ? Number(dsId.value) : dsId.value,
          dbName: db.value || null,
          schemaName: schema.value,
          tableName,
          tableComment: null
        }]
      })
      ElMessage.success(`已采集「${tableName}」,可在「人工采集」页签查看`)
      await loadCollectStatus()
    }
  } finally {
    collectLoading.value = false
  }
}

/** 标签页签:拉本库打标 map 取当前表 entry,失败静默按无标记处理 */
async function loadTags() {
  if (!dsId.value || !schema.value) return
  tagsLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    const map = await request.get(`${base}/table-tags${q}`).catch(() => ({}))
    tableTags.value = map?.[tableName] || []
    tagsLoaded.value = true
  } finally {
    tagsLoading.value = false
  }
}

/** 打标保存:接口返回该表最新标记数组(含空表标记),就地回填 */
function onTagsSaved(tags) {
  tableTags.value = tags
}

/** 索引懒加载:首次切到索引 tab 或刷新时调用,已加载则跳过(除非强制) */
async function loadIndexes(force = false) {
  if (indexesLoaded.value && !force) return
  if (!dsId.value || !schema.value) return
  indexesLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (force) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`
    indexes.value = await request.get(`${url}/indexes${q}`).catch(() => [])
    indexesLoaded.value = true
  } finally {
    indexesLoading.value = false
  }
}

/** DDL 懒加载:首次切到 DDL 视图或刷新时调用(实时拉取,接口本身不落缓存,无 refresh 参数) */
async function loadDdl() {
  if (!dsId.value || !schema.value) return
  ddlLoading.value = true
  ddlError.value = ''
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    const q = params.toString() ? `?${params.toString()}` : ''
    const data = await request.get(`${base}/tables/${encodeURIComponent(tableName)}/ddl${q}`)
    ddlText.value = data?.ddl || ''
    ddlLoaded.value = true
  } catch (e) {
    // 拦截器已弹出错误消息,这里留内联提示;不置 loaded,允许重试
    ddlError.value = e?.response?.data?.message || e?.message || '加载 DDL 失败'
  } finally {
    ddlLoading.value = false
  }
}

// 预览行数组转成 el-table 需要的行对象(c0/c1/... 与列定义 key 对应)
const previewTableData = computed(() =>
  previewRows.value.map((r) => Object.fromEntries(r.map((v, i) => ['c' + i, v]))))

/** 数据预览:服务端分页,切页/首次切入/刷新时按页拉取;失败保留错误提示,可再次切换/刷新重试 */
async function loadPreview(page = previewPage.value || 1) {
  if (!dsId.value || !schema.value) return
  previewLoading.value = true
  previewError.value = ''
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (appliedWhere.value) params.set('where', appliedWhere.value)
    if (appliedOrderBy.value) params.set('orderBy', appliedOrderBy.value)
    params.set('page', String(page))
    params.set('size', String(previewSize.value))
    const url = `${base}/tables/${encodeURIComponent(tableName)}/preview?${params.toString()}`
    const data = await request.get(url)
    previewColumns.value = (data.columns || []).map((c, i) => ({ key: 'c' + i, name: c.name, type: c.type }))
    previewRows.value = data.rows || []
    previewTotal.value = data.total ?? 0
    previewPage.value = data.page ?? page
    previewLoaded.value = true
  } catch (e) {
    // 拦截器已弹出错误消息,这里留内联提示;不置 loaded,允许重试
    previewError.value = e?.response?.data?.message || e?.message || '加载预览数据失败'
  } finally {
    previewLoading.value = false
  }
}

/** 切换每页条数:回到第 1 页重新查询 */
function onPreviewSizeChange() {
  loadPreview(1)
}

/** 复制文本到剪贴板:内网 http 部署是非安全上下文,没有 Clipboard API,退回隐藏 textarea 方案 */
async function copyText(text, successMsg) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    ElMessage.success(successMsg)
  } catch {
    ElMessage.error('复制失败')
  }
}

/** 点击预览单元格复制完整内容(长文本截断成 ... 时靠它取全文);NULL/序号列不复制 */
async function copyPreviewCell(row, column) {
  const v = row[column.property]
  if (v === null || v === undefined) return
  copyText(String(v), '已复制单元格内容')
}

/** 复制整段建表 DDL */
function copyDdl() {
  copyText(ddlText.value, '已复制 DDL')
}

/** 应用过滤/排序:同步到已应用变量并回到第 1 页重新查询 */
function applyPreviewFilter() {
  appliedWhere.value = previewWhere.value.trim()
  appliedOrderBy.value = previewOrderBy.value.trim()
  loadPreview(1)
}

/** 切换 tab:切到索引/预览/标签时各自懒加载(预览仅首次,之后翻页由分页器触发) */
function onTabChange(name) {
  if (name === 'indexes') loadIndexes()
  if (name === 'preview' && !previewLoaded.value) loadPreview(1)
  if (name === 'tags' && !tagsLoaded.value) loadTags()
}

/** 字段明细内 表格/DDL 切换:首次切到 DDL 时懒加载 */
function onColumnViewChange(view) {
  if (view === 'ddl' && !ddlLoaded.value) loadDdl()
}

/** 导出当前 tab 的列表 Excel(字段明细/索引结构/数据预览当前页,列与页面一致) */
function exportExcel() {
  if (activeTab.value === 'columns') {
    if (columnView.value === 'ddl') return ElMessage.warning('DDL 请使用「复制 DDL」按钮')
    if (!filteredColumns.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = ['字段名', '注释', '类型', '键', '可空', '默认值']
    if (hasJob.value) headers.push('空值数(合计)', '总行数', 'NULL 数', '空串数', '有值数', '有值率%')
    const rows = filteredColumns.value.map((c) => {
      const row = [
        cellText(c.name),
        cellText(c.comment),
        cellText(c.displayType),
        keyLabel(c),
        c.nullable === null || c.nullable === undefined ? '' : (c.nullable ? '是' : '否'),
        cellText(c.defaultValue)
      ]
      if (hasJob.value) {
        row.push(
          formatNumber(nullTotal(c)),
          formatNumber(c.totalRows),
          formatNumber(c.nullCount),
          formatNumber(c.emptyCount),
          formatNumber(c.valueCount),
          c.fillRate === null || c.fillRate === undefined ? '' : String(Math.round(c.fillRate * 100) / 100)
        )
      }
      return row
    })
    exportListToExcel(`字段明细-${tableName}`, headers, rows, '字段明细')
  } else if (activeTab.value === 'indexes') {
    if (!indexes.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = ['索引名', '唯一', '索引列']
    const rows = indexes.value.map((i) => [
      cellText(i.name),
      i.unique ? '唯一' : '',
      (i.columns || []).join(', ')
    ])
    exportListToExcel(`索引结构-${tableName}`, headers, rows, '索引结构')
  } else if (activeTab.value === 'tags') {
    if (!filteredTableTags.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = ['标记名', '打标类型', '描述']
    const rows = filteredTableTags.value.map((t) => [cellText(t.name), tagSourceLabel(t), cellText(t.description)])
    exportListToExcel(`标签-${tableName}`, headers, rows, '标签')
  } else {
    if (!previewRows.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = previewColumns.value.map((c) => (c.type ? `${c.name} ${c.type}` : c.name))
    const rows = previewRows.value.map((r) => r.map(cellText))
    exportListToExcel(`数据预览-${tableName}-第${previewPage.value}页`, headers, rows, '数据预览')
  }
}

/** 手动刷新:结构强制从业务库拉最新并覆盖本地缓存,统计一并重拉;索引/预览/DDL 按当前展示位置决定是否重载 */
async function refreshAll() {
  refreshing.value = true
  try {
    // 重置索引/预览/DDL/标签 加载标记,若当前正在展示则强制重新拉取
    indexesLoaded.value = false
    previewLoaded.value = false
    ddlLoaded.value = false
    tagsLoaded.value = false
    await load(true)
    await loadCollectStatus()
    if (activeTab.value === 'indexes') await loadIndexes(true)
    if (activeTab.value === 'preview') await loadPreview(previewPage.value)
    if (activeTab.value === 'tags') await loadTags()
    if (activeTab.value === 'columns' && columnView.value === 'ddl') await loadDdl()
    ElMessage.success('已刷新结构与扫描信息')
  } finally {
    refreshing.value = false
  }
}

// ---------- 面包屑 ----------
const dsName = computed(() => getDsName(dsId.value) || `数据源 ${dsId.value}`)
const schemaLabel = computed(() => (db.value ? `${db.value}.${schema.value}` : schema.value))
const tablesPath = computed(() => {
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables`
  return db.value ? `${base}?db=${encodeURIComponent(db.value)}` : base
})
const breadcrumbItems = computed(() => {
  const items = [
    { label: dsName.value, to: `/datasources/${dsId.value}/schemas` },
    { label: schemaLabel.value, to: tablesPath.value }
  ]
  if (hasJob.value) items.push({ label: `扫描 #${jobId.value}`, to: `/scans/${jobId.value}` })
  items.push({ label: tableName })
  return items
})

onMounted(async () => {
  await load()
  loadCollectStatus()
  // 数据源名兜底解析:刷新/直达 URL 无 ?name= 时也能恢复真名,并刷新页签标题
  // (dsId 在 load 内由任务接口回填,须在 load 之后解析)
  if (dsId.value) ensureDsName(dsId.value).then(() => syncTab(route))
})
</script>

<style scoped>
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

/* 数据预览表格:正文与表头用一级文字色,提高对比度(NULL/类型标注仍用占位色弱化) */
.preview-table {
  --el-table-text-color: var(--el-text-color-primary);
  --el-table-header-text-color: var(--el-text-color-primary);
}

/* 单元格点击可复制,用 copy 光标提示可交互 */
.preview-table :deep(.el-table__cell) {
  cursor: copy;
}

/* 建表 DDL 文本视图:等宽字体,长行自动换行 */
.ddl-view {
  margin: 0;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
