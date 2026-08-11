<template>
  <div class="page-card">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div>
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <!-- 未扫描提示:仅结构元数据,无统计列 -->
    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      该表尚未扫描,以下为数据库元数据中的字段结构与索引结构,不含空值与有值率统计。
      在表列表勾选该表后「开始扫描」,扫描完成即可查看字段级统计。
    </el-alert>

    <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
      <el-input v-model="keyword" placeholder="按字段名或注释搜索" clearable style="width: 280px" />
      <span>字段数量: {{ columns.length }}</span>
      <span>索引数量: {{ indexes.length }}</span>
    </div>

    <el-table :data="filteredColumns" v-loading="loading" border>
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="name" label="字段名" min-width="140" sortable show-overflow-tooltip />
      <el-table-column prop="comment" label="注释" min-width="140" sortable show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.comment">{{ row.comment }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="displayType" label="类型" width="150" sortable show-overflow-tooltip />
      <el-table-column label="键" width="70" sortable :sort-method="(a, b) => keyLabel(a).localeCompare(keyLabel(b))">
        <template #default="{ row }">
          <el-tag v-if="keyLabel(row)" size="small" :type="keyLabel(row) === 'PK' ? 'primary' : 'success'">{{ keyLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可空" width="70" sortable :sort-method="(a, b) => Number(a.nullable ?? true) - Number(b.nullable ?? true)">
        <template #default="{ row }">
          <span v-if="row.nullable === null || row.nullable === undefined">-</span>
          <span v-else>{{ row.nullable ? '是' : '否' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="defaultValue" label="默认值" width="110" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.defaultValue !== null && row.defaultValue !== undefined && row.defaultValue !== ''">{{ row.defaultValue }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 索引结构:索引名 / 唯一性 / 索引列 -->
    <div style="display: flex; align-items: baseline; gap: 12px; margin: 20px 0 12px">
      <h4 style="margin: 0">索引结构</h4>
      <span style="color: var(--el-text-color-secondary)">共 {{ indexes.length }} 个</span>
    </div>
    <el-table :data="indexes" v-loading="loading" border size="small">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="name" label="索引名" min-width="180" show-overflow-tooltip />
      <el-table-column label="唯一" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.unique" type="success" size="small">唯一</el-tag>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="索引列" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="c in row.columns" :key="c" size="small" style="margin: 0 4px 2px 0">{{ c }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import request from '../api'
import Breadcrumb from '../components/Breadcrumb.vue'
import { getDsName } from '../stores/tabs'

const route = useRoute()
const dsId = route.params.id
const schema = route.params.schema
const tableName = route.params.tableName
const db = route.query.db || ''

const columns = ref([])
const indexes = ref([])
const loading = ref(false)
const refreshing = ref(false)
const keyword = ref('')

const filteredColumns = computed(() => {
  let list = columns.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((c) =>
      c.name.toLowerCase().includes(kw) || (c.comment || '').toLowerCase().includes(kw))
  }
  return list
})

// 键约束展示:PK / UNI / 空(与扫描字段页口径一致)
function keyLabel(row) {
  if (row.primaryKey) return 'PK'
  if (row.uniqueIndexFirst) return 'UNI'
  return ''
}

async function load(refresh = false) {
  loading.value = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    // refresh=true 时字段/索引强制从业务库拉最新结构并覆盖本地缓存
    const params = new URLSearchParams()
    if (db) params.set('db', db)
    if (refresh) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`
    // 索引查询失败(部分方言/驱动不支持 getIndexInfo)不阻塞页面,字段照常展示
    const [cols, idx] = await Promise.all([
      request.get(`${url}/columns${q}`),
      request.get(`${url}/indexes${q}`).catch(() => [])
    ])
    columns.value = cols
    indexes.value = idx || []
  } finally {
    loading.value = false
  }
}

/** 手动刷新:从业务库拉最新字段/索引结构并覆盖本地缓存 */
async function refreshAll() {
  refreshing.value = true
  try {
    await load(true)
    ElMessage.success('已从数据源刷新结构缓存')
  } finally {
    refreshing.value = false
  }
}

// ---------- 面包屑 ----------
const dsName = computed(() => getDsName(dsId) || `数据源 ${dsId}`)
const schemaLabel = computed(() => (db ? `${db}.${schema}` : schema))
const tablesPath = computed(() => {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables`
  return db ? `${base}?db=${encodeURIComponent(db)}` : base
})
const breadcrumbItems = computed(() => [
  { label: '数据源列表', to: '/datasources' },
  { label: dsName.value, to: `/datasources/${dsId}/schemas` },
  { label: schemaLabel.value, to: tablesPath.value },
  { label: tableName }
])

onMounted(load)
</script>
