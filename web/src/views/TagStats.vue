<template>
  <div class="page-card tag-stats">
    <!-- 左侧:标记列表(含系统「空表」标记),支持新建/编辑/删除 -->
    <div class="tag-panel">
      <div class="tag-panel-title">
        <span>标记列表</span>
        <el-button link type="primary" :icon="Plus" @click="startCreate">新建</el-button>
      </div>
      <div v-loading="tagsLoading" class="tag-list">
        <!-- 新建行:名称 + 颜色 -->
        <div v-if="creating" class="tag-item tag-form-row">
          <el-input v-model="createForm.name" placeholder="标记名称" maxlength="50" size="small"
                    @keyup.enter="createTag" />
          <el-color-picker v-model="createForm.color" :predefine="presetColors" size="small" />
          <el-button link type="primary" size="small" :loading="operating" @click="createTag">确定</el-button>
          <el-button link size="small" @click="creating = false">取消</el-button>
        </div>
        <template v-for="tag in tags" :key="tag.id">
          <!-- 编辑行:名称 + 颜色(仅用户标记) -->
          <div v-if="editingId === tag.id" class="tag-item tag-form-row">
            <el-input v-model="editForm.name" maxlength="50" size="small" @keyup.enter="saveEdit" />
            <el-color-picker v-model="editForm.color" :predefine="presetColors" size="small" />
            <el-button link type="primary" size="small" :loading="operating" @click="saveEdit">保存</el-button>
            <el-button link size="small" @click="editingId = null">取消</el-button>
          </div>
          <div
            v-else
            class="tag-item"
            :class="{ active: currentTag && currentTag.id === tag.id }"
            @click="selectTag(tag)"
          >
            <span class="tag-dot" :style="{ background: tag.color }" />
            <span class="tag-name" :title="tag.name">{{ tag.name }}</span>
            <el-tag v-if="tag.kind === 'EMPTY'" size="small" type="info">系统</el-tag>
            <span class="tag-count">{{ formatNumber(tag.tableCount ?? 0) }}</span>
            <span v-if="tag.kind !== 'EMPTY'" class="tag-actions">
              <el-button link type="primary" :icon="Edit" title="编辑" @click.stop="startEdit(tag)" />
              <el-button link type="danger" :icon="Delete" title="删除" @click.stop="removeTag(tag)" />
            </span>
          </div>
        </template>
        <el-empty v-if="!tagsLoading && !tags.length && !creating" description="暂无标记" :image-size="80">
          <template #description>
            <span>暂无标记,点击右上角「新建」创建</span>
          </template>
        </el-empty>
      </div>
    </div>

    <!-- 右侧:指标卡 + 分布表格 -->
    <div class="stats-panel">
      <el-empty v-if="!currentTag" description="请选择左侧标记查看统计" />
      <template v-else>
        <div v-loading="statsLoading">
          <div class="metric-cards" v-if="stats">
            <div class="metric-card">
              <div class="metric-value">{{ formatNumber(stats.totalTables ?? 0) }}</div>
              <div class="metric-label">总表数</div>
            </div>
            <div class="metric-card">
              <div class="metric-value">{{ numOrDash(stats.totalRows) }}</div>
              <div class="metric-label">总行数</div>
              <div class="metric-note">取最近一次扫描</div>
            </div>
            <div class="metric-card">
              <div class="metric-value">{{ numOrDash(stats.totalColumns) }}</div>
              <div class="metric-label">总列数</div>
              <div class="metric-note">取最近一次扫描</div>
            </div>
            <div class="metric-card">
              <div class="metric-value">{{ formatNumber(stats.coveredTables ?? 0) }}</div>
              <div class="metric-label">标记覆盖表数(去重)</div>
              <div class="metric-note">所有标记合计,多标记只算一次</div>
            </div>
          </div>

          <el-table
            v-if="stats"
            :data="stats.schemas || []"
            border
            show-summary
            :summary-method="summaryMethod"
            style="width: 100%; margin-top: 16px"
          >
            <el-table-column prop="datasourceName" label="数据源" min-width="140" sortable
                             :sort-method="(a, b) => (a.datasourceName || '').localeCompare(b.datasourceName || '', 'zh-CN')" />
            <el-table-column label="库" min-width="160" sortable
                             :sort-method="(a, b) => schemaLabel(a).localeCompare(schemaLabel(b), 'zh-CN')">
              <template #default="{ row }">{{ schemaLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="表数" width="120" align="right" sortable
                             :sort-method="(a, b) => (a.tableCount ?? -1) - (b.tableCount ?? -1)">
              <template #default="{ row }">
                <el-link type="primary" @click="goTables(row)">{{ formatNumber(row.tableCount ?? 0) }}</el-link>
              </template>
            </el-table-column>
            <el-table-column label="行数" width="140" align="right" sortable
                             :sort-method="(a, b) => (a.totalRows ?? -1) - (b.totalRows ?? -1)">
              <template #default="{ row }">
                <el-link v-if="row.totalRows !== null && row.totalRows !== undefined" type="primary" @click="goTables(row)">
                  {{ formatNumber(row.totalRows) }}
                </el-link>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="列数" width="120" align="right" sortable
                             :sort-method="(a, b) => (a.totalColumns ?? -1) - (b.totalColumns ?? -1)">
              <template #default="{ row }">
                <el-link v-if="row.totalColumns !== null && row.totalColumns !== undefined" type="primary" @click="goTables(row)">
                  {{ formatNumber(row.totalColumns) }}
                </el-link>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onActivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'
import request from '../api'
import { formatNumber } from '../utils/format'

const router = useRouter()

