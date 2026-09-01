<template>
  <div class="page-card diagnostics">
    <!-- 工具栏 -->
    <div class="diag-toolbar">
      <div class="diag-summary">
        <template v-if="report">
          <el-tag v-if="problemCount === 0" type="success">未发现异常</el-tag>
          <el-tag v-else type="danger">{{ problemCount }} 项需要关注</el-tag>
          <span class="diag-gen">生成于 {{ formatDateTime(report.generatedAt) }}(耗时 {{ report.durationMs }}ms)</span>
        </template>
      </div>
      <div class="diag-actions">
        <el-button :loading="loading" @click="loadOverview">刷新</el-button>
        <el-button type="primary" :disabled="!report" @click="exportReport">导出诊断报告</el-button>
      </div>
    </div>

    <div v-loading="loading" class="diag-body">
      <template v-if="report">
        <!-- 运行环境 -->
        <div class="section-title">运行环境</div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="软件版本">{{ report.env.appVersion }}</el-descriptions-item>
          <el-descriptions-item label="Java">{{ report.env.javaVersion }}</el-descriptions-item>
          <el-descriptions-item label="操作系统">{{ report.env.os }}</el-descriptions-item>
          <el-descriptions-item label="CPU 核数">{{ report.env.processors }}</el-descriptions-item>
          <el-descriptions-item label="堆内存">{{ formatBytes(report.env.jvmHeapUsedBytes) }} / {{ formatBytes(report.env.jvmHeapMaxBytes) }}</el-descriptions-item>
          <el-descriptions-item label="运行时长">{{ formatUptime(report.env.uptimeMs) }}</el-descriptions-item>
          <el-descriptions-item label="数据目录" :span="2">{{ report.env.dataDir }}</el-descriptions-item>
          <el-descriptions-item label="磁盘剩余">{{ formatBytes(report.env.diskFreeBytes) }}</el-descriptions-item>
          <el-descriptions-item label="H2 库大小" :span="2">{{ formatBytes(report.env.h2SizeBytes) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 最近扫描失败 -->
        <div class="section-title">最近扫描失败</div>
        <el-empty v-if="!report.scanFailures.length" description="无失败的扫描任务" :image-size="60" />
        <el-table v-else :data="report.scanFailures" border size="small" class="diag-table">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="failed-tables">
                <div v-for="t in row.failedTables" :key="t.tableName" class="failed-table-item">
                  <span class="failed-table-name">{{ t.tableName }}</span>
                  <span class="error-text">{{ t.error || '(无失败原因)' }}</span>
                </div>
                <div v-if="!row.failedTables.length" class="diag-note">无表级失败记录(任务级失败,见任务错误列)</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.finishedAt) }}</template>
          </el-table-column>
          <el-table-column prop="datasourceName" label="数据源" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.datasourceName || `数据源 ${row.datasourceId}` }}</template>
          </el-table-column>
          <el-table-column label="库" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.dbName ? `${row.dbName}.${row.schemaName}` : row.schemaName }}</template>
          </el-table-column>
          <el-table-column label="任务" width="80" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="goScan(row.jobId)">#{{ row.jobId }}</el-button>
            </template>
          </el-table-column>
          <el-table-column label="任务错误" min-width="200">
            <template #default="{ row }">
              <el-tooltip v-if="row.error" :content="row.error" placement="top" :show-after="200">
                <span class="error-text">{{ row.error }}</span>
              </el-tooltip>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 最近导出失败(仅 Word 数据调研报告异步导出任务;扫描 Excel 等导出是浏览器直接下载,无失败记录) -->
        <div class="section-title">
          最近导出失败
          <span class="diag-note">(Word 数据调研报告;扫描 Excel 导出等走浏览器直接下载,不产生失败记录)</span>
        </div>
        <el-empty v-if="!report.exportFailures.length" description="无失败的 Word 报告导出任务" :image-size="60" />
        <el-table v-else :data="report.exportFailures" border size="small" class="diag-table">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.finishedAt) }}</template>
          </el-table-column>
          <el-table-column label="任务" width="80" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="goReportExports">#{{ row.id }}</el-button>
            </template>
          </el-table-column>
          <el-table-column label="数据源" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.datasourceName || `数据源 ${row.datasourceId}` }}</template>
          </el-table-column>
          <el-table-column label="数据库" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.dbName || '-' }}</template>
          </el-table-column>
          <el-table-column label="库范围" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.schemaNames || '全部库' }}</template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="200">
            <template #default="{ row }">
              <span v-if="row.error" class="error-text clickable" @click="showDetail('失败原因', row.error)">{{ row.error }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 最近错误日志 -->
        <div class="section-title">最近错误日志</div>
        <el-empty v-if="!report.recentLogErrors.length" description="内存缓冲中暂无 WARN/ERROR 日志" :image-size="60" />
        <div v-else class="log-list">
          <div v-for="(e, i) in report.recentLogErrors" :key="i" class="log-item">
            <el-tag :type="e.level === 'ERROR' ? 'danger' : 'warning'" size="small" class="log-level">{{ e.level }}</el-tag>
            <span class="log-ts">{{ e.ts }}</span>
            <span class="log-logger">{{ shortLogger(e.logger) }}</span>
            <el-tooltip v-if="e.stackTrace" :content="e.stackTrace" placement="top" :show-after="200" popper-class="log-stack-pop">
              <span class="log-msg has-stack">{{ e.message }}</span>
            </el-tooltip>
            <span v-else class="log-msg">{{ e.message }}</span>
          </div>
        </div>

        <!-- 数据源连通性(靠后:添加数据源时已测过连通,仅切换网络环境后才需要复测) -->
        <div class="section-title">
          数据源连通性
          <span v-if="dsResults" class="diag-note">({{ dsResults.filter(r => r.success).length }}/{{ dsResults.length }} 正常)</span>
        </div>
        <div v-if="!dsResults" v-loading="checking" class="ds-check-placeholder">
          <el-button type="primary" plain :loading="checking" @click="checkDatasources">开始检测</el-button>
          <span class="diag-note">逐个实测所有数据源连接(含 SSH 隧道),数据源较多时耗时较长</span>
        </div>
        <el-table v-else-if="dsResults.length" v-loading="checking" :data="dsResults" border size="small" class="diag-table">
          <el-table-column prop="name" label="数据源" min-width="120" show-overflow-tooltip />
          <el-table-column prop="dbType" label="类型" width="110" />
          <el-table-column prop="jdbcUrl" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="SSH" width="70" align="center">
            <template #default="{ row }">{{ row.sshEnabled ? '隧道' : '-' }}</template>
          </el-table-column>
          <el-table-column label="结果" min-width="180">
            <template #default="{ row }">
              <span v-if="!row.success && row.error" class="error-text clickable" @click="showDetail(`连接失败:${row.name || `数据源 ${row.id}`}`, row.error)">✗ {{ row.error }}</span>
              <span v-else-if="row.success" class="ok-text">✓ 连接正常{{ modeSuffix(row) }}</span>
              <span v-else class="error-text">✗ 连接失败</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90" align="right">
            <template #default="{ row }">{{ row.durationMs }}ms</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无数据源" :image-size="60" />

        <!-- AI 配置(放最后:仅配置状态提示,低频查看) -->
        <div class="section-title">AI 配置</div>
        <div class="diag-line">
          <el-tag v-if="report.aiConfigured" type="success" size="small">已配置</el-tag>
          <template v-else>
            <el-tag type="info" size="small">未配置</el-tag>
            <span class="diag-note">AI 表说明/自动打标不可用;如需使用请到「系统设置 → AI 配置」填写并测试连通性</span>
          </template>
        </div>
      </template>
    </div>

    <!-- 错误详情弹窗:表格里截断的失败原因/连接错误,点击查看完整内容 -->
    <el-dialog v-model="detail.visible" :title="detail.title" width="640px">
      <pre class="detail-content">{{ detail.content }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api'
import { formatBytes, formatDateTime } from '../utils/format'
import { downloadText } from '../utils/download'

const router = useRouter()
const loading = ref(false)
const checking = ref(false)
const report = ref(null)
/** 数据源实测结果:未检测为 null(与「检测了但没有数据源」的空数组区分) */
const dsResults = ref(null)

/** 错误详情弹窗状态 */
const detail = ref({ visible: false, title: '', content: '' })

function showDetail(title, content) {
  detail.value = { visible: true, title, content }
}

/** 连通性成功行的后缀:SQL Server 探测的是版本,其余方言(金仓)是兼容模式 */
function modeSuffix(row) {
  if (!row.dbMode) return ''
  return row.dbType === 'SQLSERVER' ? `(SQL Server ${row.dbMode})` : `(兼容模式 ${row.dbMode})`
}

/** 需要关注的项数:AI 未配置 + 扫描/导出失败 + 日志中有 ERROR + 数据源实测失败 */
const problemCount = computed(() => {
  if (!report.value) return 0
  const r = report.value
  let n = 0
  if (!r.aiConfigured) n++
  n += r.scanFailures.length + r.exportFailures.length
  n += (r.recentLogErrors || []).filter((e) => e.level === 'ERROR').length
  n += (dsResults.value || []).filter((d) => !d.success).length
  return n
})

async function loadOverview() {
  loading.value = true
  try {
    report.value = await request.get('/diagnostics')
  } finally {
    loading.value = false
  }
}

async function checkDatasources() {
  checking.value = true
  try {
    // 实测逐个进行且单条 30s 超时兜底,整体可能超过默认 30s 请求超时,这里放宽到 60s
    dsResults.value = await request.post('/diagnostics/check-datasources', null, { timeout: 60000 })
  } finally {
    checking.value = false
  }
}

function goScan(jobId) {
  router.push(`/scans/${jobId}`)
}

function goReportExports() {
  router.push('/report-exports')
}

function shortLogger(logger) {
  if (!logger) return ''
  const parts = logger.split('.')
  return parts.length > 2 ? parts.slice(-2).join('.') : logger
}

function formatUptime(ms) {
  if (ms == null) return '-'
  const d = Math.floor(ms / 86400000)
  const h = Math.floor((ms % 86400000) / 3600000)
  const m = Math.floor((ms % 3600000) / 60000)
  return d > 0 ? `${d} 天 ${h} 小时` : h > 0 ? `${h} 小时 ${m} 分` : `${m} 分钟`
}

/** 组装 Markdown 诊断报告(可发给运维/开发排错);数据源未实测时标注「未检测」 */
function buildMarkdown() {
  const r = report.value
  const lines = ['# dq-tool 诊断报告', '', `- 生成时间:${formatDateTime(r.generatedAt)}`, '']
  lines.push('## 运行环境', '')
  lines.push(`- 软件版本:${r.env.appVersion}`)
  lines.push(`- Java:${r.env.javaVersion}`)
  lines.push(`- 操作系统:${r.env.os}`)
  lines.push(`- CPU 核数:${r.env.processors}`)
  lines.push(`- 堆内存:${formatBytes(r.env.jvmHeapUsedBytes)} / ${formatBytes(r.env.jvmHeapMaxBytes)}`)
  lines.push(`- 运行时长:${formatUptime(r.env.uptimeMs)}`)
  lines.push(`- 数据目录:${r.env.dataDir}`)
  lines.push(`- 磁盘剩余:${formatBytes(r.env.diskFreeBytes)}`)
  lines.push(`- H2 库大小:${formatBytes(r.env.h2SizeBytes)}`)
  lines.push('', '## AI 配置', '')
  lines.push(r.aiConfigured ? '- 已配置' : '- 未配置')
  lines.push('', '## 数据源连通性', '')
  if (!dsResults.value) {
    lines.push('(未检测)')
  } else if (!dsResults.value.length) {
    lines.push('(无数据源)')
  } else {
    for (const d of dsResults.value) {
      lines.push(`- [${d.success ? '正常' : '失败'}] ${d.name || `数据源 ${d.id}`}(${d.dbType})${d.sshEnabled ? ' [SSH 隧道]' : ''} ${d.jdbcUrl}`)
      if (d.success && d.dbMode) lines.push(`  - ${d.dbType === 'SQLSERVER' ? '版本' : '兼容模式'}:${d.dbMode}`)
      if (!d.success) lines.push(`  - 错误:${d.error || '连接失败'}`)
    }
  }
  lines.push('', '## 最近扫描失败', '')
  if (!r.scanFailures.length) lines.push('(无)')
  for (const f of r.scanFailures) {
    const schema = f.dbName ? `${f.dbName}.${f.schemaName}` : f.schemaName
    lines.push(`- 任务 #${f.jobId} ${f.datasourceName || `数据源 ${f.datasourceId}`} / ${schema}(${formatDateTime(f.finishedAt)})`)
    if (f.error) lines.push(`  - 任务错误:${f.error}`)
    for (const t of f.failedTables) lines.push(`  - 表 ${t.tableName}:${t.error || '(无失败原因)'}`)
  }
  lines.push('', '## 最近导出失败(Word 数据调研报告)', '')
  if (!r.exportFailures.length) lines.push('(无)')
  for (const f of r.exportFailures) {
    const scope = f.schemaNames || '全部库'
    lines.push(`- 任务 #${f.id} ${f.datasourceName || `数据源 ${f.datasourceId}`} / ${f.dbName || '-'} / ${scope}(${formatDateTime(f.finishedAt)}):${f.error || '-'}`)
  }
  lines.push('', '## 最近错误日志', '')
  if (!r.recentLogErrors.length) lines.push('(无)')
  else for (const e of r.recentLogErrors) {
    lines.push(`- [${e.level}] ${e.ts} ${e.logger}: ${e.message}`)
    if (e.stackTrace) lines.push('', '```', e.stackTrace.trim(), '```', '')
  }
  return lines.join('\n')
}

function exportReport() {
  const ts = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const name = `dq-diagnostic-${ts.getFullYear()}${pad(ts.getMonth() + 1)}${pad(ts.getDate())}-${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}.md`
  downloadText(name, buildMarkdown())
}

onActivated(loadOverview)
</script>

<style scoped>
.diag-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.diag-summary {
  display: flex;
  align-items: center;
  gap: 10px;
}
.diag-gen {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.diag-actions {
  display: flex;
  gap: 8px;
}
.section-title {
  font-weight: 600;
  margin: 18px 0 8px;
}
.section-title:first-child {
  margin-top: 0;
}
.diag-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.diag-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: normal;
}
.diag-table {
  width: 100%;
}
.ds-check-placeholder {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}
.error-text {
  color: var(--el-color-danger);
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.clickable {
  cursor: pointer;
  text-decoration: underline dotted;
}
.detail-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  max-height: 50vh;
  overflow: auto;
}
.ok-text {
  color: var(--el-color-success);
}
.failed-tables {
  padding: 4px 12px;
}
.failed-table-item {
  display: flex;
  gap: 12px;
  padding: 2px 0;
  font-size: 12px;
}
.failed-table-name {
  font-family: monospace;
  min-width: 240px;
}
.log-list {
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 6px 10px;
  max-height: 360px;
  overflow: auto;
}
.log-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.log-item:last-child {
  border-bottom: none;
}
.log-level {
  flex-shrink: 0;
}
.log-ts {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.log-logger {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.log-msg {
  word-break: break-all;
}
.has-stack {
  cursor: help;
  text-decoration: underline dotted;
}
</style>
