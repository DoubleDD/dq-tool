<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">库列表{{ dsName ? ` - ${dsName}` : '' }}</h3>
      <el-button @click="$router.push('/datasources')">返回数据源</el-button>
    </div>
    <div v-if="databases.length" style="margin-bottom: 12px">
      <span style="margin-right: 8px; color: #606266">数据库</span>
      <el-select v-model="currentDb" style="width: 240px" @change="loadSchemas">
        <el-option v-for="d in databases" :key="d" :label="d" :value="d" />
      </el-select>
    </div>
    <el-input v-model="keyword" placeholder="按库名搜索" clearable style="width: 280px; margin-bottom: 12px" />
    <el-table :data="filteredSchemas" v-loading="loading" border>
      <el-table-column prop="name" label="库名(Schema)" min-width="200">
        <template #default="{ row }">
          <el-link type="primary" @click="goTables(row.name)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="goTables(row.name)">查看表</el-button>
          <el-button link type="primary" @click="goScans(row.name)">扫描记录</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '../api'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schemas = ref([])
const dsName = ref('')
const databases = ref([])
const currentDb = ref('')
const loading = ref(false)
const keyword = ref('')

const filteredSchemas = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return schemas.value
  return schemas.value.filter((s) => s.name.toLowerCase().includes(kw))
})

/** 从 jdbcUrl 解析默认库(databaseName=/database=) */
function parseDefaultDb(jdbcUrl) {
  const m = /[;?&]database(?:Name)?=([^;?&]+)/i.exec(jdbcUrl || '')
  return m ? decodeURIComponent(m[1]) : ''
}

async function loadSchemas() {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  const schemaList = await request.get(`/datasources/${dsId}/schemas${q}`)
  schemas.value = (schemaList || []).map((name) => ({ name }))
}

async function load() {
  loading.value = true
  try {
    const dsList = await request.get('/datasources').catch(() => [])
    const ds = (dsList || []).find((d) => String(d.id) === String(dsId))
    dsName.value = ds ? ds.name : ''
    if (ds && ds.dbType === 'SQLSERVER') {
      databases.value = await request.get(`/datasources/${dsId}/databases`).catch(() => [])
      const fromUrl = parseDefaultDb(ds.jdbcUrl)
      currentDb.value = databases.value.includes(fromUrl) ? fromUrl : (databases.value[0] || '')
    }
    await loadSchemas()
  } finally {
    loading.value = false
  }
}

function goTables(schema) {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables${q}`)
}

function goScans(schema) {
  const q = currentDb.value ? `?db=${encodeURIComponent(currentDb.value)}` : ''
  router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/scans${q}`)
}

onMounted(load)
</script>