const tags = ref([])
const tagsLoading = ref(false)
const currentTag = ref(null)
const stats = ref(null)
const statsLoading = ref(false)

// 标记管理:新建/编辑内联表单(系统「空表」标记不可编辑)
const creating = ref(false)
const editingId = ref(null)
const operating = ref(false)
const createForm = reactive({ name: '', color: '#409EFF' })
const editForm = reactive({ name: '', color: '#409EFF' })
const presetColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#9B59B6', '#16A085', '#D35400']

/** null(该库无已扫描表)显示「—」 */
function numOrDash(n) {
  return n === null || n === undefined ? '—' : formatNumber(n)
}

/** 库名标签:有数据库实例时拼成 db.schema */
function schemaLabel(row) {
  return row.dbName ? `${row.dbName}.${row.schemaName}` : row.schemaName
}

async function loadTags() {
  tagsLoading.value = true
  try {
    tags.value = await request.get('/tags')
    // 列表刷新后同步当前选中标记的最新表数;已被删除的标记清空选中
    if (currentTag.value) {
      const fresh = tags.value.find((t) => t.id === currentTag.value.id)
      currentTag.value = fresh || null
      if (!fresh) stats.value = null
    }
  } finally {
    tagsLoading.value = false
  }
}

async function loadStats() {
  if (!currentTag.value) return
  statsLoading.value = true
  try {
    stats.value = await request.get(`/tags/${currentTag.value.id}/stats`)
  } finally {
    statsLoading.value = false
  }
}

function selectTag(tag) {
  if (currentTag.value && currentTag.value.id === tag.id) return
  currentTag.value = tag
  stats.value = null
  loadStats()
}

// ---- 标记管理(与打标弹窗同一套 /tags 接口) ----

function startCreate() {
  creating.value = true
  editingId.value = null
  createForm.name = ''
  createForm.color = '#409EFF'
}

async function createTag() {
  const name = createForm.name.trim()
  if (!name) {
    ElMessage.warning('标记名称不能为空')
    return
  }
  operating.value = true
  try {
    await request.post('/tags', { name, color: createForm.color || '#409EFF' })
    creating.value = false
    ElMessage.success('已创建')
    await loadTags()
  } finally {
    operating.value = false
  }
}

function startEdit(tag) {
  editingId.value = tag.id
  creating.value = false
  editForm.name = tag.name
  editForm.color = tag.color
}

async function saveEdit() {
  const name = editForm.name.trim()
  if (!name) {
    ElMessage.warning('标记名称不能为空')
    return
  }
  operating.value = true
  try {
    await request.put(`/tags/${editingId.value}`, { name, color: editForm.color || '#409EFF' })
    editingId.value = null
    ElMessage.success('已保存')
    // loadTags 会用最新名称/颜色同步当前选中标记
    await loadTags()
  } finally {
    operating.value = false
  }
}

async function removeTag(tag) {
  await ElMessageBox.confirm(
    `确定删除标记「${tag.name}」吗?已打该标记的 ${tag.tableCount ?? 0} 张表会自动解除。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除' }
  )
  operating.value = true
  try {
    await request.delete(`/tags/${tag.id}`)
    ElMessage.success('已删除')
    // loadTags 发现当前选中标记已删除时会自动清空选中与统计
    await loadTags()
  } finally {
    operating.value = false
  }
}

/** 跳转只读表列表:库 + 标记过滤;dbName 为空串时不带 db 参数 */
function goTables(row) {
  const params = new URLSearchParams()
  if (row.dbName) params.set('db', row.dbName)
  params.set('tagId', String(currentTag.value.id))
  params.set('tagName', currentTag.value.name)
  router.push(`/datasources/${row.datasourceId}/schemas/${encodeURIComponent(row.schemaName)}/tables?${params.toString()}`)
}

/** 合计行:表数必为数字;行/列数全部为 null 时显示「—」 */
function summaryMethod({ data }) {
  const sumTables = data.reduce((acc, r) => acc + (r.tableCount ?? 0), 0)
  const sumOrDash = (key) => {
    const rows = data.filter((r) => r[key] !== null && r[key] !== undefined)
    return rows.length ? formatNumber(rows.reduce((acc, r) => acc + r[key], 0)) : '—'
  }
  return ['合计', '', formatNumber(sumTables), sumOrDash('totalRows'), sumOrDash('totalColumns')]
}

onMounted(loadTags)

// 页签切换走 keep-alive:回来时刷新标记列表与当前统计
onActivated(() => {
  loadTags()
  loadStats()
})
</script>

<style scoped>
.tag-stats {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}
.tag-panel {
  width: 260px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.tag-panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px 6px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
  border-radius: 8px 8px 0 0;
}
.tag-list {
  min-height: 120px;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding: 6px;
}
.tag-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s;
}
.tag-item:hover {
  background: var(--el-fill-color-light);
}
.tag-item.active {
  background: var(--el-color-primary-light-9);
}
.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.tag-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  flex-shrink: 0;
}
/* 操作按钮默认隐藏,悬停整行时显示,避免列表常显拥挤 */
.tag-actions {
  display: none;
  flex-shrink: 0;
}
.tag-item:hover .tag-actions {
  display: inline-flex;
}
.tag-actions .el-button + .el-button {
  margin-left: 2px;
}
/* 新建/编辑内联表单行 */
.tag-form-row {
  cursor: default;
  gap: 6px;
}
.tag-form-row .el-input {
  flex: 1;
  min-width: 0;
}
.stats-panel {
  flex: 1;
  min-width: 0;
}
.metric-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.metric-card {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.metric-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.metric-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.metric-note {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
