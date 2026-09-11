<template>
  <div class="page-card sql-console-page">
    <!-- 工具栏:左标题,右操作(数据源选择 / 库选择 / 历史 / 执行) -->
    <div class="toolbar">
      <h3 style="margin: 0">SQL 控制台</h3>
      <div class="toolbar-actions">
        <el-select v-model="selectedDsId" filterable placeholder="选择数据源" class="ds-select" :loading="dsLoading">
          <el-option v-for="ds in dsOptions" :key="ds.id" :value="ds.id" :label="ds.name">
            <div class="ds-option">
              <DbTypeIcon :type="ds.dbType" :size="14" />
              <span>{{ ds.name }}</span>
            </div>
          </el-option>
        </el-select>
        <!-- 库选择:多库方言(SQL Server/Kingbase)列数据库,其余列 schema;空=数据源默认库 -->
        <el-select v-model="selectedSchema" filterable clearable placeholder="默认库" class="schema-select"
                   :loading="schemaLoading" :disabled="!selectedDsId">
          <el-option v-for="s in schemas" :key="s" :value="s" :label="s" />
        </el-select>
        <el-tooltip content="从源库刷新表/字段提示(覆盖本地缓存)" placement="bottom">
          <span>
            <!-- 本地 H2 库是应用自身配置库,无源库/缓存概念,刷新无意义故禁用 -->
            <el-button :icon="Refresh" :loading="metaRefreshing" :disabled="!selectedDsId || isLocalH2" @click="refreshMeta" />
          </span>
        </el-tooltip>
        <el-button :icon="Clock" @click="historyVisible = true">历史</el-button>
        <!-- 未选数据源时禁用执行并提示:disabled 按钮不触发鼠标事件,需包一层 span 才能弹 tooltip -->
        <el-tooltip :disabled="!!selectedDsId" content="请先选择数据源" placement="bottom">
          <span>
            <el-button type="primary" :icon="CaretRight" :loading="executing" :disabled="!selectedDsId" @click="doExecute">执行</el-button>
          </span>
        </el-tooltip>
      </div>
    </div>

    <!-- SQL 编辑器(CodeMirror 6):行号 + 语法高亮;选中片段仅执行选中部分,无选中执行光标所在语句(模拟 DataGrip) -->
    <div ref="editorHost" class="sql-editor" />
    <!-- 执行目标提示:跟随光标/选中实时刷新 -->
    <div v-if="execHint" class="exec-hint">{{ execHint }}</div>

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
                             :label="col" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <!-- 制表符按 tab-size 展开成对齐空位(同 DataGrip);其余控制字符转义为可见符号 -->
                <span class="cell-text"><template v-for="(seg, i) in cellSegments(row[idx])" :key="i"><span v-if="seg.ctrl" class="ctrl-char">{{ seg.text }}</span><template v-else>{{ seg.text }}</template></template></span>
              </template>
            </el-table-column>
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
      <el-empty v-else description="可写多条 SQL 用分号隔开,点击「执行」或按 Ctrl/Cmd+Enter 运行光标所在语句;选中片段时仅执行选中部分" :image-size="60" />
    </div>

    <!-- 执行历史:点击回填编辑器并自动切回该条执行时的数据源和库 -->
    <el-drawer v-model="historyVisible" title="执行历史" size="440px">
      <div v-if="!history.length" style="color: var(--el-text-color-secondary); font-size: 13px">暂无执行历史</div>
      <div v-for="(item, i) in history" :key="i" class="history-item" @click="useHistory(item)">
        <div class="history-sql">{{ item.sql }}</div>
        <div class="history-time">
          {{ formatDateTime(item.time) }}
          <span v-if="item.dsId" class="history-ds">· {{ dsNameOf(item.dsId) }}<template v-if="item.schema"> / {{ item.schema }}</template></span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * SQL 控制台:选数据源(可再选目标库,空=数据源默认库)→ 写 SQL(可多条,分号隔开)→ 执行看结果。
 * 执行规则模拟 DataGrip:优先执行当前选中片段,无选中执行光标所在语句(按顶层分号切分,空行隔开不算);
 * 编辑器下方实时提示当前将执行的语句。
 * 智能提示:选定数据源+库后自动拉取表清单与整库字段清单,按 数据源|库 覆盖更新到 localStorage,
 * 表(「表」徽标)/字段(「列」徽标)/关键字(「词」徽标)一眼区分。
 * 补全元数据三级获取:浏览器 localStorage(秒出)→ 服务端 H2 缓存 → 源库;工具栏刷新按钮强制从源库
 * 拉取并覆盖 H2 与 localStorage;表数超 100 时字段按 50 表/批分批拉取(防整库一次拉超时)并渐进补齐补全项。
 * 成功记入 localStorage 历史(最近 50 条,按 SQL+数据源+库 去重只留最新;条目带 dsId/schema,
 * 点击回填时自动切回原数据源和库);失败由响应拦截器全局弹窗,
 * 同时把后端 message 内联展示在结果区,便于原地修改重试。
 * 结果单元格里的制表符按 8 列 tab 位展开成对齐空位(同 DataGrip),换行/回车/其余控制码
 * 转义为可见符号(\n \r \xNN)加底色展示,避免控制字符在表格里塌陷看不出来。
 * 按住 Cmd/Ctrl 悬停在语句内 FROM/JOIN/INTO/UPDATE 等表位关键字后的表名上时出现下划线,
 * 点击跳转到该表的字段明细页(限定名/引号包裹均可识别,schema 从已加载表清单反查)。
 * 数据源下拉首项「本地 H2 库(只读)」是应用自身配置库:走 /sql-console/local-h2/* 只读接口,
 * 后端做语句级只读校验(写语句 400),补全元数据直接读本地库(无缓存/refresh 概念),不支持表名跳转。
 */
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { CaretRight, Clock, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from '../utils/notify'
import { EditorView, keymap, lineNumbers, placeholder as cmPlaceholder, tooltips, Decoration } from '@codemirror/view'
import { EditorState, Prec, StateEffect, StateField } from '@codemirror/state'
import { sql, StandardSQL } from '@codemirror/lang-sql'
import { autocompletion } from '@codemirror/autocomplete'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import api from '../api'
import DbTypeIcon from '../components/DbTypeIcon.vue'
import { formatDateTime, formatNumber } from '../utils/format'
import { splitStatements, statementAt } from '../utils/sqlStatements'

// ---------- 数据源下拉 ----------
const datasources = ref([])
const dsLoading = ref(false)
const selectedDsId = ref('')

/**
 * 本地 H2 库(应用自身配置库)伪数据源:只出现在 SQL 控制台下拉,不进数据源管理页,
 * 后端走独立的 /api/sql-console/local-h2/* 路由,只读(写语句直接 400)。
 */
const LOCAL_H2_ID = '__local_h2__'
const LOCAL_H2_OPTION = { id: LOCAL_H2_ID, name: '本地 H2 库(只读)', dbType: 'H2' }
/** 下拉选项 = 本地 H2 库 + 业务数据源 */
const dsOptions = computed(() => [LOCAL_H2_OPTION, ...datasources.value])
const isLocalH2 = computed(() => selectedDsId.value === LOCAL_H2_ID)

async function loadDatasources() {
  dsLoading.value = true
  try {
    datasources.value = await api.get('/datasources')
  } catch { /* 错误提示由响应拦截器统一弹出 */ } finally {
    dsLoading.value = false
  }
}

// ---------- 库下拉:多库方言(SQL Server/Kingbase)列数据库,其余列 schema;空=数据源默认库 ----------
const schemas = ref([])
const schemaLoading = ref(false)
const selectedSchema = ref('')

// 切换数据源后重拉库清单并清空已选;列表受该数据源的库过滤白名单约束(与库列表页同口径)。
// schemasReady 记录当前加载 promise,供历史回填时等库清单就绪后再选库(防被这里的清空逻辑冲掉)
let schemasReady = Promise.resolve()
watch(selectedDsId, (id) => {
  selectedSchema.value = ''
  schemas.value = []
  if (!id) {
    tableOptions.value = [...KEYWORD_OPTIONS]
    return
  }
  schemasReady = (async () => {
    // 本地 H2 库:库下拉列的是本地库自身的 schema(PUBLIC / INFORMATION_SCHEMA 等)
    if (id === LOCAL_H2_ID) {
      schemaLoading.value = true
      try {
        schemas.value = await api.get('/sql-console/local-h2/schemas').catch(() => [])
      } finally {
        schemaLoading.value = false
      }
      await loadTableCompletions()
      return
    }
    const ds = datasources.value.find((d) => d.id === id)
    schemaLoading.value = true
    try {
      const url = ['SQLSERVER', 'KINGBASE'].includes(ds?.dbType)
        ? `/datasources/${id}/databases`
        : `/datasources/${id}/schemas`
      schemas.value = await api.get(url).catch(() => [])
    } finally {
      schemaLoading.value = false
    }
    await loadTableCompletions()
  })()
})

// ---------- 表/字段智能提示(类 DataGrip):跟随数据源+库下拉动态加载,补全源实时读取 ----------
// 补全项顺序:表(class 图标,表注释 detail)→ 字段(property 图标,「类型 · 归属表」detail)→ SQL 关键字
const tableOptions = ref([])
// 关键字取自 lang-sql 方言的词表(6.x 为 dialect.words 的键集合,含 true/false/null 字面量)
const KEYWORD_OPTIONS = Object.keys(StandardSQL.dialect.words || {})
  .map((k) => ({ label: k.toUpperCase(), type: 'keyword' }))
// 未选库时跨库枚举的 schema 数上限:防无白名单的大实例一次发出过多请求
const COMPLETION_MAX_SCHEMAS = 20

// 表/字段补全 localStorage 缓存:按 数据源|库 覆盖更新;再次选中时先上缓存秒出提示,后台拉新后覆盖
const META_CACHE_KEY = 'dq-sql-console-meta'
const META_CACHE_MAX = 10
// 字段清单分批拉取:表数超过阈值才分批(防整库一次拉超时),每批表数
const COLUMN_BATCH_THRESHOLD = 100
const COLUMN_BATCH_SIZE = 50

function readMetaCache() {
  try {
    const obj = JSON.parse(localStorage.getItem(META_CACHE_KEY) || '{}')
    return obj && typeof obj === 'object' ? obj : {}
  } catch { return {} }
}

function writeMetaCache(key, options) {
  const cache = readMetaCache()
  cache[key] = { options, time: Date.now() }
  // 超出上限按时间淘汰最旧,防 localStorage 膨胀
  const keys = Object.keys(cache)
  if (keys.length > META_CACHE_MAX) {
    keys.sort((a, b) => cache[a].time - cache[b].time)
      .slice(0, keys.length - META_CACHE_MAX)
      .forEach((k) => delete cache[k])
  }
  try { localStorage.setItem(META_CACHE_KEY, JSON.stringify(cache)) } catch { /* 容量满等场景静默放弃 */ }
}

/** 补全源:取光标前标识符 token(含 . 支持 schema.table 限定名),前缀匹配表名/字段名/关键字 */
function completionSource(context) {
  const word = context.matchBefore(/[\w$.一-龥]*$/)
  if (!word || (word.from === word.to && !context.explicit)) return null
  return { from: word.from, options: tableOptions.value, validFor: /^[\w$.一-龥]*$/ }
}

/** 拼字段/表清单查询串:db 过滤 + refresh 强制回源 */
function metaQuery(db, refresh) {
  const params = []
  if (db) params.push(`db=${encodeURIComponent(db)}`)
  if (refresh) params.push('refresh=true')
  return params.length ? `?${params.join('&')}` : ''
}

/** 拉一个 schema 的表清单(原始行 [{name, comment}]);失败按空处理,不阻塞其它库 */
async function fetchTables(dsId, schema, db, refresh = false) {
  const list = await api.get(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables${metaQuery(db, refresh)}`).catch(() => [])
  // TableStat.name 服务端可空,滤掉防空标签混进补全列表触发 CM 内部异常
  return (list || []).filter((t) => t && t.name)
    .map((t) => ({ name: t.name, comment: t.comment || undefined }))
}

/** 拉一个 schema 的整库字段清单(原始行 [{table, name, type, comment}]);失败按空处理 */
async function fetchColumns(dsId, schema, db, refresh = false) {
  const list = await api.get(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/columns${metaQuery(db, refresh)}`).catch(() => [])
  return (list || []).filter((c) => c && c.name)
    .map((c) => ({ table: c.table, name: c.name, type: c.type, comment: c.comment || '' }))
}

/** 拉一个 schema 的字段清单:表数超阈值时按 tables=a&tables=b 重复参数分批拉取(防整库一次拉超时),
    每批返回后回调 onBatch(累计字段)供渐进刷新补全;单批失败不阻塞后续批 */
async function fetchColumnsBatched(dsId, schema, db, tableNames, refresh, onBatch) {
  if (!tableNames.length) return []
  // 表数不超阈值时退化为整库一次性拉取
  if (tableNames.length <= COLUMN_BATCH_THRESHOLD) return fetchColumns(dsId, schema, db, refresh)
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/columns`
  const prefix = metaQuery(db, refresh) ? `${metaQuery(db, refresh)}&` : '?'
  const acc = []
  for (let i = 0; i < tableNames.length; i += COLUMN_BATCH_SIZE) {
    const batch = tableNames.slice(i, i + COLUMN_BATCH_SIZE)
    const q = batch.map((t) => `tables=${encodeURIComponent(t)}`).join('&')
    const list = await api.get(`${base}${prefix}${q}`).catch(() => [])
    acc.push(...(list || []).filter((c) => c && c.name)
      .map((c) => ({ table: c.table, name: c.name, type: c.type, comment: c.comment || '' })))
    onBatch?.(acc)
  }
  return acc
}

/** 表行 → 补全项(class=表图标,表注释作 detail) */
function toTableOption(label, comment) {
  return { label, type: 'class', detail: comment }
}

/** 拉本地 H2 库某 schema 的表/视图清单(应用自身库,结构量小:不分批、无服务端缓存、无 refresh);失败按空处理 */
async function fetchLocalTables(schema) {
  const q = schema ? `?schema=${encodeURIComponent(schema)}` : ''
  const list = await api.get(`/sql-console/local-h2/tables${q}`).catch(() => [])
  return (list || []).filter((t) => t && t.name).map((t) => toTableOption(t.name, t.comment || undefined))
}

/** 拉本地 H2 库某 schema 的整库字段清单;失败按空处理 */
async function fetchLocalColumns(schema) {
  const q = schema ? `?schema=${encodeURIComponent(schema)}` : ''
  const list = await api.get(`/sql-console/local-h2/columns${q}`).catch(() => [])
  return (list || []).filter((c) => c && c.name).map((c) => toColumnOption(c))
}

/** 字段行 → 补全项(property=列图标,detail 为「类型 · 归属表 · 备注」,跨表同名字段靠它区分) */
function toColumnOption(col, tableLabel) {
  const detail = [col.type, tableLabel || col.table, col.comment].filter(Boolean).join(' · ')
  return { label: col.name, type: 'property', detail: detail || undefined }
}

/** 按当前数据源+库下拉装配补全项:选中库拉表+字段;非多库方言未选库时只跨库枚举表;多库方言未选库只补关键字。
    refresh=true 时强制从源库拉取(透传到接口),仍先上 localStorage 缓存秒出,拉取完成后覆盖。
    本地 H2 库直接读本地库结构(应用自身配置库,无源库/缓存/refresh 概念)。 */
async function loadTableCompletions(refresh = false) {
  const id = selectedDsId.value
  if (!id) {
    tableOptions.value = [...KEYWORD_OPTIONS]
    return
  }
  const atStart = { id, schema: selectedSchema.value }
  // 异步拉取期间用户又切换了数据源/库时丢弃过期结果,防止旧补全盖掉新的
  const stale = () => atStart.id !== selectedDsId.value || atStart.schema !== selectedSchema.value
  // 本地 H2 库:结构量小,一次性拉表与字段(先上表名,字段到了再补齐),不走 localStorage/服务端缓存
  if (id === LOCAL_H2_ID) {
    const ts = await fetchLocalTables(atStart.schema)
    if (stale()) return
    tableOptions.value = [...ts, ...KEYWORD_OPTIONS]
    const cs = await fetchLocalColumns(atStart.schema)
    if (stale()) return
    tableOptions.value = [...ts, ...cs, ...KEYWORD_OPTIONS]
    return
  }
  // 先上 localStorage 缓存秒出提示,后台拉新完成后覆盖
  const cacheKey = `${id}|${atStart.schema}`
  const cached = readMetaCache()[cacheKey]
  if (cached?.options?.length) {
    tableOptions.value = [...cached.options, ...KEYWORD_OPTIONS]
  }
  const ds = datasources.value.find((d) => d.id === id)
  const multiDb = ['SQLSERVER', 'KINGBASE'].includes(ds?.dbType)
  let tables = []
  let columns = []
  if (multiDb) {
    // 多库方言:选中数据库后按 库→schema 两级枚举,表名用 schema.table 限定名,字段归属标 schema.table
    if (atStart.schema) {
      const db = atStart.schema
      const names = await api.get(`/datasources/${id}/schemas?db=${encodeURIComponent(db)}`).catch(() => [])
      const limited = (names || []).slice(0, COMPLETION_MAX_SCHEMAS)
      // 每 schema 先表后字段:表清单先到先上,字段分批渐进补齐
      const perTables = new Map()
      const perColumns = new Map()
      const applyProgress = () => {
        if (stale()) return
        tableOptions.value = [[...perTables.values()].flat(), [...perColumns.values()].flat(), KEYWORD_OPTIONS].flat()
      }
      await Promise.all(limited.map(async (s) => {
        const ts = await fetchTables(id, s, db, refresh)
        perTables.set(s, ts.map((t) => toTableOption(`${s}.${t.name}`, t.comment)))
        applyProgress()
        const cs = await fetchColumnsBatched(id, s, db, ts.map((t) => t.name), refresh, (acc) => {
          perColumns.set(s, acc.map((c) => toColumnOption(c, `${s}.${c.table}`)))
          applyProgress()
        })
        perColumns.set(s, cs.map((c) => toColumnOption(c, `${s}.${c.table}`)))
      }))
      tables = [...perTables.values()].flat()
      columns = [...perColumns.values()].flat()
    }
  } else if (atStart.schema) {
    // 非多库方言选中库:执行时会话已切到该库,表名/字段名均补未限定名
    // 先表后字段:表清单先上(不等字段),字段分批渐进补齐
    const ts = await fetchTables(id, atStart.schema, null, refresh)
    tables = ts.map((t) => toTableOption(t.name, t.comment))
    if (!stale()) tableOptions.value = [...tables, ...KEYWORD_OPTIONS]
    const cs = await fetchColumnsBatched(id, atStart.schema, null, ts.map((t) => t.name), refresh, (acc) => {
      if (!stale()) tableOptions.value = [...tables, ...acc.map((c) => toColumnOption(c)), ...KEYWORD_OPTIONS]
    })
    columns = cs.map((c) => toColumnOption(c))
  } else {
    // 未选库:只枚举库下拉全部 schema 的表名(字段量太大不拉),补全项用 schema.table 限定名;
    // 另附一份未限定名(跨库同名去重),落在默认库的表直接敲表名也能匹配
    const per = await Promise.all(schemas.value.slice(0, COMPLETION_MAX_SCHEMAS)
      .map((s) => fetchTables(id, s, null, refresh)))
    const seenPlain = new Set()
    schemas.value.slice(0, COMPLETION_MAX_SCHEMAS).forEach((s, i) => {
      for (const t of per[i]) {
        tables.push(toTableOption(`${s}.${t.name}`, t.comment))
        if (!seenPlain.has(t.name)) {
          seenPlain.add(t.name)
          tables.push(toTableOption(t.name, t.comment))
        }
      }
    })
  }
  if (stale()) return
  const options = [...tables, ...columns]
  tableOptions.value = [...options, ...KEYWORD_OPTIONS]
  // 覆盖更新 localStorage 缓存(选中库的路径含字段清单);
  // 空结果不写:库暂时连不上/列表还在加载时不至于把已有的好缓存冲掉
  if (options.length) writeMetaCache(cacheKey, options)
}

// 切换库后重拉表清单(多库方言未选库时为关键字-only,无需请求);
// 注意必须包一层箭头函数:watch 会把新值作为第一个参数传入,直接传 loadTableCompletions 会被当成 refresh 实参
watch(selectedSchema, () => loadTableCompletions())

// 刷新按钮:强制从源库拉取表/字段提示,覆盖服务端 H2 缓存与 localStorage
const metaRefreshing = ref(false)

async function refreshMeta() {
  if (!selectedDsId.value || metaRefreshing.value) return
  metaRefreshing.value = true
  try {
    await loadTableCompletions(true)
    ElMessage.success('表/字段提示已从源库刷新')
  } finally {
    metaRefreshing.value = false
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
  // 高度铺满外层 flex 容器(页面剩余空间),内容超出后滚动
  '&': { backgroundColor: 'transparent', fontSize: '13px', height: '100%' },
  '.cm-content': {
    fontFamily: "'SF Mono', Menlo, Consolas, 'Courier New', monospace",
    caretColor: 'var(--el-text-color-primary)',
  },
  '.cm-scroller': { overflowY: 'auto', lineHeight: '1.6' },
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
  // 补全下拉:跟随 Element 弹层配色(与 SqlInput.vue 同一套)
  '.cm-tooltip': {
    backgroundColor: 'var(--el-bg-color-overlay)',
    border: '1px solid var(--el-border-color-light)',
    borderRadius: '4px',
    color: 'var(--el-text-color-primary)',
  },
  '.cm-tooltip.cm-tooltip-autocomplete > ul > li[aria-selected]': {
    backgroundColor: 'var(--el-color-primary)',
    color: 'var(--el-color-white)',
  },
  '.cm-completionDetail': { color: 'var(--el-text-color-placeholder)', fontStyle: 'normal' },
  // 补全项类型徽标(CM 默认无图标):表=class、列=property、词=keyword,一眼区分
  '.cm-completionIcon': { padding: '0 4px 0 0', width: 'auto', opacity: '1' },
  '.cm-completionIcon::after': {
    display: 'inline-block',
    fontSize: '10px',
    lineHeight: '14px',
    padding: '0 3px',
    borderRadius: '3px',
  },
  '.cm-completionIcon-class::after': {
    content: '"表"',
    color: 'var(--el-color-primary)',
    border: '1px solid var(--el-color-primary)',
  },
  '.cm-completionIcon-property::after': {
    content: '"列"',
    color: 'var(--el-color-success)',
    border: '1px solid var(--el-color-success)',
  },
  '.cm-completionIcon-keyword::after': {
    content: '"词"',
    color: 'var(--el-text-color-placeholder)',
    border: '1px solid var(--el-border-color)',
  },
})

// ---------- Cmd/Ctrl+点击表名跳转字段明细 ----------
const router = useRouter()

// 表位关键字:标识符前一个字是这些词时才视为「表」(FROM/JOIN/INTO/UPDATE 等)
const TABLE_POS_KEYWORDS = new Set(['from', 'join', 'into', 'update', 'table', 'truncate', 'describe', 'desc'])
const IDENT_CHAR = /[\w$一-龥]/
// 引号包裹标识符的开闭符号:`t`(MySQL)/"t"(PG/Oracle)/[t](SQL Server)
const QUOTE_PAIRS = { '`': '`', '"': '"', '[': ']' }
const stripQuotes = (s) => s.replace(/^[`"[]|[`"\]]$/g, '')

