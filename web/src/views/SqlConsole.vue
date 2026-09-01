<template>
  <div class="page-card">
    <!-- 工具栏:左标题,右操作(数据源选择 / 历史 / 执行) -->
    <div class="toolbar">
      <h3 style="margin: 0">SQL 控制台</h3>
      <div class="toolbar-actions">
        <el-select v-model="selectedDsId" filterable placeholder="选择数据源" class="ds-select" :loading="dsLoading">
          <el-option v-for="ds in datasources" :key="ds.id" :value="ds.id" :label="ds.name">
            <div class="ds-option">
              <DbTypeIcon :type="ds.dbType" :size="14" />
              <span>{{ ds.name }}</span>
            </div>
          </el-option>
        </el-select>
        <el-button :icon="Clock" @click="historyVisible = true">历史</el-button>
        <!-- 未选数据源时禁用执行并提示:disabled 按钮不触发鼠标事件,需包一层 span 才能弹 tooltip -->
        <el-tooltip :disabled="!!selectedDsId" content="请先选择数据源" placement="bottom">
          <span>
            <el-button type="primary" :icon="CaretRight" :loading="executing" :disabled="!selectedDsId" @click="doExecute">执行</el-button>
          </span>
        </el-tooltip>
      </div>
    </div>

    <!-- SQL 编辑器(CodeMirror 6):行号 + 语法高亮;选中片段时仅执行选中部分(模拟 DataGrip) -->
    <div ref="editorHost" class="sql-editor" />

    <!-- 结果区:查询 → 动态列表格 + 本地分页;非查询 → 影响行数提示;失败 → 内联错误(拦截器已全局弹窗) -->
    <div class="result-area">
      <el-alert v-if="execError" type="error" :closable="false" show-icon :title="execError" />
      <template v-else-if="result">
        <template v-if="result.query">
          <div class="result-summary">共 {{ formatNumber(result.total) }} 行,耗时 {{ result.durationMs }} ms</div>
          <el-alert v-if="result.truncated" type="warning" :closable="false" show-icon
                    title="结果集过大,仅返回前 1000 行" style="margin-bottom: 12px" />
          <el-table :data="pagedRows" border size="small" style="width: 100%">
            <el-table-column type="index" label="#" width="50" :index="(resultPage - 1) * resultSize + 1" />
            <el-table-column v-for="(col, idx) in result.columns" :key="col + '_' + idx"
                             :label="col" :formatter="(row) => row[idx]" min-width="140" show-overflow-tooltip />
          </el-table>
          <div class="pagination-wrapper">
            <el-pagination v-model:current-page="resultPage" v-model:page-size="resultSize"
                           :page-sizes="[20, 50, 100, 200]" :total="result.rows.length"
                           layout="total, sizes, prev, pager, next" background />
          </div>
        </template>
        <el-alert v-else type="success" :closable="false" show-icon
                  :title="`执行成功,影响 ${result.updateCount} 行,耗时 ${result.durationMs} ms`" />
      </template>
      <el-empty v-else description="输入 SQL 后点击「执行」或按 Ctrl/Cmd+Enter;选中片段时仅执行选中部分" :image-size="60" />
    </div>

    <!-- 执行历史:点击回填编辑器 -->
    <el-drawer v-model="historyVisible" title="执行历史" size="440px">
      <div v-if="!history.length" style="color: var(--el-text-color-secondary); font-size: 13px">暂无执行历史</div>
      <div v-for="(item, i) in history" :key="i" class="history-item" @click="useHistory(item)">
        <div class="history-sql">{{ item.sql }}</div>
        <div class="history-time">{{ formatDateTime(item.time) }}</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * SQL 控制台:选数据源 → 写 SQL(可多行)→ 执行看结果。
 * 执行规则模拟 DataGrip:优先执行当前选中片段,无选中则执行全文;
 * 成功记入 localStorage 历史(最近 50 条,同 SQL 去重只留最新);失败由响应拦截器全局弹窗,
 * 同时把后端 message 内联展示在结果区,便于原地修改重试。
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { CaretRight, Clock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EditorView, keymap, lineNumbers, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState, Prec } from '@codemirror/state'
import { sql } from '@codemirror/lang-sql'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import api from '../api'
import DbTypeIcon from '../components/DbTypeIcon.vue'
import { formatDateTime, formatNumber } from '../utils/format'

// ---------- 数据源下拉 ----------
const datasources = ref([])
const dsLoading = ref(false)
const selectedDsId = ref('')

async function loadDatasources() {
  dsLoading.value = true
  try {
    datasources.value = await api.get('/datasources')
  } catch { /* 错误提示由响应拦截器统一弹出 */ } finally {
    dsLoading.value = false
  }
}

// ---------- CodeMirror 6 编辑器(多行 SQL) ----------
const editorHost = ref(null)
let view = null

// 高亮配色与 SqlInput.vue 同一套:全部引用 CSS 变量,跟随明暗主题
const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--el-color-primary)', fontWeight: '600' },
  { tag: tags.string, color: 'var(--el-color-success)' },
  { tag: tags.special(tags.string), color: 'var(--dq-sql-ident)' },
  { tag: tags.number, color: 'var(--el-color-warning)' },
  { tag: tags.bool, color: 'var(--el-color-warning)' },
  { tag: tags.null, color: 'var(--el-color-warning)', fontWeight: '600' },
  { tag: tags.typeName, color: 'var(--el-color-danger)' },
  { tag: [tags.operator, tags.punctuation], color: 'var(--el-text-color-regular)' },
  { tag: tags.name, color: 'var(--dq-sql-ident)' },
  { tag: [tags.lineComment, tags.blockComment], color: 'var(--el-text-color-placeholder)', fontStyle: 'italic' },
])

