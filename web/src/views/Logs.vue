<template>
  <div class="page-card logs-page">
    <div class="toolbar">
      <h3 style="margin: 0">运行日志</h3>
      <div style="display: flex; gap: 8px; align-items: center">
        <el-tag :type="connected ? 'success' : 'danger'" size="small" effect="plain">
          {{ connected ? '已连接' : '未连接' }}
        </el-tag>
        <el-button v-if="!connected" type="primary" size="small" @click="connect">连接</el-button>
        <el-button v-else size="small" @click="disconnect">断开</el-button>
      </div>
    </div>

    <div style="display: flex; gap: 12px; margin-bottom: 12px; align-items: center; flex-wrap: wrap">
      <el-select v-model="levelFilter" placeholder="日志级别" clearable style="width: 120px">
        <el-option label="ERROR" value="ERROR" />
        <el-option label="WARN" value="WARN" />
        <el-option label="INFO" value="INFO" />
        <el-option label="DEBUG" value="DEBUG" />
        <el-option label="TRACE" value="TRACE" />
      </el-select>
      <el-input v-model="keyword" placeholder="搜索日志内容" clearable style="width: 260px" />
      <el-checkbox v-model="autoScroll">自动滚动到底部</el-checkbox>
      <el-button size="small" @click="clearLogs">清空</el-button>
      <span style="color: var(--el-text-color-secondary); font-size: 12px; margin-left: auto">
        共 {{ logs.length }} 条{{ filteredLogs.length !== logs.length ? `，筛选后 ${filteredLogs.length} 条` : '' }}
      </span>
    </div>

    <div ref="logContainer" class="log-container">
      <div v-if="filteredLogs.length === 0" class="log-empty">
        <span v-if="!connected">未连接，点击「连接」开始查看实时日志</span>
        <span v-else>暂无匹配的日志</span>
      </div>
      <div
        v-for="(log, i) in filteredLogs"
        :key="i"
        class="log-line"
        :class="'log-level-' + log.level.toLowerCase()"
      >
        <span class="log-ts">{{ formatTs(log.timestamp) }}</span>
        <span class="log-level" :class="'level-badge-' + log.level.toLowerCase()">{{ log.level }}</span>
        <span class="log-thread" :title="log.thread">[{{ log.thread }}]</span>
        <span class="log-logger" :title="log.logger">{{ shortLogger(log.logger) }}</span>
        <span class="log-msg">{{ log.message }}</span>
        <pre v-if="log.stackTrace" class="log-stack">{{ log.stackTrace }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onActivated, onDeactivated, nextTick, watch } from 'vue'

const logs = ref([])
const connected = ref(false)
const levelFilter = ref('')
const keyword = ref('')
const autoScroll = ref(true)
const logContainer = ref(null)
let eventSource = null

/** 日志级别权重，用于级别过滤(ERROR 显示 ERROR，WARN 显示 WARN+ERROR ...) */
const LEVEL_WEIGHT = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4 }

const filteredLogs = computed(() => {
  const minWeight = levelFilter.value ? LEVEL_WEIGHT[levelFilter.value] : -1
  const kw = keyword.value.trim().toLowerCase()
  return logs.value.filter((log) => {
    if (LEVEL_WEIGHT[log.level] < minWeight) return false
    if (kw) {
      const haystack = (log.message + ' ' + log.logger + ' ' + (log.stackTrace || '')).toLowerCase()
      if (!haystack.includes(kw)) return false
    }
    return true
  })
})

/** 格式化时间戳：ISO 8601 -> HH:mm:ss.SSS */
function formatTs(ts) {
  if (!ts) return ''
  // 提取时间部分(兼容 2024-01-01T12:00:00.000+08:00)
  const m = ts.match(/T(\d{2}:\d{2}:\d{2}\.\d{3})/)
  return m ? m[1] : ts
}

/** 缩短 logger 名：取最后两段 */
function shortLogger(logger) {
  if (!logger) return ''
  const parts = logger.split('.')
  if (parts.length <= 2) return logger
  return '...' + parts.slice(-2).join('.')
}

function scrollToBottom() {
  if (autoScroll.value && logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

function connect() {
  if (eventSource) eventSource.close()
  logs.value = []
  eventSource = new EventSource('/api/logs/stream')
  connected.value = true

  eventSource.onmessage = (ev) => {
    connected.value = true
    try {
      const entry = JSON.parse(ev.data)
      logs.value.push(entry)
      // 限制前端最多保留 2000 条，避免内存溢出
      if (logs.value.length > 2000) {
        logs.value.splice(0, logs.value.length - 2000)
      }
      nextTick(scrollToBottom)
    } catch { /* 忽略解析失败 */ }
  }

  eventSource.onerror = () => {
    // EventSource 会自动重连；仅更新连接状态，重连成功后 onmessage 会恢复
    connected.value = false
  }
}

function disconnect() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  connected.value = false
}

function clearLogs() {
  logs.value = []
}

watch(autoScroll, () => {
  if (autoScroll.value) nextTick(scrollToBottom)
})

onActivated(() => {
  // 页面重新激活时自动连接
  if (!eventSource) connect()
})

onDeactivated(() => {
  // 页面失活时断开，避免后台消耗连接
  disconnect()
})
</script>

<style scoped>
.logs-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 48px - 40px);
}

.log-container {
  flex: 1;
  overflow-y: auto;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 0;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', 'Courier New', monospace;
  font-size: 12.5px;
  line-height: 1.6;
}

.log-empty {
  text-align: center;
  color: var(--el-text-color-secondary);
  padding: 40px 0;
}

.log-line {
  padding: 1px 12px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  white-space: nowrap;
  overflow: hidden;
}

.log-line:hover {
  background: var(--el-fill-color-light);
}

.log-ts {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.log-level {
  flex-shrink: 0;
  width: 44px;
  text-align: center;
  font-weight: 600;
  border-radius: 3px;
  font-size: 11px;
  padding: 0 2px;
}

.level-badge-error { color: #fff; background: var(--el-color-danger); }
.level-badge-warn  { color: #fff; background: var(--el-color-warning); }
.level-badge-info   { color: var(--el-color-primary); }
.level-badge-debug  { color: var(--el-text-color-secondary); }
.level-badge-trace  { color: var(--el-text-color-placeholder); }

.log-thread {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.log-logger {
  color: var(--el-color-primary);
  flex-shrink: 0;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.log-msg {
  color: var(--el-text-color-primary);
  white-space: pre-wrap;
  word-break: break-all;
  flex: 1;
  min-width: 0;
}

.log-stack {
  margin: 2px 0 0 0;
  padding: 0;
  color: var(--el-color-danger);
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: inherit;
}

/* ERROR 级别整行强调 */
.log-level-error .log-msg {
  color: var(--el-color-danger);
}
.log-level-warn .log-msg {
  color: var(--el-color-warning);
}
</style>