/** 在已加载的表清单(智能提示同一份数据)里匹配表名,反查出跳转所需的 schema/db;匹配不到返回 null */
function matchTable(name, schemaHint) {
  const ds = datasources.value.find((d) => d.id === selectedDsId.value)
  const multiDb = ['SQLSERVER', 'KINGBASE'].includes(ds?.dbType)
  // 补全项里表条目(type=class)的 label 为 schema.table 限定名或未限定名,拆回 {schema, table}
  const entries = []
  for (const o of tableOptions.value) {
    if (o.type !== 'class') continue
    const dot = o.label.lastIndexOf('.')
    entries.push(dot > 0
      ? { schema: o.label.slice(0, dot), table: o.label.slice(dot + 1) }
      : { schema: null, table: o.label })
  }
  const eq = (a, b) => a.toLowerCase() === b.toLowerCase()
  // 多库方言跳转需带 db(当前选中的数据库名);未选库时表清单为空,走不到这
  const db = multiDb ? selectedSchema.value || undefined : undefined
  if (schemaHint) {
    const hit = entries.find((e) => eq(e.table, name)
      && (e.schema ? eq(e.schema, schemaHint) : eq(selectedSchema.value || '', schemaHint)))
    return hit ? { schema: hit.schema || schemaHint, table: hit.table, db } : null
  }
  if (selectedSchema.value && !multiDb) {
    // 非多库方言已选库:未限定名直接归属当前库
    const hit = entries.find((e) => eq(e.table, name))
    return hit ? { schema: selectedSchema.value, table: hit.table, db } : null
  }
  // 未选库(或多库方言选了数据库):从限定名条目反查 schema,跨库同名取第一个
  const hit = entries.find((e) => e.schema && eq(e.table, name))
  return hit ? { schema: hit.schema, table: hit.table, db } : null
}

