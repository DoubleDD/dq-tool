<template>
  <div class="page-card errors-page">
    <div class="toolbar">
      <h3 style="margin: 0">错误中心</h3>
      <div style="display: flex; gap: 8px; align-items: center">
        <el-checkbox v-model="autoRefresh">自动刷新(5s)</el-checkbox>
        <el-button size="small" :loading="loading" @click="reload">
          <el-icon><Refresh /></el-icon>
          <span style="margin-left: 4px">刷新</span>
        </el-button>
        <el-dropdown size="small" @command="onExport">
          <el-button size="small">
            导出<el-icon style="margin-left: 4px"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <!-- 勾选了行 → 优先导出所选(单行/多行);未勾选 → 导出当前筛选结果 -->
              <template v-if="selected.length">
                <el-dropdown-item command="selected:markdown">导出所选 {{ selected.length }} 条 Markdown</el-dropdown-item>
                <el-dropdown-item command="selected:json">导出所选 {{ selected.length }} 条 JSON</el-dropdown-item>
                <el-dropdown-item divided command="all:markdown">导出全部筛选结果 Markdown</el-dropdown-item>
                <el-dropdown-item command="all:json">导出全部筛选结果 JSON</el-dropdown-item>
              </template>
              <template v-else>
                <el-dropdown-item command="all:markdown">导出全部筛选结果 Markdown(发开发排错)</el-dropdown-item>
                <el-dropdown-item command="all:json">导出全部筛选结果 JSON</el-dropdown-item>
              </template>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-dropdown size="small" @command="onMaintenance">
          <el-button size="small">清理<el-icon style="margin-left: 4px"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="clear">清空当前筛选结果</el-dropdown-item>
              <el-dropdown-item command="purge30">清理 30 天前记录</el-dropdown-item>
              <el-dropdown-item command="purge7">清理 7 天前记录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <!-- 采集健康度:只在错误中心自身采集/落库异常时才出现,正常情况不占位、不算统计 -->
    <div v-if="stats.spoolPending || stats.dropped" class="collect-alert">
      <el-icon><Warning /></el-icon>
      <span v-if="stats.spoolPending">有启动期错误待回灌,内核就绪后自动入库</span>
      <span v-if="stats.dropped">错误写库失败已落盘 {{ stats.dropped }} 次</span>
    </div>

    <div class="filter-row">
      <el-select v-model="filters.sources" multiple collapse-tags placeholder="来源" clearable style="width: 200px">
        <el-option v-for="s in SOURCE_OPTIONS" :key="s" :label="SOURCE_LABEL[s]" :value="s" />
      </el-select>
      <el-select v-model="filters.levels" multiple collapse-tags placeholder="级别" clearable style="width: 150px">
        <el-option v-for="l in LEVEL_OPTIONS" :key="l" :label="l" :value="l" />
      </el-select>
      <el-date-picker v-model="filters.range" type="daterange" value-format="YYYY-MM-DD"
        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 250px" />
      <el-input v-model="filters.keyword" placeholder="搜索消息/类型/位置/堆栈" clearable style="width: 240px"
        @keyup.enter="search" />
      <el-button type="primary" size="small" @click="search">查询</el-button>
      <el-button size="small" @click="resetFilters">重置</el-button>
      <span style="margin-left: auto; color: var(--el-text-color-secondary); font-size: 12px">
        共 {{ total }} 条聚合错误
      </span>
    </div>

    <!-- 勾选行后出现:提示已选并可直接导出所选(跨页保留勾选,故提供一键清空) -->
    <div class="select-row" v-if="selected.length">
      <span class="select-hint">已选 {{ selected.length }} 条</span>
      <el-button size="small" @click="onExport('selected:markdown')">导出所选 Markdown</el-button>
      <el-button size="small" @click="onExport('selected:json')">导出所选 JSON</el-button>
      <el-button size="small" text @click="clearSelection">清空选择</el-button>
    </div>

    <div ref="tableWrapRef" class="table-wrap">
      <!-- max-height 取容器实际高度(ResizeObserver):表头固定 + 列表内部纵向滚动,分页条始终可见 -->
      <!-- row-key + reserve-selection:勾选跨分页保留(导出所选时不会因翻页丢勾选) -->
      <el-table ref="tableRef" :data="rows" v-loading="loading" size="small" class="error-table"
        row-key="id" :max-height="tableMaxHeight" @row-click="onRowClick" @selection-change="onSelectionChange">
      <el-table-column type="selection" width="42" reserve-selection fixed="left" />
      <el-table-column label="来源" width="86">
        <template #default="{ row }">
          <el-tag :type="SOURCE_TAG[row.source] || 'info'" size="small" effect="plain">
            {{ SOURCE_LABEL[row.source] || row.source }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="76">
        <template #default="{ row }">
          <el-tag :type="LEVEL_TAG[row.level] || 'info'" size="small">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="kind" label="类型" width="180" show-overflow-tooltip />
      <el-table-column prop="message" label="消息" min-width="280" show-overflow-tooltip />
      <el-table-column label="位置" width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ decodeUrlText(row.route) || '-' }}</template>
      </el-table-column>
      <el-table-column label="次数" width="70" align="right">
        <template #default="{ row }">
          <span class="occ">{{ row.occurrences }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近发生" width="160">
        <template #default="{ row }">{{ formatTs(row.lastSeen) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
          <el-button link type="primary" size="small" @click.stop="exportOne(row, 'markdown')">导出</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span style="color: var(--el-text-color-secondary)">暂无错误记录</span>
      </template>
      </el-table>
    </div>

    <div class="pager-row">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        :page-sizes="[20, 50, 100, 200]" layout="total, sizes, prev, pager, next, jumper"
        @current-change="reload" @size-change="search" />
    </div>

    <!-- 详情抽屉:只读展示最近一次完整消息/上下文/堆栈(不做处置,仅供排错查看) -->
    <el-drawer v-model="detailVisible" :title="detailTitle" size="60%">
      <div v-if="detail" class="detail-body">
        <!-- label-width 固定 + 标签不换行:长值(URL/指纹)不再把标签列挤成竖排;
             超长字段单独占整行(span=2),值允许折行完整展示 -->
        <el-descriptions :column="2" border size="small" label-width="88px" class="detail-desc">
          <el-descriptions-item label="来源">
            <el-tag :type="SOURCE_TAG[detail.source] || 'info'" size="small" effect="plain">
              {{ SOURCE_LABEL[detail.source] || detail.source }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="级别">
            <el-tag :type="LEVEL_TAG[detail.level] || 'info'" size="small">{{ detail.level }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型" :span="2">{{ detail.kind }}</el-descriptions-item>
          <el-descriptions-item label="累计次数">{{ detail.occurrences }}</el-descriptions-item>
          <el-descriptions-item label="首次发生">{{ formatTs(detail.firstSeen) }}</el-descriptions-item>
          <el-descriptions-item label="最近发生">{{ formatTs(detail.lastSeen) }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ detail.appVersion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="logger">{{ detail.logger || '-' }}</el-descriptions-item>
          <el-descriptions-item label="线程" :span="2">{{ detail.thread || '-' }}</el-descriptions-item>
          <el-descriptions-item label="位置" :span="2">{{ decodeUrlText(detail.route) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="指纹" :span="2">
            <span class="mono small">{{ detail.fingerprint }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-section">
          <div class="detail-label">错误消息</div>
          <pre class="detail-pre">{{ detail.message || '-' }}</pre>
        </div>

        <div class="detail-section" v-if="detail.context">
          <div class="detail-label">上下文</div>
          <pre class="detail-pre">{{ decodeUrlText(detail.context) }}</pre>
        </div>

        <div class="detail-section" v-if="detail.detail">
          <div class="detail-label">堆栈</div>
          <pre class="detail-pre detail-stack">{{ detail.detail }}</pre>
        </div>

        <div class="detail-actions">
          <el-button @click="copyDetail">复制详情</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onActivated, onDeactivated, onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Refresh, ArrowDown, Warning } from '@element-plus/icons-vue'
import request from '../api'
import { ElMessage } from '../utils/notify'
import { downloadFile, downloadText } from '../utils/download'

const SOURCE_OPTIONS = ['FRONTEND', 'BACKEND', 'DATABASE', 'TASK', 'STARTUP']
const LEVEL_OPTIONS = ['ERROR', 'WARN', 'FATAL']
const SOURCE_LABEL = { FRONTEND: '前端', BACKEND: '后端', DATABASE: '数据库', TASK: '任务', STARTUP: '启动' }
const SOURCE_TAG = { FRONTEND: 'warning', BACKEND: 'danger', DATABASE: 'danger', TASK: 'warning', STARTUP: 'info' }
const LEVEL_TAG = { ERROR: 'danger', WARN: 'warning', FATAL: 'danger' }

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const loading = ref(false)
const autoRefresh = ref(false)
const filters = reactive({ sources: [], levels: [], range: null, keyword: '' })
/** 只保留采集健康度两项(页面不做统计展示);由 /errors/stats 返回,仅在异常时提示 */
const stats = reactive({ dropped: 0, spoolPending: false })

const detailVisible = ref(false)
const detail = ref(null)

// 表格最大高度直接取容器实际高度(ResizeObserver 监听):提示条/勾选条出现消失、窗口变化都会触发重算。
// 不用视口估算 —— 视口法在提示条消失后不会重算,表格过高会溢出盖住分页条。
const tableWrapRef = ref(null)
const tableMaxHeight = ref(600)
let tableWrapRO = null
function updateTableMaxHeight() {
  const h = tableWrapRef.value?.clientHeight
  if (h) tableMaxHeight.value = Math.floor(h)
}

/** 表格引用 + 当前勾选行(随 row-key 跨分页保留;仅用于「导出所选」,不是处置) */
const tableRef = ref(null)
const selected = ref([])
function onSelectionChange(val) {
  selected.value = val
}
function clearSelection() {
  tableRef.value?.clearSelection()
  selected.value = []
}

let timer = null

const detailTitle = computed(() => (detail.value ? `${detail.value.kind}(×${detail.value.occurrences})` : '错误详情'))

/** 组装查询参数:数组用逗号拼接(后端按逗号拆),时间范围转 from/to */
function queryParams(extra = {}) {
  const params = {
    page: page.value,
    size: size.value,
    ...extra
  }
  if (filters.sources.length) params.source = filters.sources.join(',')
  if (filters.levels.length) params.level = filters.levels.join(',')
  if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
  if (filters.range && filters.range.length === 2) {
    params.from = filters.range[0]
    params.to = filters.range[1]
  }
  return params
}

async function reload() {
  loading.value = true
  try {
    const [list, st] = await Promise.all([
      request.get('/errors', { params: queryParams(), _silent: true }),
      request.get('/errors/stats', { _silent: true })
    ])
    rows.value = list.items || []
    total.value = list.total || 0
    Object.assign(stats, { dropped: st.dropped || 0, spoolPending: !!st.spoolPending })
  } catch { /* 拦截器已提示;错误中心自身失败不弹窗刷屏 */ } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  // 筛选/翻页会换掉列表数据,勾选随之失效 —— 显式清空,避免导出了看不见的行
  clearSelection()
  reload()
}

function resetFilters() {
  filters.sources = []
  filters.levels = []
  filters.range = null
  filters.keyword = ''
  search()
}

async function openDetail(row) {
  try {
    detail.value = await request.get(`/errors/${row.id}`, { _silent: true })
    detailVisible.value = true
  } catch { /* 已提示 */ }
}

/** 行点击打开详情;点复选框列不触发(勾选与查看是两种意图,与 RelationBatchDialog 同口径) */
function onRowClick(row, column) {
  if (column?.type === 'selection') return
  openDetail(row)
}

async function onMaintenance(command) {
  if (command === 'clear') {
    const scoped = filters.sources.length || filters.levels.length ||
      filters.keyword.trim() || filters.range
    try {
      await ElMessageBox.confirm(
        scoped ? '确认清空「当前筛选条件」下的错误记录?' : '未设置筛选条件,将清空全部错误记录,确认继续?',
        '清空确认', { type: 'warning' }
      )
    } catch {
      return
    }
    try {
      const res = await request.post('/errors/clear', {
        sources: filters.sources,
        levels: filters.levels,
        keyword: filters.keyword.trim() || null,
        from: filters.range?.[0] || null,
        to: filters.range?.[1] || null
      })
      ElMessage.success(`已清空 ${res.deleted} 条`)
      reload()
    } catch { /* 已提示 */ }
    return
  }
  const days = command === 'purge30' ? 30 : 7
  try {
    await ElMessageBox.confirm(`确认清理 ${days} 天前的错误记录?`, '清理确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    const res = await request.post('/errors/purge', { days })
    ElMessage.success(`已清理 ${res.deleted} 条`)
    reload()
  } catch { /* 已提示 */ }
}

/**
 * 导出:command 形如 `all:markdown`(当前筛选结果)或 `selected:json`(勾选行)。
 * 所选行按 id 传给后端(不受筛选/分页影响),未勾选时沿用筛选条件。
 */
function onExport(command) {
  const [scope, format] = String(command).split(':')
  const params = scope === 'selected'
    ? { format, ids: selected.value.map((r) => r.id).join(',') }
    : queryParams({ format })
  delete params.page
  delete params.size
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') qs.append(k, v)
  }
  downloadFile('/api/errors/export?' + qs.toString())
}

/** 单行导出(操作列):等价于只勾选这一行 */
function exportOne(row, format) {
  downloadFile(`/api/errors/export?format=${format}&ids=${row.id}`)
}

function copyDetail() {
  if (!detail.value) return
  const d = detail.value
  const text = [
    `[${d.source}/${d.level}] ${d.kind} ×${d.occurrences}`,
    `消息: ${d.message}`,
    `位置: ${decodeUrlText(d.route) || '-'}`,
    `首次/最近: ${d.firstSeen} / ${d.lastSeen}`,
    d.context ? `上下文:\n${decodeUrlText(d.context)}` : '',
    d.detail ? `堆栈:\n${d.detail}` : ''
  ].filter(Boolean).join('\n')
  try {
    navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    downloadText(`dq-error-${d.id}.txt`, text, 'text/plain')
  }
}

function formatTs(ts) {
  if (!ts) return '-'
  return String(ts).replace('T', ' ').slice(0, 19)
}

/**
 * 展示用百分号解码:接口路径/页面地址里的查询串是编码过的(如 `?name=%E4%BB%BB%E5%8A%A1`),
 * 原文直接展示读不了。只在确实含 %XX 时解码,且**不把 + 当空格**(decodeURIComponent 语义,保持路径原义);
 * 解码失败(裸 % 等非法序列)时按行兜底,仍失败的行原样返回 —— 绝不让展示层因脏数据报错。
 * 数据库里始终保留原始值,这里只影响展示与复制/导出文本。
 */
function decodeUrlText(text) {
  if (!text || !/%[0-9A-Fa-f]{2}/.test(text)) return text
  try {
    return decodeURIComponent(text)
  } catch {
    return String(text).split('\n').map((line) => {
      if (!/%[0-9A-Fa-f]{2}/.test(line)) return line
      try {
        return decodeURIComponent(line)
      } catch {
        return line
      }
    }).join('\n')
  }
}

function startTimer() {
  if (timer || !autoRefresh.value) return
  timer = setInterval(reload, 5000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function watchAutoRefresh() {
  if (autoRefresh.value) startTimer()
  else stopTimer()
}

onMounted(() => {
  updateTableMaxHeight()
  tableWrapRO = new ResizeObserver(updateTableMaxHeight)
  if (tableWrapRef.value) tableWrapRO.observe(tableWrapRef.value)
})

onActivated(() => {
  reload()
  watchAutoRefresh()
  nextTick(updateTableMaxHeight)
})

onDeactivated(() => {
  stopTimer()
})

onUnmounted(() => {
  stopTimer()
  tableWrapRO?.disconnect()
  tableWrapRO = null
})

// 自动刷新开关变化时启停定时器
watch(autoRefresh, watchAutoRefresh)
</script>

<style scoped>
.errors-page {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  height: calc(100% - 40px);
  gap: 10px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 勾选行后的操作条(仅导出所用,不是处置) */
.select-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.select-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 采集健康度提示:只在错误中心自身采集/落库异常时出现(不算统计,正常不占位) */
.collect-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  font-size: 12px;
}

.filter-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.batch-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.table-wrap {
  flex: 1;
  min-height: 0;
  /* 硬兜底:即便 max-height 短暂失配,表格也只会被裁掉而不盖住下方分页条 */
  overflow: hidden;
}

.error-table {
  width: 100%;
}

.pager-row {
  display: flex;
  justify-content: flex-end;
}

.occ {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 详情表:标签固定单行(不随超长值被挤成竖排),值超长时折行完整展示 */
.detail-desc :deep(.el-descriptions__label) {
  white-space: nowrap;
  width: 88px;
  min-width: 88px;
}

.detail-desc :deep(.el-descriptions__content) {
  word-break: break-all;
  white-space: normal;
}

.detail-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.detail-pre {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}

.detail-stack {
  color: var(--el-color-danger);
  max-height: 340px;
}

.detail-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.mono {
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
}

.small {
  font-size: 11px;
}
</style>
