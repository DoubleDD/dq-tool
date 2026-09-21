<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">比对任务</h3>
      <!-- 按钮组统一右侧、gap 统一间距(与抽样导出页同写法) -->
      <div class="toolbar-actions">
        <el-checkbox v-model="showArchived" @change="search">显示已归档</el-checkbox>
        <!-- 批量删除勾选任务(运行中不可勾选);与抽样导出页同一写法 -->
        <el-button type="danger" plain :disabled="!selection.length" @click="confirmBatchDelete">批量删除</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <!-- 批量导入:一 sheet 一任务(本页整体已在 compare 授权门禁内,不再单独判) -->
        <el-button @click="openImport">导入表格</el-button>
        <el-button type="primary" @click="router.push('/compare/new')">+ 新建比对任务</el-button>
      </div>
    </div>
    <!-- 筛选条:服务端筛选(与错误中心同模式);表的标记命中口径=基准表或任一比对目标表打了所选标记之一 -->
    <div class="filter-row">
      <el-input
        v-model="filters.kw"
        placeholder="搜索任务名/基准表/来源文件"
        clearable
        style="width: 220px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="filters.status" multiple collapse-tags collapse-tags-tooltip clearable
        placeholder="状态" style="width: 180px" @change="search">
        <el-option v-for="s in STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="filters.tagIds" multiple collapse-tags collapse-tags-tooltip clearable
        placeholder="表的标记(基准/目标任一命中)" style="width: 220px" @change="search">
        <el-option v-for="tag in availableTags" :key="tag.id" :label="tag.name" :value="tag.id">
          <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle" :style="{ background: tag.color }" />
          {{ tag.name }}
        </el-option>
      </el-select>
      <el-select v-model="filters.datasourceId" clearable filterable placeholder="基准数据源"
        style="width: 200px" @change="search">
        <el-option v-for="ds in datasourceList" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-select v-model="filters.compareMode" clearable placeholder="比对模式" style="width: 130px" @change="search">
        <el-option label="行级" value="ROW" />
        <el-option label="行级+列级" value="COLUMN" />
      </el-select>
      <el-select v-model="filters.matchMode" clearable placeholder="匹配逻辑" style="width: 170px" @change="search">
        <el-option label="仅编码" value="LEGACY" />
        <el-option label="编码+名称" value="EXACT" />
        <el-option label="先编码后名称" value="CODE_THEN_NAME" />
        <el-option label="先编码后名称+AI" value="CODE_NAME_LLM" />
      </el-select>
      <el-button size="small" @click="resetFilters">重置</el-button>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      <template #title>
        比对为长时任务:创建后按「连接数据源 → 读取基准表 → 逐字段比对 → 生成报告」执行,运行中可留在本页观察进度
      </template>
    </el-alert>
    <el-table :data="tasks" v-loading="loading" border row-key="id" @selection-change="onSelectionChange">
      <!-- 运行中任务不可勾选(与单删同口径:RUNNING 禁删;PENDING 是静止状态可勾);row-key 让轮询重拉时勾选不丢 -->
      <el-table-column type="selection" width="45" :selectable="(row) => row.status !== 'RUNNING'" />
      <el-table-column label="任务" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <div>
            {{ row.name }}
            <!-- 对比模式标识:COLUMN=行级+列级全量字段比对(重),ROW=仅行级身份字段比对(老任务缺省同此) -->
            <el-tag size="small" :type="row.compareMode === 'COLUMN' ? 'warning' : 'info'" effect="plain" style="margin-left: 4px">
              {{ row.compareMode === 'COLUMN' ? '行级+列级' : '行级' }}
            </el-tag>
            <!-- 抽样标识(V70):sampleRows 非空 = 双侧各按身份字段排序取前 N 条比对,指标为样本口径 -->
            <el-tag v-if="row.sampleRows" size="small" type="success" effect="plain" style="margin-left: 4px">
              抽样 {{ row.sampleRows }} 条
            </el-tag>
          </div>
          <div style="color: var(--el-text-color-secondary); font-size: 12px">T-{{ row.id }}</div>
          <!-- 来源文件名:仅批量导入来的任务有(importId 非空),点击下载上传的 Excel 原件 -->
          <div
            v-if="row.importId && row.importFileName"
            class="import-src"
            :title="`来自批量导入:${row.importFileName}(点击下载原件)`"
            @click="downloadImportFile(row)"
          >
            <el-icon><Document /></el-icon>
            <span class="import-src-name">{{ row.importFileName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="基准表" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div>{{ row.baseDatasourceName || `数据源 ${row.baseDatasourceId}` }}</div>
          <div style="color: var(--el-text-color-secondary); font-size: 12px">{{ baseTableLabel(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="比对字段" width="90" align="center">
        <template #default="{ row }">{{ (row.fields || []).length }} 个</template>
      </el-table-column>
      <!-- 匹配逻辑:决定「两条数据算不算同一个对象」,老任务为空 = 仅按编码对齐 -->
      <el-table-column label="匹配逻辑" width="130" align="center">
        <template #default="{ row }">
          <el-tag size="small" type="info" plain>{{ matchModeLabel(row.matchMode) }}</el-tag>
        </template>
      </el-table-column>
      <!-- 列表视图不含目标数,逐任务详情接口补一次(缓存,不随轮询重拉) -->
      <el-table-column label="比对系统" width="90" align="center">
        <template #default="{ row }">
          <span v-if="targetCounts[row.id] != null">{{ targetCounts[row.id] }} 个系统</span>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
        </template>
      </el-table-column>
      <!-- 状态与进度合并为一列:状态标签在上,运行中下方显示进度条与阶段文案 -->
      <el-table-column label="状态 / 进度" width="150" align="center">
        <template #default="{ row }">
          <div style="display: inline-flex; flex-direction: column; align-items: center; gap: 4px">
            <div>
              <!-- 导出中:点「导出表格」后行内进度(蓝色加载 tag,与「映射推导中」同款),完成后由 exportFileOk 翻「已导出」 -->
              <el-tag v-if="exportingIds.has(row.id)" type="primary" size="small">
                <el-icon class="is-loading" style="margin-right: 2px"><Loading /></el-icon>导出中
              </el-tag>
              <!-- 已导出:DONE 且导出件 checksum 与库中一致;重新比对会复位 export_status,标签随之消失 -->
              <el-tag v-else-if="row.status === 'DONE' && row.exportFileOk" type="primary" size="small">已导出</el-tag>
              <!-- 待处理(批量导入):映射推导中=蓝色加载 tag,其余原因=灰色 tag + 原因 tooltip -->
              <el-tooltip v-else-if="row.status === 'PENDING'" :content="pendingReasonTip(row)" placement="top" :show-after="200">
                <el-tag v-if="row.pendingReason === 'MAPPING_RUNNING'" type="primary" size="small">
                  <el-icon class="is-loading" style="margin-right: 2px"><Loading /></el-icon>映射推导中
                </el-tag>
                <el-tag v-else type="info" size="small">待处理</el-tag>
              </el-tooltip>
              <el-tooltip v-else-if="row.status === 'FAILED' && row.error" :content="row.error" placement="top" :show-after="200">
                <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </el-tooltip>
              <el-tag v-else :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              <el-tag v-if="row.archived" size="small" type="info" plain style="margin-left: 4px">已归档</el-tag>
            </div>
            <!-- 进度条与阶段文案纵向排列,与抽样导出页同一写法 -->
            <template v-if="row.status === 'RUNNING'">
              <el-progress :percentage="row.progressPercent || 0" :stroke-width="10" style="width: 130px" />
              <span style="color: var(--el-text-color-secondary); font-size: 12px">{{ row.stage || '运行中' }}</span>
            </template>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="发起时间" width="165" class-name="nowrap-cell">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="110" class-name="nowrap-cell">
        <template #default="{ row }">
          <span v-if="row.status === 'RUNNING'">—</span>
          <span v-else>{{ formatDuration(row.startedAt, row.finishedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="400" fixed="right" class-name="nowrap-cell">
        <template #default="{ row }">
          <!-- flex 横排居中:link 按钮与「打开」下拉触发同轴对齐(混排 inline 时下拉触发带 icon 会基线错位) -->
          <div class="op-cell">
          <!-- 待处理任务:字段审核(弹画布确认映射后开始比对)/ 编辑(进向导逐步改);映射推导中(MAPPING_RUNNING)不显示(映射还没出来,没得审) -->
          <template v-if="row.status === 'PENDING' && row.pendingReason !== 'MAPPING_RUNNING'">
            <el-button link type="primary" @click="openMappingReview(row)">字段审核</el-button>
            <el-button link type="primary" @click="router.push(`/compare/new?edit=${row.id}`)">编辑</el-button>
          </template>
          <!-- 查看详情:DONE 看差异明细;RUNNING 也可进详情页(差异页自渲染「比对进行中」+进度条,随轮询到终态) -->
          <el-button v-if="row.status === 'DONE' || row.status === 'RUNNING'" link type="primary" @click="router.push(`/compare/${row.id}/diff`)">查看详情</el-button>
          <!-- 导出表格:总览 sheet + 每差异行一 sheet,与详情页「导出比对报告」同一接口;导出中禁重点 -->
          <el-button v-if="row.status === 'DONE'" link type="primary" :disabled="exportingIds.has(row.id)" @click="exportReport(row)">导出表格</el-button>
          <!-- 已完成任务可再次编辑:进向导改配置,保存后直接按新配置重新比对(旧差异明细覆盖) -->
          <el-button v-if="row.status === 'DONE'" link type="primary" @click="router.push(`/compare/new?edit=${row.id}`)">编辑</el-button>
          <el-button v-if="row.status === 'DONE' || row.status === 'FAILED'" link type="primary" @click="confirmRerun(row)">重新比对</el-button>
          <!-- 打开最近一次导出的比对报告(V65):服务端直存 <数据目录>/compare(任务 ID 前缀命名,同名覆盖),
               下拉收纳「打开文件/打开文件夹」;「打开文件」置灰口径=行级 exportFileOk(文件存在且 checksum 与 H2 一致),
               「打开文件夹」始终可点(无导出件时退化为打开 compare 目录) -->
          <el-dropdown v-if="row.status === 'DONE'" trigger="click" @command="(cmd) => onOpenExport(row, cmd)">
            <el-button link type="primary">打开<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="open" :disabled="!row.exportFileOk">打开文件</el-dropdown-item>
                <el-dropdown-item command="reveal">打开文件夹</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          </div>
          <!-- 删除不在操作列提供,统一走首列勾选 + 工具栏「批量删除」(单条=只勾一行) -->
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有比对任务,点击「+ 新建比对任务」发起" :image-size="60" />
      </template>
    </el-table>

    <!-- 分页(与错误中心同写法):筛选/改页大小时回第 1 页,翻页沿用当前筛选态;轮询也按当前页静默重拉 -->
    <div class="pager-row">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
        @current-change="load" @size-change="search" />
    </div>

    <!-- 字段审核弹窗:待处理任务核对/改线字段映射,确认后开始比对 -->
    <CompareMappingReview v-model="reviewVisible" :job-id="reviewJobId" @confirmed="load" />

    <!-- 导入表格弹窗(两步在一个 Dialog 内):①选 .xlsx 上传解析 → ②确认数据源映射清单后建任务(轮询批次到 DONE/FAILED) -->
    <el-dialog v-model="importVisible" title="导入比对任务" width="760px" destroy-on-close @closed="onImportClosed">
      <!-- 步骤 1:选择文件上传(未配置大模型时后端 409 拦下,拦截器统一提示) -->
      <template v-if="importStep === 0">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          <template #title>
            上传一张 xlsx:每个 sheet 一个比对任务(按「是否基准表」列定位基准行,恰一行「是」,其余为对比表)。上传后先确认数据源映射,
            再批量创建任务;字段映射由大模型在后台推导(任务列表「映射推导中」可见),推导完成后需逐个「字段审核」再开始比对
          </template>
        </el-alert>
        <el-upload
          ref="importUploadRef"
          drag
          :auto-upload="false"
          :limit="1"
          accept=".xlsx"
          :on-change="onImportFileChange"
          :on-exceed="onImportFileExceed"
          :on-remove="onImportFileRemove"
        >
          <el-icon style="font-size: 40px; color: var(--el-text-color-secondary)"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽文件到此处,或 <em>点击选择</em>(仅 .xlsx)</div>
        </el-upload>
        <div style="margin-top: 8px; text-align: right">
          <el-link type="primary" @click="downloadFile('/api/compare-import-template')">下载导入模版</el-link>
        </div>
      </template>
      <!-- 步骤 2:数据源映射清单确认(「匹配结果」列逐行下拉:现有数据源 + 待新建,可改绑;说明列保留原匹配提示);
           确认后进入建任务轮询,BUILDING 中下拉禁用定格选择,终态回结果 tag -->
      <template v-else>
        <el-alert v-if="importBuilding" type="info" :closable="false" style="margin-bottom: 12px">
          <template #title>正在创建任务:数据源建档与任务落库(连接实测与字段映射转后台推导),请稍候…</template>
        </el-alert>
        <el-alert v-else-if="importBatch?.status === 'FAILED'" type="error" :closable="false" style="margin-bottom: 12px">
          <template #title>创建任务失败:{{ importBatch.error || '未知错误' }}</template>
        </el-alert>
        <div v-if="importBatch" class="import-file-line">
          <span>文件:{{ importBatch.fileName }}<template v-if="importBatch.fileSize != null">({{ formatBytes(importBatch.fileSize) }})</template>,共 {{ importBatch.taskCount }} 个 sheet</span>
          <el-link type="primary" @click="downloadFile(`/api/compare-imports/${importBatch.id}/file`)">下载原件</el-link>
        </div>
        <el-table :data="importBatch?.dsReport || []" border size="small" max-height="340">
          <el-table-column label="数据源" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.name || '-' }}</template>
          </el-table-column>
          <el-table-column label="地址 / 库名" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.host">{{ row.host }}:{{ row.port }} / {{ row.databaseName }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <!-- 匹配结果:DS_REVIEW 时每行一个下拉(现有数据源 + 「待新建」,可改绑,默认带匹配结果);
               BUILDING 中禁用、定格用户选择;终态(DONE/FAILED)回到结果 tag(已建档绿/改绑黄/异常红/提示灰) -->
          <el-table-column label="匹配结果" min-width="230">
            <template #default="{ row }">
              <el-select
                v-if="row.key != null && (importBatch?.status === 'DS_REVIEW' || importBuilding)"
                v-model="importDsChoices[row.key]"
                size="small"
                style="width: 100%"
                :disabled="importBatch?.status !== 'DS_REVIEW' || importConfirming"
              >
                <el-option
                  v-for="d in importDsList" :key="d.id" :value="d.id"
                  :label="`现有:${d.name}`"
                />
                <el-option value="__new__" label="待新建(用表格连接信息实测建档)" />
              </el-select>
              <el-tag v-else size="small" :type="importActionType(row.action)">{{ importActionText(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span :style="row.action === 'ERROR' ? 'color: var(--el-color-danger)' : ''">{{ row.error || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <template v-if="importStep === 0">
          <el-button @click="importVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!importFile" :loading="importSubmitting" @click="doImportSubmit">上传并解析</el-button>
        </template>
        <template v-else>
          <el-button @click="importVisible = false">关闭</el-button>
          <el-button
            v-if="importBatch?.status === 'DS_REVIEW'"
            type="primary"
            :loading="importConfirming"
            @click="doImportConfirm"
          >确认并创建任务</el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { ArrowDown, Document, Loading, UploadFilled } from '@element-plus/icons-vue'
import request, {
  listCompareJobs, getCompareJob, rerunCompareJob, batchDeleteCompareJobs,
  submitCompareImport, getCompareImport, confirmCompareImport, openCompareExport, exportCompareReport
} from '../api'
import { downloadFile, notifyExportSaved } from '../utils/download'
import { formatBytes, formatDateTime, formatDuration, statusTagType, statusText } from '../utils/format'
import { ackTask } from '../stores/backgroundTasks'
import CompareMappingReview from '../components/CompareMappingReview.vue'

const router = useRouter()

const tasks = ref([])
const loading = ref(false)
// 导出中的任务 id 集(行内「导出中」状态,请求结束即删;Set 的 has 在 reactive 下可追踪)
const exportingIds = reactive(new Set())
const showArchived = ref(false)
// 分页(服务端分页,与错误中心同模式):total 由列表接口返回
const page = ref(1)
const size = ref(20)
const total = ref(0)
// 表格勾选行(批量删除用;轮询重拉后由 row-key 维持勾选态)
const selection = ref([])
// 筛选条件(服务端筛选;各 select change 即触发 load,轮询沿用同一条件)
const filters = reactive({ kw: '', status: [], tagIds: [], datasourceId: null, compareMode: null, matchMode: null })
// 标记下选项(全量标记含系统「空表/备份表」;GET /api/tags)
const availableTags = ref([])
// 基准数据源下选项
const datasourceList = ref([])
// 状态选项(比对任务实际只产出这四种;PENDING=批量导入待处理)
const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待处理' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'DONE', label: '完成' },
  { value: 'FAILED', label: '失败' }
]
// 各任务的目标系统数(列表视图不返回,从详情接口补;任务 id → 目标数)
const targetCounts = ref({})
let timer = null

/** 匹配逻辑短文案(与后端 CompareService.MatchMode 同一取值;老任务为空 = 仅按编码对齐) */
const MATCH_MODE_LABELS = {
  EXACT: '编码+名称',
  CODE_THEN_NAME: '先编码后名称',
  CODE_NAME_LLM: '先编码后名称+AI'
}

function matchModeLabel(mode) {
  return MATCH_MODE_LABELS[mode] || '仅编码'
}

/** 待处理原因(与后端 PendingReason 同一取值)→ tooltip 文案 */
const PENDING_REASON_TIPS = {
  DS_ERROR: '数据源异常:涉及的数据源连不上或未实测通过;修复数据源后仍可经「字段审核」确认开跑',
  MAPPING_RUNNING: '映射推导中:数据源连接实测与字段映射正在后台推导,完成后转「映射待审核」,届时可逐个审核',
  MAPPING_REVIEW: '映射待审核:字段映射已预生成,需人工审核(「字段审核」或「编辑」)确认后开始比对',
  IMPORT_ERROR: '导入异常:基准表校验未通过(表不存在或缺身份字段),请点「编辑」进向导修正'
}

function pendingReasonTip(row) {
  return PENDING_REASON_TIPS[row.pendingReason] || '待处理:需人工处理后才开始比对'
}

/** 基准表定位串:多库方言带库名 db.schema.table,否则 schema.table */
function baseTableLabel(row) {
  const schemaPart = row.baseDb ? `${row.baseDb}.${row.baseSchema || ''}` : (row.baseSchema || '')
  return schemaPart ? `${schemaPart}.${row.baseTable}` : row.baseTable
}

/** 下载该任务来源的导入 Excel 原件(文件名原样) */
function downloadImportFile(row) {
  downloadFile(`/api/compare-imports/${row.importId}/file`)
}

function needPolling() {
  // 除运行中的比对外,映射后台推导中(MAPPING_RUNNING)的任务也保持轮询,全部终态后停
  return tasks.value.some((t) => t.status === 'RUNNING'
    || (t.status === 'PENDING' && t.pendingReason === 'MAPPING_RUNNING'))
}

/** 列表已渲染到终态的任务登记 ack:全局后台任务跟踪器不再对其弹完成/失败通知(本页自己看得见) */
function ackTerminal() {
  for (const t of tasks.value) {
    if (t.status === 'DONE' || t.status === 'FAILED' || t.status === 'CANCELED') ackTask('compare', t.id)
  }
}

async function load() {
  loading.value = true
  try {
    const res = await listCompareJobs(showArchived.value, filters, page.value, size.value)
    tasks.value = res.rows || []
    total.value = res.total || 0
    ackTerminal()
    fillTargetCounts()
  } finally {
    loading.value = false
    if (needPolling() && !timer) startPolling()
  }
}

/** 筛选条件或页大小变化:回第 1 页再拉(翻页本身不改条件,直接 load) */
function search() {
  page.value = 1
  load()
}

/** 重置筛选并重新加载(显示已归档开关不动) */
function resetFilters() {
  filters.kw = ''
  filters.status = []
  filters.tagIds = []
  filters.datasourceId = null
  filters.compareMode = null
  filters.matchMode = null
  search()
}

/** 筛选条下选项:全量标记 + 数据源清单(静默,失败则对应下拉为空) */
function loadFilterOptions() {
  request.get('/tags').then((d) => { availableTags.value = d || [] }).catch(() => {})
  request.get('/datasources').then((d) => { datasourceList.value = d || [] }).catch(() => {})
}

/** 逐个任务补目标系统数:仅对未缓存的任务发详情请求(静默,失败保持「—」) */
function fillTargetCounts() {
  for (const t of tasks.value) {
    if (targetCounts.value[t.id] != null) continue
    getCompareJob(t.id, true)
      .then((d) => { targetCounts.value = { ...targetCounts.value, [t.id]: (d?.targets || []).length } })
      .catch(() => {})
  }
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    // 轮询按当前页静默重拉(失败保旧数据,下轮再试);翻页/改筛选由 load/search 自然带走
    const res = await listCompareJobs(showArchived.value, filters, page.value, size.value).catch(() => null)
    if (res) {
      tasks.value = res.rows || []
      total.value = res.total || 0
    }
    ackTerminal()
    if (!needPolling()) stopPolling()
  }, 1000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 导出表格(与详情页同一接口):行内状态列显「导出中」,服务端直存数据目录/compare 完成后通知,刷新列表翻「已导出」 */
async function exportReport(row) {
  exportingIds.add(row.id)
  try {
    const saved = await exportCompareReport(row.id)
    notifyExportSaved(saved.path)
  } catch { /* 拦截器已弹错误提示 */ } finally {
    exportingIds.delete(row.id)
  }
  load()
}

/** 打开最近一次导出的比对报告文件 / 其所在文件夹(下拉「打开」两项;未导出过 409 由拦截器提示) */
function onOpenExport(row, cmd) {
  openCompareExport(row.id, cmd === 'reveal').catch(() => { /* 拦截器已弹错误提示 */ })
}

/** 重新比对:确认后按原目标清单重跑(RUNNING 时后端 409,拦截器统一提示) */
async function confirmRerun(row) {
  try {
    await ElMessageBox.confirm(
      `将按任务「${row.name}」原目标清单重新比对,现有差异明细与报告会被覆盖。`,
      '重新比对',
      { type: 'warning', confirmButtonText: '重新比对', cancelButtonText: '取消' }
    )
  } catch { /* 用户取消 */ return }
  await rerunCompareJob(row.id)
  ElMessage.success(`任务 T-${row.id} 已开始重新比对`)
  await load()
}

function onSelectionChange(rows) {
  selection.value = rows
}

/** 批量删除勾选的任务:运行中不可勾选;勾选后开跑的由服务端跳过并在结果里提示 */
async function confirmBatchDelete() {
  const ids = selection.value.map((t) => t.id)
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `将删除 ${ids.length} 个任务及其差异明细与报告,删除后不可恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { /* 用户取消 */ return }
  const res = await batchDeleteCompareJobs(ids)
  const skipped = res.skipped || []
  if (skipped.length) {
    ElMessage.warning(`已删除 ${res.deleted.length} 个,跳过运行中的任务:${skipped.map((id) => `T-${id}`).join('、')}`)
  } else {
    ElMessage.success(`已删除 ${res.deleted.length} 个任务`)
  }
  // 当前页被删空且非首页时回退一页,避免停在越界空页
  if (tasks.value.length === (res.deleted?.length || 0) && page.value > 1) page.value -= 1
  await load()
}

// ---------- 字段审核(待处理任务) ----------

const reviewVisible = ref(false)
const reviewJobId = ref(null)

function openMappingReview(row) {
  reviewJobId.value = row.id
  reviewVisible.value = true
}

// ---------- 导入表格(单 Dialog 两步:上传解析 → 数据源映射确认 → 轮询建任务) ----------

const importVisible = ref(false)
// 0=选文件上传;1=数据源映射清单确认/建任务进度
const importStep = ref(0)
const importUploadRef = ref()
const importFile = ref(null)
const importSubmitting = ref(false)
// 批次详情(含 dsReport 数据源映射清单 / status / jobIds)
const importBatch = ref(null)
const importConfirming = ref(false)
// 数据源映射下拉:现有数据源清单(进入步骤②时拉一次)+ 每行选择(ds_report 行 key → 数据源 id 或 '__new__' 待新建)
const importDsList = ref([])
const importDsChoices = reactive({})
let importTimer = null

// 建任务中(BUILDING):确认后到 DONE/FAILED 之间,弹窗顶部给进度提示并轮询批次详情
const importBuilding = ref(false)

function openImport() {
  importStep.value = 0
  importFile.value = null
  importBatch.value = null
  importBuilding.value = false
  importVisible.value = true
}

function onImportFileChange(file) {
  const name = file.name || ''
  if (!name.toLowerCase().endsWith('.xlsx')) {
    ElMessage.warning('仅支持 .xlsx 文件')
    importUploadRef.value?.clearFiles()
    importFile.value = null
    return
  }
  importFile.value = file.raw
}

/** 超出 limit 时替换为最新选择的文件 */
function onImportFileExceed(files) {
  importUploadRef.value.clearFiles()
  importUploadRef.value.handleStart(files[0])
}

function onImportFileRemove() {
  importFile.value = null
}

/** 步骤 ①:上传 Excel 并解析(原件落盘留档),拿到批次详情后进步骤 ② 展示数据源映射清单 */
async function doImportSubmit() {
  if (!importFile.value || importSubmitting.value) return
  importSubmitting.value = true
  try {
    const res = await submitCompareImport(importFile.value)
    importBatch.value = await getCompareImport(res.batchId)
    // 映射下拉的现有数据源清单(拉取失败降级为空清单:只剩「待新建」可选,拦截器已提示)
    importDsList.value = await request.get('/datasources').catch(() => [])
    initImportDsChoices(importBatch.value)
    importStep.value = 1
  } catch { /* 拦截器已提示(未配置大模型 409 等) */ } finally {
    importSubmitting.value = false
  }
}

/** 数据源映射下拉默认值:MATCHED 行选匹配到的数据源,其余行默认「待新建」(NOTE 提示行不参与映射) */
function initImportDsChoices(batch) {
  Object.keys(importDsChoices).forEach((k) => delete importDsChoices[k])
  for (const row of batch?.dsReport || []) {
    if (row.key == null) continue
    importDsChoices[row.key] = row.datasourceId != null ? row.datasourceId : '__new__'
  }
}

/** 步骤 ②:确认数据源映射(带上用户逐行选择:行 key → 数据源 id,「待新建」为 null)→ 后台建档数据源 + 逐 sheet 建任务;2s 轮询批次详情直到 DONE/FAILED */
async function doImportConfirm() {
  if (!importBatch.value || importConfirming.value) return
  importConfirming.value = true
  try {
    const mapping = {}
    for (const row of importBatch.value.dsReport || []) {
      if (row.key == null) continue
      const choice = importDsChoices[row.key]
      mapping[row.key] = choice === '__new__' || choice == null ? null : choice
    }
    await confirmCompareImport(importBatch.value.id, mapping)
    importBuilding.value = true
    startImportPolling(importBatch.value.id)
  } catch { /* 拦截器已提示 */ } finally {
    importConfirming.value = false
  }
}

function startImportPolling(batchId) {
  stopImportPolling()
  importTimer = setInterval(async () => {
    const batch = await getCompareImport(batchId).catch(() => null)
    if (!batch) return // 轮询失败下个周期重试
    importBatch.value = batch
    if (batch.status === 'DONE' || batch.status === 'FAILED') {
      stopImportPolling()
      importBuilding.value = false
      if (batch.status === 'DONE') {
        const n = (batch.jobIds || []).length
        ElMessage.success(`已创建 ${n} 个任务,字段映射后台推导中,完成后可逐个审核`)
        importVisible.value = false
        await load()
      }
      // FAILED:错误已在弹窗顶部红条展示,留弹窗供核对原件
    }
  }, 2000)
}

function stopImportPolling() {
  if (importTimer) {
    clearInterval(importTimer)
    importTimer = null
  }
}

/** 数据源映射结果 action → tag 类型(已匹配绿/待新建黄/已建档绿/改绑黄/异常红;提示灰) */
function importActionType(a) {
  return { MATCHED: 'success', CREATE: 'warning', CREATED: 'success', REBOUND: 'warning', ERROR: 'danger', NOTE: 'info' }[a] || 'info'
}

/** 数据源映射结果 action → 中文文案 */
function importActionText(a) {
  return { MATCHED: '已匹配', CREATE: '待新建', CREATED: '已建档', REBOUND: '改绑', ERROR: '异常', NOTE: '提示' }[a] || a
}

/** 对话框完全关闭后重置状态并停轮询,供下次打开 */
function onImportClosed() {
  stopImportPolling()
  importStep.value = 0
  importFile.value = null
  importBatch.value = null
  importBuilding.value = false
  importDsList.value = []
  Object.keys(importDsChoices).forEach((k) => delete importDsChoices[k])
}

onMounted(() => { loadFilterOptions(); load() })

// 固定页签走 keep-alive:失活时停轮询,回来时刷新并按需恢复
onActivated(load)
onDeactivated(() => { stopPolling(); stopImportPolling() })
onUnmounted(() => { stopPolling(); stopImportPolling() })
</script>

<style scoped>
/* 工具栏按钮组:flex + gap 统一间距,并清掉 el-button 相邻默认 margin(与抽样导出页同写法) */
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}

/* 筛选条(与错误中心 .filter-row 同模式) */
.filter-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

/* 发起时间/耗时/操作列不换行 */
:deep(.nowrap-cell) {
  white-space: nowrap;
}

/* 操作列按钮组:flex + gap 统一间距并水平居中对齐——link 按钮与「打开」下拉触发同轴
   (混排 inline 时下拉触发带 icon 会基线错位,与同行按钮视觉上不在一条水平线) */
.op-cell {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: nowrap;
}
.op-cell :deep(.el-button) {
  margin-left: 0;
}

/* 分页条右对齐(与错误中心 .pager-row 同写法) */
.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

/* 任务名下方来源文件名(批量导入):小字灰字 + Excel 图标,点击下载原件 */
.import-src {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  max-width: 100%;
}
.import-src:hover {
  color: var(--el-color-primary);
}
.import-src-name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 导入弹窗步骤 ②:文件信息行(名称/大小/sheet 数 + 原件下载链接) */
.import-file-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