/**
 * 解析编辑器 pos 处的表名引用,命中条件:
 * 1) pos 落在某条语句区间内(顶层分号切分),即一条语法完整的语句里;
 * 2) 标识符(支持 schema.table 限定名与引号包裹,点 schema 部分也能识别)前一个字是表位关键字;
 * 3) 表名在已加载的表清单里能匹配出 schema。
 * 返回 { from, to, schema, table, db }(from/to 供悬停装饰),不满足返回 null。
 */
function resolveTableRefAt(pos) {
  // 本地 H2 库是应用自身配置库,没有对应的数据源字段明细页,不支持跳转
  if (!view || !selectedDsId.value || isLocalH2.value) return null
  const docText = view.state.doc.toString()
  const stmt = splitStatements(docText).find((s) => pos >= s.from && pos <= s.to)
  if (!stmt) return null
  let from = pos
  let to = pos
  while (from > stmt.from && IDENT_CHAR.test(docText[from - 1])) from--
  while (to < stmt.to && IDENT_CHAR.test(docText[to])) to++
  if (from === to) return null
  // 引号包裹的标识符:把开闭引号一起吃进区间
  if (QUOTE_PAIRS[docText[from - 1]] && docText[to] === QUOTE_PAIRS[docText[from - 1]]) { from--; to++ }
  let name = stripQuotes(docText.slice(from, to))
  let schema = null
  let anchor = from // 表位关键字相对整个「schema.table」的起点判断
  if (docText[to] === '.') {
    // 点在限定名的 schema 部分上:向右取表名
    let tTo = to + 1
    while (tTo < stmt.to && IDENT_CHAR.test(docText[tTo])) tTo++
    if (tTo === to + 1) return null
    schema = name
    name = stripQuotes(docText.slice(to + 1, tTo))
    to = tTo
  } else if (docText[from - 1] === '.') {
    // 左侧有 schema 限定:向左取 schema,装饰区间扩到整个限定名
    let sFrom = from - 1
    while (sFrom > stmt.from && IDENT_CHAR.test(docText[sFrom - 1])) sFrom--
    if (sFrom === from - 1) return null
    schema = stripQuotes(docText.slice(sFrom, from - 1))
    from = sFrom
    anchor = sFrom
  }
  if (!name) return null
  const before = docText.slice(stmt.from, anchor).trimEnd()
  const kwMatch = before.match(/([A-Za-z]+)$/)
  if (!kwMatch || !TABLE_POS_KEYWORDS.has(kwMatch[1].toLowerCase())) return null
  const ref = matchTable(name, schema)
  return ref ? { from, to, ...ref } : null
}

