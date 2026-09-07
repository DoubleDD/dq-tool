<template>
  <div class="page-card">
    <div class="toolbar">
      <div class="filter-row">
        <el-input v-model="keyword" placeholder="按表名或注释搜索" clearable style="width: 260px" />
        <el-select v-model="selectedDsId" clearable placeholder="按数据源筛选" style="width: 220px">
          <el-option v-for="ds in dsOptions" :key="ds.id" :label="ds.name" :value="String(ds.id)" />
        </el-select>
        <el-select v-model="selectedDb" clearable placeholder="按库筛选" style="width: 220px">
          <el-option v-for="db in dbOptions" :key="db" :label="db" :value="db" />
        </el-select>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="Download" :disabled="filteredList.length === 0" @click="exportExcel">导出</el-button>
        <el-button type="danger" plain :disabled="selectedRows.length === 0" @click="removeSelected">
          批量取消({{ selectedRows.length }})
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table :data="filteredList" v-loading="loading" border row-key="id"
              @selection-change="onSelectionChange" @sort-change="onSortChange">
      <el-table-column type="selection" width="45" />
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="datasourceName" label="数据源" min-width="140" sortable :sort-method="sortBy(dsLabel)" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goDatasource(row)">{{ dsLabel(row) }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="dbName" label="库" min-width="140" sortable :sort-method="sortBy(dbLabel)" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goSchema(row)">{{ dbLabel(row) }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="tableName" label="表名" min-width="180" sortable :sort-method="sortBy((r) => r.tableName)" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goTable(row)">{{ row.tableName }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="tableComment" label="注释" min-width="160" sortable :sort-method="sortBy((r) => r.tableComment || '')" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.tableComment">{{ row.tableComment }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="tags" label="标记" min-width="140" sortable :sort-method="sortBy(tagNames)" show-overflow-tooltip>
        <template #default="{ row }">
          <template v-if="(row.tags || []).length">
            <el-tag
              v-for="tag in row.tags"
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
      <el-table-column prop="description" label="描述" min-width="200" sortable :sort-method="sortBy((r) => r.description || '')" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.description">{{ row.description }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="采集时间" width="170" sortable :sort-method="sortBy(collectTime)">
        <template #default="{ row }">{{ row.createdAt ? formatDateTime(row.createdAt) : '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="removeCollect(row)">取消采集</el-button>
        </template>
      </el-table-column>
      <template #empty>暂无采集记录,可到「数据源 → 库 → 表列表」勾选或单行采集</template>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { Download, Refresh } from '@element-plus/icons-vue'
import request from '../api'
import { formatDateTime } from '../utils/format'
import { cellText, exportListToExcel } from '../utils/listExport'

const router = useRouter()

const list = ref([])
const loading = ref(false)
const keyword = ref('')
// 数据源筛选:选中的数据源 id(字符串);库筛选:选中的库标签(dbLabel 组合值)
const selectedDsId = ref('')
const selectedDb = ref('')

// 数据源下拉项:采集记录里出现过的数据源去重(数据源被删后名称为空,显示兜底 id)
const dsOptions = computed(() => {
  const seen = new Map()
  for (const row of list.value) {
    if (!seen.has(row.datasourceId)) seen.set(row.datasourceId, { id: row.datasourceId, name: dsLabel(row) })
  }
  return [...seen.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh'))
})

// 库下拉项:当前数据源筛选下的库标签去重(先选数据源时库选项随动收敛)
const dbOptions = computed(() => {
  const rows = selectedDsId.value
    ? list.value.filter((r) => String(r.datasourceId) === selectedDsId.value)
    : list.value
  return [...new Set(rows.map(dbLabel))].sort((a, b) => a.localeCompare(b, 'zh'))
})

const filteredList = computed(() => {
  let rows = list.value
  if (selectedDsId.value) {
    rows = rows.filter((r) => String(r.datasourceId) === selectedDsId.value)
  }
  if (selectedDb.value) {
    rows = rows.filter((r) => dbLabel(r) === selectedDb.value)
  }
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    rows = rows.filter((r) =>
      r.tableName.toLowerCase().includes(kw) || (r.tableComment || '').toLowerCase().includes(kw))
  }
  return rows
})

function dsLabel(row) {
  return row.datasourceName || `数据源 ${row.datasourceId}`
}

// 库标签:多库数据源(如 SQL Server)带上数据库名,与「库列表」页签标题口径一致
function dbLabel(row) {
  return row.dbName ? `${row.dbName}.${row.schemaName}` : row.schemaName
}

// 排序用:采集时间的毫秒值
function collectTime(row) {
  return row.createdAt ? new Date(row.createdAt).getTime() : 0
}

// 标记列展示/排序/导出共用:标记名逗号拼接
function tagNames(row) {
  return (row.tags || []).map((tag) => tag.name).join(', ')
}

// 列排序比较器工厂:数字按差值,字符串按中文排序(空值归一空串沉底/升顶由 order 决定)
function sortBy(getter) {
  return (a, b) => {
    const va = getter(a)
    const vb = getter(b)
    return typeof va === 'number' ? va - vb : String(va).localeCompare(String(vb), 'zh')
  }
}

// 当前表格排序状态(el-table 内部排序,这里仅跟踪,导出按同序输出)
const sortState = ref({ prop: '', order: '' })

function onSortChange({ prop, order }) {
  sortState.value = { prop: prop || '', order: order || '' }
}

// 各列排序取值器,与列定义里的 sort-method 保持同口径
const sortGetters = {
  tableName: (r) => r.tableName,
  tableComment: (r) => r.tableComment || '',
  tags: tagNames,
  description: (r) => r.description || '',
  datasourceName: dsLabel,
  dbName: dbLabel,
  createdAt: collectTime
}

// 导出用:过滤后的行按当前表格排序输出(未排序时保持采集时间倒序)
const sortedList = computed(() => {
  const { prop, order } = sortState.value
  const getter = sortGetters[prop]
  if (!getter || !order) return filteredList.value
  const dir = order === 'ascending' ? 1 : -1
  return [...filteredList.value].sort((a, b) => sortBy(getter)(a, b) * dir)
})

// 导出当前过滤后的列表 Excel(列与页面一致,不含序号/操作列;行序与表格当前排序一致)
function exportExcel() {
  const headers = ['数据源', '库', '表名', '注释', '标记', '描述', '采集时间']
  const rows = sortedList.value.map((row) => [
    dsLabel(row),
    dbLabel(row),
    cellText(row.tableName),
    cellText(row.tableComment),
    tagNames(row),
    cellText(row.description),
    row.createdAt ? formatDateTime(row.createdAt) : ''
  ])
  exportListToExcel('人工采集清单', headers, rows, '人工采集')
}

async function load() {
  loading.value = true
  try {
    list.value = await request.get('/manual-collects')
  } finally {
    loading.value = false
  }
}

// 取消采集:确认后删除并就地移除行
async function removeCollect(row) {
  try {
    await ElMessageBox.confirm(`确定取消采集表「${row.tableName}」(${dsLabel(row)} / ${dbLabel(row)})吗?`, '取消采集', {
      type: 'warning',
      confirmButtonText: '取消采集',
      cancelButtonText: '再想想'
    })
  } catch {
    return // 用户取消
  }
  await request.delete(`/manual-collects/${row.id}`)
  list.value = list.value.filter((r) => r.id !== row.id)
  ElMessage.success(`已取消采集「${row.tableName}」`)
}

// 表格勾选的行(批量取消用)
const selectedRows = ref([])

function onSelectionChange(rows) {
  selectedRows.value = rows
}

// 批量取消:一次请求按 id 批量删除,就地移除行
async function removeSelected() {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(`确定取消选中的 ${rows.length} 条采集记录吗?`, '批量取消采集', {
      type: 'warning',
      confirmButtonText: '取消采集',
      cancelButtonText: '再想想'
    })
  } catch {
    return // 用户取消
  }
  const { deleted } = await request.post('/manual-collects/batch-delete', { ids: rows.map((r) => r.id) })
  const ids = new Set(rows.map((r) => r.id))
  list.value = list.value.filter((r) => !ids.has(r.id))
  ElMessage.success(`已取消采集 ${deleted} 条记录`)
}

// 数据源列:跳该数据源的库列表页(带数据源名,页签标题恢复真名)
function goDatasource(row) {
  router.push({ path: `/datasources/${row.datasourceId}/schemas`, query: { name: dsLabel(row) } })
}

// 库列:跳该库的表列表页(带 db 与数据源名)
function goSchema(row) {
  const query = { name: dsLabel(row) }
  if (row.dbName) query.db = row.dbName
  router.push({ path: `/datasources/${row.datasourceId}/schemas/${encodeURIComponent(row.schemaName)}/tables`, query })
}

// 表名链接:跳到该表的字段明细页(带 db 与数据源名,页签标题能恢复真名)
function goTable(row) {
  const query = { name: dsLabel(row) }
  if (row.dbName) query.db = row.dbName
  router.push({
    path: `/datasources/${row.datasourceId}/schemas/${encodeURIComponent(row.schemaName)}/tables/${encodeURIComponent(row.tableName)}`,
    query
  })
}

// 首次挂载标记:onActivated 在首次挂载后也会触发,避免与 onMounted 重复加载
const mounted = ref(false)

onMounted(async () => {
  await load()
  mounted.value = true
})

// 页签切换是失活而非卸载:回来时重载(别处可能新增/取消了采集)
onActivated(() => {
  if (!mounted.value) return
  load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.filter-row {
  display: flex;
  gap: 16px;
  align-items: center;
}
.table-tag {
  margin: 0 4px 2px 0;
}
</style>
