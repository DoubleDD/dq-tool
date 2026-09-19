<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">导出中心</h3>
      <div style="display: flex; gap: 12px">
        <el-button :type="selection.length ? 'danger' : 'default'" :disabled="!selection.length" @click="removeBatch">批量删除{{ selection.length ? `(${selection.length})` : '' }}</el-button>
        <el-button :loading="loading" @click="load()">刷新</el-button>
      </div>
    </div>
    <!-- 筛选:类型 / 关键字 / 时间范围(创建日含当日);查询回第 1 页,翻页沿用筛选态 -->
    <div style="display: flex; gap: 12px; margin-bottom: 12px; align-items: center; flex-wrap: wrap">
      <el-select v-model="kindFilter" placeholder="类型" clearable style="width: 150px" @change="search">
        <el-option v-for="k in KIND_OPTIONS" :key="k.value" :label="k.label" :value="k.value" />
      </el-select>
      <el-input v-model="keyword" placeholder="按描述、文件名搜索" clearable style="width: 240px"
        @keyup.enter="search" @clear="search" />
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" style="width: 260px"
        start-placeholder="开始日期" end-placeholder="结束日期" @change="search" />
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table :data="items" v-loading="loading" border row-key="id" @selection-change="(rows) => (selection = rows)">
      <el-table-column type="selection" width="45" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="row.storage === 'DISK' ? 'warning' : 'info'">{{ kindLabel(row.kind) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="220" prop="title" show-overflow-tooltip />
      <!-- 文件列 = 文件名,点击系统默认应用直开;checksum 对不上(TAMPERED)仍可打开,旁边打「已篡改」标签提示;
           MISSING(磁盘上已删)删除线置灰禁点,「打开目录」降级开所在目录 -->
      <el-table-column label="文件" min-width="240">
        <template #default="{ row }">
          <template v-if="canOpen(row)">
            <el-button link type="primary" :title="row.fileName" @click="openFile(row)">{{ row.fileName }}</el-button>
            <el-tooltip v-if="row.fileState === 'TAMPERED'" content="文件内容与导出时不一致(校验和不匹配),可能已被修改" placement="top" :show-after="200">
              <el-tag type="danger" size="small" style="margin-left: 4px">已篡改</el-tag>
            </el-tooltip>
          </template>
          <span v-else :title="fileBlockTip(row)" :style="fileBlockStyle(row)">
            {{ row.fileName || '—' }}
          </span>
        </template>
      </el-table-column>
      <!-- 校验和:SHA-256 短显 16 位,悬浮全量 -->
      <el-table-column label="校验和" width="150">
        <template #default="{ row }">
          <span v-if="row.checksum" :title="`SHA-256: ${row.checksum}`"
            style="color: var(--el-text-color-secondary); font-size: 12px; font-family: monospace">
            {{ row.checksum.slice(0, 16) }}…
          </span>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100" align="right">
        <template #default="{ row }">{{ row.fileSize != null ? formatBytes(row.fileSize) : '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tooltip v-if="row.status === 'FAILED' && row.error" :content="row.error" placement="top" :show-after="200">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </el-tooltip>
          <el-tag v-else :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="175" class-name="nowrap-cell">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <!-- 操作列唯一入口:按 rel_path(相对数据目录)在文件管理器中选中产物;老记录无路径则不渲染 -->
          <el-button v-if="row.relPath" link type="primary" @click="openDir(row)">打开目录</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有导出记录,任意页面的导出完成后在此可查" :image-size="60" />
      </template>
    </el-table>

    <div class="pager-row">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
        @current-change="load()" @size-change="search" />
    </div>
  </div>
</template>

<script setup>
/**
 * 导出中心(V66):全部导出入口统一登记可查——同步流式导出(扫描 Excel/Word、库结构 Word、预览 xlsx、
 * 列表 xlsx、4 种 JSON 传输、模版)成功后登记,落盘类(报告 Word/抽样 zip/比对 xlsx)产物落盘即推送,
 * 记录含相对数据目录的详细路径(relPath)、大小、描述、时间(push 模型,单表全量,V67)。
 * 文件名点击系统默认应用直开,操作列只留「打开目录」,均按 rel_path 走 /system/open|reveal。
 * 诊断报告 .md(纯前端)与局域网 share 出口不在此列。生成中/排队项 2s 轮询(保留口径)。
 */
import { onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import request, { listExportCenter, deleteExportCenter } from '../api'
import { formatBytes, formatDateTime } from '../utils/format'

const KIND_OPTIONS = [
  { value: 'SCAN_EXCEL', label: '扫描 Excel' },
  { value: 'SCAN_WORD', label: '扫描 Word' },
  { value: 'DBSTRUCT_WORD', label: '库结构 Word' },
  { value: 'PREVIEW_XLSX', label: '预览 Excel' },
  { value: 'COMPARE_XLSX', label: '比对报告' },
  { value: 'LIST_XLSX', label: '列表 Excel' },
  { value: 'TRANSFER_DATASOURCE', label: '数据源 JSON' },
  { value: 'TRANSFER_METADATA', label: '元数据 JSON' },
  { value: 'TRANSFER_SCAN', label: '扫描记录 JSON' },
  { value: 'TRANSFER_ANNOTATION', label: '标注 JSON' },
  { value: 'TEMPLATE', label: '导入模版' },
  { value: 'ERROR_EXPORT', label: '错误中心导出' },
  { value: 'IMPORT_FILE', label: '导入原件' },
  { value: 'REPORT_DOCX', label: '调研报告' },
  { value: 'SAMPLE_ZIP', label: '抽样导出' }
]

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const kindFilter = ref(null)
const keyword = ref('')
const range = ref(null)
// 勾选批量删除(row-key=id 保证重拉勾选不丢;记录只是元数据,删除不动磁盘文件)
const selection = ref([])

function kindLabel(kind) {
  return KIND_OPTIONS.find((k) => k.value === kind)?.label || kind
}

function statusText(s) {
  return { SUCCESS: '成功', FAILED: '失败', PENDING: '排队中', RUNNING: '生成中', PAUSED: '已暂停' }[s] || s
}
function statusType(s) {
  return { SUCCESS: 'success', FAILED: 'danger', PENDING: 'info', RUNNING: 'primary', PAUSED: 'warning' }[s] || 'info'
}

function params() {
  return {
    ...(kindFilter.value ? { kind: kindFilter.value } : {}),
    ...(keyword.value?.trim() ? { keyword: keyword.value.trim() } : {}),
    ...(range.value?.[0] ? { start: range.value[0] } : {}),
    ...(range.value?.[1] ? { end: range.value[1] } : {}),
    page: page.value,
    size: size.value
  }
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await listExportCenter(params())
    items.value = data.items || []
    total.value = data.total || 0
  } catch { /* 拦截器已弹错误提示 */ } finally {
    loading.value = false
  }
}

/** 筛选/改页大小回第 1 页 */
function search() {
  page.value = 1
  load()
}

/** 打开产物目录(操作列唯一入口):统一按 rel_path 在文件管理器中选中;
 *  文件已被删(MISSING)时降级打开所在目录(目录仍在)。注意 request baseURL 已含 /api,传相对路径 */
function openDir(row) {
  const path = row.fileState === 'MISSING' ? row.relPath.replace(/[/\\][^/\\]*$/, '') : row.relPath
  request.post('/system/reveal', { path }).catch(() => { /* 拦截器已弹错误提示 */ })
}

/** 文件名是否可点击直开:文件在(OK 或 TAMPERED)即可点——文件名一致就能打开;
 *  只有 MISSING(已删)禁点,fileState 空=无 relPath 老记录也不点 */
function canOpen(row) {
  return !!row.fileName && !!row.relPath && row.fileState !== 'MISSING'
}

/** 禁点文件名的悬浮提示(仅 MISSING;TAMPERED 仍可打开,用「已篡改」标签提示) */
function fileBlockTip(row) {
  if (row.fileState === 'MISSING') return '文件已被删除,无法打开;可打开所在目录查看'
  return row.fileName
}

/** 禁点文件名的样式:已删 = 删除线 + 灰 */
function fileBlockStyle(row) {
  if (row.fileState === 'MISSING') return 'color: var(--el-text-color-secondary); text-decoration: line-through'
  return ''
}

/** 文件名直开:统一按 rel_path 走 /system/open,系统默认关联程序打开(全类型同一入口) */
function openFile(row) {
  request.post('/system/open', { path: row.relPath }).catch(() => { /* 拦截器已弹错误提示 */ })
}

/** 批量删除:逐条删登记记录(单删除端点循环;只删记录不动磁盘文件),失败计数并入提示 */
async function removeBatch() {
  const rows = selection.value
  try {
    await ElMessageBox.confirm(`删除选中的 ${rows.length} 条导出记录?只删记录,不影响已生成的文件。`,
      '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { /* 用户取消 */ return }
  let failed = 0
  for (const row of rows) {
    try {
      await deleteExportCenter(row.kind, row.id.split(':')[1])
    } catch { failed++ }
  }
  ElMessage.success(failed ? `已删除 ${rows.length - failed} 条,${failed} 条失败` : `已删除 ${rows.length} 条`)
  selection.value = []
  load()
}

// 生成中/排队项存在时 2s 轮询,全终态停止(照报告列表页签口径)
let timer = null
function hasActive() {
  return items.value.some((i) => i.status === 'PENDING' || i.status === 'RUNNING')
}
function startPolling() {
  if (!timer) timer = setInterval(() => load(true), 2000)
}
function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}
watch(items, () => (hasActive() ? startPolling() : stopPolling()))

onMounted(() => {
  load()
  if (hasActive()) startPolling()
})
onActivated(() => {
  load(true)
  if (hasActive()) startPolling()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

/* 时间列不换行(与比对任务列表 nowrap-cell 同口径) */
:deep(.nowrap-cell) {
  white-space: nowrap;
}
</style>