/** 跳转到表字段明细页(路由 push 后由页签体系自动落到该数据源页签) */
function openTableDetail(ref) {
  const ds = datasources.value.find((d) => d.id === selectedDsId.value)
  const params = []
  if (ref.db) params.push(`db=${encodeURIComponent(ref.db)}`)
  if (ds?.name) params.push(`name=${encodeURIComponent(ds.name)}`)
  const q = params.length ? `?${params.join('&')}` : ''
  router.push(`/datasources/${selectedDsId.value}/schemas/${encodeURIComponent(ref.schema)}/tables/${encodeURIComponent(ref.table)}${q}`)
}

// 悬停下划线装饰:按住 Cmd/Ctrl 悬停在可跳转表名上时加下划线+手型
const setTableLinkDeco = StateEffect.define()
const tableLinkMark = Decoration.mark({ class: 'cm-table-link' })
const tableLinkField = StateField.define({
  create: () => Decoration.none,
  update(deco, tr) {
    for (const e of tr.effects) {
      if (e.is(setTableLinkDeco)) {
        return e.value ? Decoration.set([tableLinkMark.range(e.value.from, e.value.to)]) : Decoration.none
      }
    }
    return deco.map(tr.changes)
  },
  provide: (f) => EditorView.decorations.from(f),
})

