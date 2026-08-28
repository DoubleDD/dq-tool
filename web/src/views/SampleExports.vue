<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">抽样导出</h3>
      <!-- 按钮组统一右侧、gap 统一间距(与表列表页 toolbar-actions 同写法) -->
      <div class="toolbar-actions">
        <el-button type="primary" @click="uploadDialogVisible = true">导入 Excel 并导出</el-button>
        <el-button type="danger" plain :disabled="!selection.length" @click="confirmBatchDelete">批量删除</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>
    <el-table :data="tasks" v-loading="loading" border @selection-change="onSelectionChange">
      <!-- 运行中/排队中的任务不可勾选(无法删除),其余状态均可 -->
      <el-table-column type="selection" width="45" :selectable="(row) => row.status !== 'RUNNING' && row.status !== 'PENDING'" />
      <el-table-column label="ID" width="60" prop="id" sortable />
      <el-table-column label="文件名" min-width="200" prop="fileName" sortable show-overflow-tooltip>
        <template #default="{ row }">{{ row.fileName || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" sortable :sort-method="(a, b) => statusOrder(a.status) - statusOrder(b.status)">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总进度" min-width="180" sortable :sort-method="(a, b) => percent(a) - percent(b)">
        <template #default="{ row }">
          <!-- 进度条与阶段文案纵向排列,与报告列表页同一写法 -->
          <span style="display: inline-flex; flex-direction: column; gap: 2px; vertical-align: middle">
            <el-progress :percentage="percent(row)" :stroke-width="10" style="width: 130px" />
            <span v-if="row.status === 'RUNNING' || row.status === 'PAUSED'" style="color: var(--el-text-color-secondary); font-size: 12px">{{ row.status === 'PAUSED' ? '已暂停' : row.stage || '运行中' }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="数据源导入" min-width="230">
        <template #default="{ row }">
          <span style="font-size: 12px">
            共 {{ row.dsTotal ?? 0 }} · 新增 {{ row.dsAdded ?? 0 }} · 跳过 {{ row.dsSkipped ?? 0 }} · 修复 {{ row.dsFixed ?? 0 }} ·
            <span :style="row.dsError > 0 ? 'color: var(--el-color-danger)' : ''">错误 {{ row.dsError ?? 0 }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="zip 大小" width="100" align="right" prop="zipSize" sortable>
        <template #default="{ row }">{{ row.zipSize != null ? formatBytes(row.zipSize) : '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="175" prop="createdAt" sortable class-name="nowrap-cell">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="275" fixed="right" class-name="nowrap-cell">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">明细</el-button>
          <el-button v-if="row.status === 'RUNNING'" link type="warning" @click="pauseTask(row)">暂停</el-button>
          <el-button v-if="row.status === 'PAUSED'" link type="primary" @click="resumeTask(row)">继续</el-button>
          <el-button v-if="row.status === 'DONE' && row.zipFileName" link type="primary" @click="download(row)">下载 zip</el-button>
          <el-button link type="primary" @click="openDir(row)">打开目录</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有抽样导出任务,点击「导入 Excel 并导出」提交" :image-size="60" />
      </template>
    </el-table>

    <!-- 导入 Excel 对话框:选文件后点确定上传 -->
    <el-dialog v-model="uploadDialogVisible" title="导入 Excel 并导出" width="520px" destroy-on-close @closed="onUploadClosed">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px">
        <template #title>
          上传后自动:① 提取并去重数据源加入系统(连不上的标记错误)② 每行一张表抽 50 行数据,按 数据源×类别 生成 Excel,最终按类别目录打 zip
        </template>
      </el-alert>
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx"
        :on-change="onFileChange"
        :on-exceed="onFileExceed"
        :on-remove="onFileRemove"
      >
        <el-icon style="font-size: 40px; color: var(--el-text-color-secondary)"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处,或 <em>点击选择</em>(仅 .xlsx)</div>
      </el-upload>
      <div style="margin-top: 8px; text-align: right">
        <el-link type="primary" href="/api/sample-export-template" download="抽样导入模版.xlsx">下载导入模版</el-link>
      </div>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!uploadFile" :loading="uploading" @click="doUpload">开始导出</el-button>
      </template>
    </el-dialog>

    <!-- 任务明细抽屉:数据源导入明细 + 表导出明细 -->
    <el-drawer v-model="drawerVisible" :title="`任务 #${detailId} 明细`" size="75%" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-collapse v-model="collapseActive">
            <el-collapse-item title="数据源导入明细" name="ds">
              <el-table :data="detail.dsReport || []" border size="small">
                <el-table-column label="名称" min-width="140" prop="name" show-overflow-tooltip>
                  <template #default="{ row }">
                    <!-- 可定位到数据源时点击名称直接弹数据源编辑框 -->
                    <el-link v-if="row.datasourceId" type="primary" @click="goEditDs(row)">{{ row.name }}</el-link>
                    <span v-else>{{ row.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="地址" min-width="150">
                  <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
                </el-table-column>
                <el-table-column label="数据库" min-width="120" prop="databaseName" show-overflow-tooltip />
                <el-table-column label="结果" width="130">
                  <template #default="{ row }">
                    <el-tag :type="dsActionType(row.action)" size="small">{{ dsActionText(row.action) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="说明" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-link v-if="row.message" type="primary" class="msg-link" @click="showMsg('数据源导入详情', row)">
                      <span class="msg-ellipsis">{{ row.message }}</span>
                    </el-link>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
            <el-collapse-item title="表导出明细" name="items">
              <el-table :data="detail.items || []" border size="small">
                <el-table-column label="序号" width="70" prop="seq" sortable />
                <el-table-column label="类别" width="110" prop="category" sortable show-overflow-tooltip />
                <el-table-column label="表" min-width="180" sortable :sort-method="(a, b) => (a.tableCnName || a.tableName || '').localeCompare(b.tableCnName || b.tableName || '')">
                  <template #default="{ row }">
                    <!-- 可定位到数据源时整格为链接:点击打开该数据源页签并跳到该表字段明细页 -->
                    <el-link v-if="canJumpTable(row)" type="primary" @click="goTable(row)">
                      <span style="text-align: left">
                        <div>{{ row.tableCnName || '-' }}</div>
                        <div style="color: var(--el-text-color-secondary); font-size: 12px">{{ row.tableName }}</div>
                      </span>
                    </el-link>
                    <template v-else>
                      <div>{{ row.tableCnName || '-' }}</div>
                      <div style="color: var(--el-text-color-secondary); font-size: 12px">{{ row.tableName }}</div>
                    </template>
                  </template>
                </el-table-column>
                <el-table-column label="库 / 模式" min-width="140" sortable :sort-method="(a, b) => (a.databaseName || '').localeCompare(b.databaseName || '') || (a.schemaName || '').localeCompare(b.schemaName || '')">
                  <template #default="{ row }">
                    <!-- 点击打开该数据源页签并跳到对应库的表列表 -->
                    <el-link v-if="canJumpSchema(row)" type="primary" @click="goSchema(row)">
                      {{ row.databaseName || '-' }} / {{ row.schemaName || '-' }}
                    </el-link>
                    <template v-else>{{ row.databaseName || '-' }} / {{ row.schemaName || '-' }}</template>
                  </template>
                </el-table-column>
                <el-table-column label="数据源" min-width="120" prop="datasourceName" sortable show-overflow-tooltip>
                  <template #default="{ row }">{{ row.datasourceName || (row.datasourceId ? `数据源 ${row.datasourceId}` : '-') }}</template>
                </el-table-column>
                <el-table-column label="进度" width="130" sortable :sort-method="(a, b) => itemPercent(a) - itemPercent(b)">
                  <template #default="{ row }">
                    <el-progress
                      :percentage="itemPercent(row)"
                      :status="itemProgressStatus(row)"
                      :striped="row.status === 'RUNNING'"
                      :striped-flow="row.status === 'RUNNING'"
                      :stroke-width="10"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="行数" width="90" align="right" prop="rowCount" sortable>
                  <template #default="{ row }">{{ row.rowCount != null ? row.rowCount : '-' }}</template>
                </el-table-column>
                <el-table-column label="错误" min-width="160" sortable :sort-method="(a, b) => (a.error || '').localeCompare(b.error || '')" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-link v-if="row.error" type="danger" class="msg-link" @click="showMsg('表导出错误详情', row)">
                      <span class="msg-ellipsis">{{ row.error }}</span>
                    </el-link>
                    <span v-else style="color: var(--el-text-color-secondary)">-</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </template>
      </div>
    </el-drawer>

    <!-- 说明/错误全文查看(数据源导入明细与表导出明细共用) -->
    <el-dialog v-model="msgDialog.visible" :title="msgDialog.title" width="640px" append-to-body>
      <div v-if="msgDialog.context" class="msg-context">{{ msgDialog.context }}</div>
      <pre class="msg-content">{{ msgDialog.content }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import request from '../api'
import { formatBytes, formatDateTime } from '../utils/format'
import { tabState } from '../stores/tabs'

const router = useRouter()

const tasks = ref([])
const loading = ref(false)
const selection = ref([])
let timer = null

// 上传对话框
const uploadDialogVisible = ref(false)
const uploadRef = ref()
const uploadFile = ref(null)
const uploading = ref(false)

// 明细抽屉
const drawerVisible = ref(false)
const detailId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const collapseActive = ref(['ds', 'items'])

// 说明/错误全文弹窗(数据源导入明细与表导出明细共用)
const msgDialog = ref({ visible: false, title: '', context: '', content: '' })

/** 点开截断的说明/错误看全文;row 为 dsReport 行(含 name/host)或 item 行(含 tableName) */
function showMsg(title, row) {
  const isDs = row.name != null
  msgDialog.value = {
    visible: true,
    title,
    context: isDs
      ? `${row.name}  ${row.host ?? ''}:${row.port ?? ''} / ${row.databaseName ?? ''}`
      : `${row.tableCnName || row.tableName || ''}  ${row.databaseName ?? ''} / ${row.schemaName ?? ''}`,
    content: row.message || row.error || '',
  }
}

// ---------- 明细行跳转数据源页签(路由落在 /datasources/:id/ 下,页签系统自动激活/创建 ds-{id} 页签) ----------

/** 点数据源导入明细的名称:与侧边栏编辑图标同一机制——写 pendingDsEditId 并跳数据源页,由数据源页弹编辑框 */
function goEditDs(row) {
  tabState.pendingDsEditId = String(row.datasourceId)
  router.push('/datasources')
}

/** 明细行 → 库列表里的「库名(Schema)」:模式名优先,空则库名兜底(与后端抽样 schema ?: database 同口径) */
function itemSchema(row) {
  return row.schemaName || row.databaseName || ''
}

/** 多库方言(SQL Server/Kingbase)下钻需带 ?db= 指定数据库,与库列表页 goTables 同一口径 */
function itemDbQuery(row) {
  return ['SQLSERVER', 'KINGBASE'].includes(row.dbType) && row.databaseName && row.schemaName
    ? `?db=${encodeURIComponent(row.databaseName)}`
    : ''
}

function canJumpSchema(row) {
  return row.datasourceId && itemSchema(row)
}

function canJumpTable(row) {
  return canJumpSchema(row) && row.tableName
}

/** 点库/模式:打开该数据源页签并跳到对应库的表列表 */
function goSchema(row) {
  router.push(`/datasources/${row.datasourceId}/schemas/${encodeURIComponent(itemSchema(row))}/tables${itemDbQuery(row)}`)
}

/** 点表名:打开该数据源页签并跳到该表字段明细页(未扫描口径;已扫描的统计从表列表进入) */
function goTable(row) {
  router.push(
    `/datasources/${row.datasourceId}/schemas/${encodeURIComponent(itemSchema(row))}/tables/${encodeURIComponent(row.tableName)}${itemDbQuery(row)}`
  )
}

function statusType(s) {
  return { PENDING: 'info', RUNNING: 'primary', PAUSED: 'warning', DONE: 'success', FAILED: 'danger' }[s] || 'info'
}

function statusText(s) {
  return { PENDING: '排队中', RUNNING: '运行中', PAUSED: '已暂停', DONE: '完成', FAILED: '失败' }[s] || s
}

/** 状态排序权重:排队中 < 运行中 < 已暂停 < 完成 < 失败 */
function statusOrder(s) {
  return { PENDING: 0, RUNNING: 1, PAUSED: 2, DONE: 3, FAILED: 4 }[s] ?? 99
}

function percent(row) {
  if (!row.totalItems) return 0
  return Math.min(100, Math.round((row.doneItems / row.totalItems) * 100))
}

/** 数据源导入结果 action → tag 类型 */
function dsActionType(a) {
  return { ADDED: 'success', ADDED_ERROR: 'warning', SKIPPED: 'info', FIXED: 'primary', STILL_ERROR: 'danger', ROW_SKIPPED: 'warning' }[a] || 'info'
}

/** 数据源导入结果 action → 中文文案 */
function dsActionText(a) {
  return { ADDED: '新增', ADDED_ERROR: '新增(连不上)', SKIPPED: '已跳过', FIXED: '已修复', STILL_ERROR: '仍失败', ROW_SKIPPED: '行跳过' }[a] || a
}

/** 表导出明细小进度:PENDING 0% / RUNNING 50% 条纹动画 / DONE 100% 成功 / FAILED 100% 异常 */
function itemPercent(row) {
  if (row.status === 'DONE' || row.status === 'FAILED') return 100
  if (row.status === 'RUNNING') return 50
  return 0
}

function itemProgressStatus(row) {
  if (row.status === 'DONE') return 'success'
  if (row.status === 'FAILED') return 'exception'
  return ''
}

function isActive(s) {
  return s === 'PENDING' || s === 'RUNNING'
}

/** 需要继续轮询:列表里有未终态任务,或抽屉打开且该任务未终态 */
function needPolling() {
  return tasks.value.some((t) => isActive(t.status)) || (drawerVisible.value && detail.value && isActive(detail.value.status))
}

async function load() {
  loading.value = true
  try {
    tasks.value = await request.get('/sample-exports')
  } finally {
    loading.value = false
    if (needPolling() && !timer) startPolling()
  }
}

async function loadDetail() {
  if (!detailId.value) return
  try {
    detail.value = await request.get(`/sample-exports/${detailId.value}`)
  } catch { /* 详情刷新失败维持旧数据,拦截器已提示 */ }
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    tasks.value = await request.get('/sample-exports').catch(() => tasks.value)
    if (drawerVisible.value && detailId.value) await loadDetail()
    if (!needPolling()) stopPolling()
  }, 1000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 打开明细抽屉并立即拉详情;任务未终态时启动轮询跟进 */
async function openDetail(row) {
  detailId.value = row.id
  detail.value = null
  drawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await request.get(`/sample-exports/${row.id}`)
  } finally {
    detailLoading.value = false
    if (needPolling() && !timer) startPolling()
  }
}

function onFileChange(file) {
  const name = file.name || ''
  if (!name.toLowerCase().endsWith('.xlsx')) {
    ElMessage.warning('仅支持 .xlsx 文件')
    uploadRef.value?.clearFiles()
    uploadFile.value = null
    return
  }
  uploadFile.value = file.raw
}

/** 超出 limit 时替换为最新选择的文件 */
function onFileExceed(files) {
  uploadRef.value.clearFiles()
  uploadRef.value.handleStart(files[0])
}

function onFileRemove() {
  uploadFile.value = null
}

async function doUpload() {
  if (!uploadFile.value || uploading.value) return
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', uploadFile.value)
    const res = await request.post('/sample-exports', formData)
    ElMessage.success(`已提交导出任务 #${res.taskId}`)
    uploadDialogVisible.value = false
    await load()
  } finally {
    uploading.value = false
  }
}

/** 对话框完全关闭后重置选择状态,供下次打开 */
function onUploadClosed() {
  uploadFile.value = null
}

/** 下载 zip:直接走浏览器下载(GET 流式响应),与报告列表页同一写法 */
function download(row) {
  const a = document.createElement('a')
  a.href = `/api/sample-exports/${row.id}/download`
  a.click()
}

/** 打开产物目录:服务端调系统文件管理器 */
async function openDir(row) {
  await request.post(`/sample-exports/${row.id}/open-dir`)
  ElMessage.success('已打开产物目录')
}

// ---------- 暂停 / 恢复 / 批量删除 ----------

function onSelectionChange(rows) {
  selection.value = rows
}

/** 暂停运行中的任务:服务端在工作线程的下一检查点挂起 */
async function pauseTask(row) {
  await request.post(`/sample-exports/${row.id}/pause`)
  ElMessage.success(`任务 #${row.id} 已暂停,当前表处理完后停止`)
  await load()
}

/** 恢复暂停的任务 */
async function resumeTask(row) {
  await request.post(`/sample-exports/${row.id}/resume`)
  ElMessage.success(`任务 #${row.id} 已继续`)
  await load()
}

/** 批量删除勾选的任务:完成/失败直接删,暂停中=取消并删,运行/排队中由服务端跳过 */
async function confirmBatchDelete() {
  const ids = selection.value.map((t) => t.id)
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `将删除 ${ids.length} 个任务及其产物文件(暂停中的任务会被取消并删除;运行中/排队中的任务自动跳过)。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { /* 用户取消 */ return }
  const res = await request.post('/sample-exports-delete', { ids })
  const skipped = res.skipped || []
  if (skipped.length) {
    ElMessage.warning(`已删除 ${res.deleted.length} 个,跳过运行中/排队中的任务:${skipped.join('、')}`)
  } else {
    ElMessage.success(`已删除 ${res.deleted.length} 个任务`)
  }
  await load()
}

onMounted(load)

// 固定页签走 keep-alive:失活时停轮询,回来时刷新并按需恢复
onActivated(load)
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
/* 工具栏按钮组:flex + gap 统一间距,并清掉 el-button 相邻默认 margin(与表列表页同写法) */
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}

/* 创建时间/操作两列不换行(宽度已按内容留足,这里兜底防挤压换行) */
:deep(.nowrap-cell) {
  white-space: nowrap;
}

/* 说明/错误列:链接单行截断,点击看全文 */
.msg-link {
  font-size: 12px;
  max-width: 100%;
}

.msg-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.msg-context {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 8px;
}

.msg-content {
  margin: 0;
  max-height: 50vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: 13px;
}
</style>