const theme = EditorView.theme({
  // 高度跟随内容纵向扩展,最低 240px
  '&': { backgroundColor: 'transparent', fontSize: '13px', height: 'auto', minHeight: '240px' },
  '.cm-content': {
    fontFamily: "'SF Mono', Menlo, Consolas, 'Courier New', monospace",
    caretColor: 'var(--el-text-color-primary)',
  },
  '.cm-scroller': { height: 'auto', overflowY: 'auto', lineHeight: '1.6' },
  '&.cm-focused': { outline: 'none' },
  // 行号槽与光标行:跟随 Element 填充色
  '.cm-gutters': {
    backgroundColor: 'var(--el-fill-color-light)',
    color: 'var(--el-text-color-placeholder)',
    border: 'none',
    borderRight: '1px solid var(--el-border-color-lighter)',
  },
  '.cm-activeLineGutter': { backgroundColor: 'var(--el-fill-color)' },
  '.cm-activeLine': { backgroundColor: 'var(--el-fill-color-light)' },
  '.cm-placeholder': { color: 'var(--el-text-color-placeholder)' },
  '.cm-cursor': { borderLeftColor: 'var(--el-text-color-primary)' },
})

onMounted(() => {
  loadDatasources()
  view = new EditorView({
    parent: editorHost.value,
    state: EditorState.create({
      extensions: [
        // 执行快捷键:Ctrl/Cmd+Enter,最高优先级保证不被其他 keymap 抢走
        Prec.highest(keymap.of([
          { key: 'Ctrl-Enter', run: () => (doExecute(), true) },
          { key: 'Cmd-Enter', run: () => (doExecute(), true) },
        ])),
        lineNumbers(),
        sql(),
        syntaxHighlighting(highlight),
        cmPlaceholder('输入 SQL,Ctrl/Cmd+Enter 执行;选中片段时仅执行选中部分'),
        theme,
      ],
    }),
  })
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

/** 取待执行 SQL:优先当前选中片段(模拟 DataGrip),无选中则取全文 */
function currentSql() {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to).trim()
  return selected || view.state.doc.toString().trim()
}

// ---------- 执行 ----------
const executing = ref(false)
// 后端响应:{ query, columns, rows, total, truncated, updateCount, durationMs }
const result = ref(null)
// 内联错误信息(拦截器已全局弹窗,这里再展示一次便于原地修改重试)
const execError = ref('')

// 本地分页:针对已返回的 rows 切片(后端单次最多返回 1000 行)
const resultPage = ref(1)
const resultSize = ref(20)
const pagedRows = computed(() => {
  const rows = result.value?.rows || []
  const start = (resultPage.value - 1) * resultSize.value
  return rows.slice(start, start + resultSize.value)
})

async function doExecute() {
  if (!selectedDsId.value || executing.value) return
  const sqlText = currentSql()
  if (!sqlText) {
    ElMessage.warning('请先输入 SQL')
    return
  }
  executing.value = true
  execError.value = ''
  result.value = null
  try {
    const res = await api.post(`/datasources/${selectedDsId.value}/sql/execute`, { sql: sqlText })
    result.value = res
    resultPage.value = 1
    pushHistory(sqlText)
  } catch (err) {
    // 不吞异常:提示已由响应拦截器统一弹出,这里只把后端 message 存下来内联展示
    execError.value = err?.response?.data?.message || err?.message || '执行失败'
  } finally {
    executing.value = false
  }
}

// ---------- 执行历史(localStorage,最近 50 条,同 SQL 去重只留最新一条) ----------
const HISTORY_KEY = 'dq-sql-console-history'
const HISTORY_MAX = 50
const history = ref(readHistory())
const historyVisible = ref(false)

function readHistory() {
  try {
    const list = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(list) ? list : []
  } catch { return [] }
}

function pushHistory(sqlText) {
  history.value = history.value.filter((h) => h.sql !== sqlText)
  history.value.unshift({ sql: sqlText, time: Date.now() })
  if (history.value.length > HISTORY_MAX) history.value.length = HISTORY_MAX
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value))
}

/** 点击历史条目:回填编辑器全文并关闭抽屉 */
function useHistory(item) {
  if (view) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: item.sql } })
  }
  historyVisible.value = false
}
</script>

<style scoped>
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ds-select {
  width: 260px;
}
.ds-option {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 编辑器外壳:边框/圆角/聚焦色对齐 el-input,--dq-sql-ident 供高亮配色引用(明暗两套) */
.sql-editor {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  overflow: hidden;
  --dq-sql-ident: #0e7490;
}
:global(html.dark) .sql-editor {
  --dq-sql-ident: #67e8f9;
}
.sql-editor:hover {
  border-color: var(--el-border-color-hover);
}
.sql-editor:focus-within {
  border-color: var(--el-color-primary);
}
.sql-editor :deep(.cm-editor) {
  background: transparent;
}
.result-area {
  margin-top: 14px;
}
.result-summary {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}
.history-item {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.history-item:hover {
  border-color: var(--el-color-primary);
}
.history-sql {
  font-family: 'SF Mono', Menlo, Consolas, 'Courier New', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--el-text-color-primary);
  /* 长 SQL 限高折叠,保持列表可扫读 */
  max-height: 72px;
  overflow: hidden;
}
.history-time {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