// 当前悬停的表名区间:用于避免重复 dispatch 装饰
let hoverRange = null

function updateHoverDeco(range) {
  const same = (!range && !hoverRange)
    || (range && hoverRange && range.from === hoverRange.from && range.to === hoverRange.to)
  if (same || !view) return
  hoverRange = range
  view.dispatch({ effects: setTableLinkDeco.of(range ? { from: range.from, to: range.to } : null) })
}

const tableLinkHandlers = EditorView.domEventHandlers({
  mousemove(event, v) {
    if (!(event.metaKey || event.ctrlKey)) {
      updateHoverDeco(null)
      return
    }
    const pos = v.posAtCoords({ x: event.clientX, y: event.clientY })
    updateHoverDeco(pos == null ? null : resolveTableRefAt(pos))
  },
  mousedown(event, v) {
    if (!(event.metaKey || event.ctrlKey) || event.button !== 0) return
    const pos = v.posAtCoords({ x: event.clientX, y: event.clientY })
    const ref = pos == null ? null : resolveTableRefAt(pos)
    if (!ref) return
    event.preventDefault()
    updateHoverDeco(null)
    openTableDetail(ref)
  },
})

// 松开 Cmd/Ctrl 或窗口失焦时清掉悬停装饰
function onModifierKeyup(e) {
  if (e.key === 'Meta' || e.key === 'Control') updateHoverDeco(null)
}
const onWindowBlur = () => updateHoverDeco(null)

onMounted(() => {
  loadDatasources()
  window.addEventListener('keyup', onModifierKeyup)
  window.addEventListener('blur', onWindowBlur)
  view = new EditorView({
    parent: editorHost.value,
    state: EditorState.create({
      extensions: [
        // 执行快捷键:Ctrl/Cmd+Enter,最高优先级保证不被其他 keymap 抢走
        Prec.highest(keymap.of([
          { key: 'Ctrl-Enter', run: () => (doExecute(), true) },
          { key: 'Cmd-Enter', run: () => (doExecute(), true) },
        ])),
        // 光标移动/文本变更后刷新「将执行哪条语句」的提示
        EditorView.updateListener.of((u) => {
          if (u.selectionSet || u.docChanged) refreshExecHint()
        }),
        lineNumbers(),
        sql(),
        syntaxHighlighting(highlight),
        // 表名/关键字智能提示:自定义动态补全源,随数据源+库下拉刷新
        autocompletion({ override: [completionSource], activateOnTyping: true }),
        // Cmd/Ctrl+点击表名跳转字段明细(悬停下划线装饰 + 点击处理)
        tableLinkField,
        tableLinkHandlers,
        // 补全弹层用 fixed 定位:编辑器外壳 overflow:hidden 且内部滚动,绝对定位会被裁剪/错位
        tooltips({ position: 'fixed' }),
        cmPlaceholder('可写多条 SQL 用分号隔开;Ctrl/Cmd+Enter 执行光标所在语句,选中片段时仅执行选中部分'),
        theme,
      ],
    }),
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('keyup', onModifierKeyup)
  window.removeEventListener('blur', onWindowBlur)
  view?.destroy()
  view = null
})

/** 取待执行 SQL(类 DataGrip):优先当前选中片段(剥掉末尾分号/空白);无选中取光标所在语句(按顶层分号切分) */
function currentSql() {
  const { from, to } = view.state.selection.main
  const selected = view.state.sliceDoc(from, to).trim().replace(/[;\s]+$/, '')
  if (selected) return selected
  return statementAt(view.state.doc.toString(), view.state.selection.main.head)
}

// 「将执行哪条语句」提示:跟随光标/选中实时刷新,让执行目标可见
const execHint = ref('')

function refreshExecHint() {
  if (!view) return
  const { from, to, head } = view.state.selection.main
  if (from !== to) {
    execHint.value = `将执行选中片段(${to - from} 字符)`
    return
  }
  const doc = view.state.doc.toString()
  const stmt = statementAt(doc, head)
  if (!stmt) {
    // 编辑器有内容但光标被空行隔开、够不到任何语句时给一句引导,避免用户误以为会执行某条
    execHint.value = doc.trim() ? '光标处没有 SQL 语句:移到某条语句内或选中片段执行' : ''
    return
  }
  const firstLine = stmt.split('\n', 1)[0]
  const preview = firstLine.length > 60 ? firstLine.slice(0, 60) + '…' : firstLine
  execHint.value = `将执行光标所在语句:${preview}`
}

// ---------- 执行 ----------
const executing = ref(false)

/**
 * 单元格分段:制表符 \t 原样保留(模板里 cell-text 用 white-space:pre + tab-size:8 展开成对齐空位,
 * 效果同 DataGrip);换行 \n、回车 \r、\0 及其余 C0/DEL 控制码拆成可见转义段(\n \r \0 \xNN),
 * 模板里用醒目底色渲染;普通文本原样一段返回(无控制字符时零拆分,直接走原文)。
 */
function cellSegments(v) {
  if (v === null || v === undefined) return [{ text: '', ctrl: false }]
  const s = String(v)
  if (!/[\x00-\x08\x0A-\x1F\x7F]/.test(s)) return [{ text: s, ctrl: false }]
  const segs = []
  let buf = ''
  const flush = () => { if (buf) { segs.push({ text: buf, ctrl: false }); buf = '' } }
  for (const c of s) {
    let esc = null
    if (c === '\n') esc = '\\n'
    else if (c === '\r') esc = '\\r'
    else if (c === '\0') esc = '\\0'
    else if ((c < '\x20' && c !== '\t') || c === '\x7F') esc = '\\x' + c.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')
    if (esc) { flush(); segs.push({ text: esc, ctrl: true }) } else buf += c
  }
  flush()
  return segs
}
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
    // 区分「编辑器空」与「有内容但光标够不到任何语句(被空行隔开)」两种情况
    ElMessage.warning(view.state.doc.toString().trim()
      ? '光标处没有 SQL 语句:请把光标移到某条语句内,或选中要执行的片段'
      : '请先输入 SQL')
    return
  }
  executing.value = true
  execError.value = ''
  result.value = null
  try {
    // 本地 H2 库走只读专用入口(后端语句级只读校验,写语句 400)
    const url = isLocalH2.value
      ? '/sql-console/local-h2/execute'
      : `/datasources/${selectedDsId.value}/sql/execute`
    const res = await api.post(url, { sql: sqlText, schema: selectedSchema.value || undefined })
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

// ---------- 执行历史(localStorage,最近 50 条,按 SQL+数据源+库 去重只留最新一条) ----------
// 条目带 dsId/schema(旧格式没有,向后兼容:点击时只回填 SQL 不切数据源),点击自动切回原数据源和库
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

/** 历史条目里数据源 id → 显示名(本地 H2 库与已删除的数据源走各自兜底文案) */
function dsNameOf(dsId) {
  return dsOptions.value.find((d) => d.id === dsId)?.name || '已删除数据源'
}

function pushHistory(sqlText) {
  const dsId = selectedDsId.value
  const schema = selectedSchema.value
  // 执行片段会剥掉末尾分号/空白,入历史统一补回分号,回填后可直接连写多条再执行
  const sql = /;\s*$/.test(sqlText) ? sqlText : sqlText.trimEnd() + ';'
  // 同一条 SQL 在不同数据源/库上算不同上下文,各自保留
  history.value = history.value.filter((h) =>
    !(h.sql === sql && (h.dsId || '') === dsId && (h.schema || '') === schema))
  history.value.unshift({ sql, time: Date.now(), dsId, schema })
  if (history.value.length > HISTORY_MAX) history.value.length = HISTORY_MAX
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value))
}

/** 点击历史条目:回填编辑器全文并关闭抽屉;条目带数据源/库时自动切换回去 */
async function useHistory(item) {
  historyVisible.value = false
  if (item.dsId) {
    if (!dsOptions.value.some((d) => d.id === item.dsId)) {
      ElMessage.warning('历史中的数据源已删除,仅回填 SQL')
    } else {
      if (item.dsId !== selectedDsId.value) {
        selectedDsId.value = item.dsId
        await nextTick()     // 让 watch 回调先跑起来(清空已选库、开始拉库清单)
        await schemasReady   // 等库清单就绪再选库,防被清空逻辑冲掉
      }
      // 库已不在清单里(被删/白名单挡住)时保持默认库,不硬选
      if (item.schema && schemas.value.includes(item.schema)) {
        selectedSchema.value = item.schema
      }
    }
  }
  if (view) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: item.sql } })
  }
}
</script>

<style scoped>
/* 整页纵向 flex 铺满主区域:编辑器占满剩余高度,结果区按需占位、超高内部滚动 */
.sql-console-page {
  display: flex;
  flex-direction: column;
  height: calc(100% - 40px); /* 扣除 page-card 上下 margin */
  box-sizing: border-box;
  overflow: hidden;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ds-select {
  width: 220px;
}
.schema-select {
  width: 180px;
}
.ds-option {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 编辑器外壳:边框/圆角/聚焦色对齐 el-input,--dq-sql-ident 供高亮配色引用(明暗两套);
   flex 纵向布局让 cm-editor 吃满外壳高度 */
.sql-editor {
  flex: 1 1 auto;
  min-height: 220px;
  display: flex;
  flex-direction: column;
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
/* Cmd/Ctrl+悬停可跳转表名:下划线 + 手型(颜色沿用标识符高亮,不另配色) */
.sql-editor :deep(.cm-table-link) {
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}
.sql-editor :deep(.cm-editor) {
  flex: 1;
  min-height: 0;
  background: transparent;
}
/* 结果区按需占位(上限约半页),超出内部滚动,编辑器保持可见 */
.result-area {
  flex: 0 0 auto;
  max-height: 46%;
  overflow-y: auto;
  margin-top: 14px;
}
.exec-hint {
  flex: none;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
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
/* 结果单元格文本:pre 保留制表符并按 8 列 tab 位展开(DataGrip 同款对齐效果),
   单行不换行,溢出仍由 show-overflow-tooltip 省略号+悬浮提示承接 */
.cell-text {
  white-space: pre;
  tab-size: 8;
}
/* 单元格内控制字符的可见转义符:等宽 + 醒目底色,与真实数据一眼区分(明暗主题自动适配) */
.ctrl-char {
  font-family: 'SF Mono', Menlo, Consolas, 'Courier New', monospace;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-radius: 3px;
  padding: 0 2px;
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
/* 历史条目里的数据源/库标识:比时间戳稍醒目一点 */
.history-ds {
  color: var(--el-text-color-secondary);
}
</style>
