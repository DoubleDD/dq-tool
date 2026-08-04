<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">表列表 - {{ db ? db + '.' + schema : schema }}</h3>
      <div>
        <el-button @click="$router.push(`/datasources/${dsId}/schemas`)">返回</el-button>
        <el-button type="primary" @click="openScanDialog">开始扫描</el-button>
      </div>
    </div>

    <el-input v-model="keyword" placeholder="按表名或注释搜索" clearable style="width: 280px; margin-bottom: 12px" />

    <el-table :data="filteredTables" v-loading="loading" border @selection-change="onSelectionChange">
      <el-table-column type="selection" width="45" />
      <el-table-column prop="name" label="表名" min-width="180" sortable show-overflow-tooltip />
      <el-table-column prop="comment" label="注释" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.comment">{{ row.comment }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="storageInfo" label="引擎/表空间" width="130">
        <template #default="{ row }">
          <span v-if="row.storageInfo">{{ row.storageInfo }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="estRows" label="约行数" width="140" sortable>
        <template #default="{ row }">
          <span v-if="row.estRows !== null && row.estRows !== undefined">约 {{ formatNumber(row.estRows) }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="sizeBytes" label="总大小" width="140" sortable>
        <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="scanDialogVisible" title="开始扫描" width="640px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="扫描范围">
          <span v-if="selectedTables.length">已选 {{ selectedTables.length }} 张表</span>
          <span v-else>未选择表,将扫描全库</span>
        </el-form-item>
        <el-form-item label="强制全量">
          <el-switch v-model="scanForm.forceFull" />
          <div class="form-tip">超过阈值的表不做采样,逐行精确统计</div>
        </el-form-item>
        <el-form-item label="空值规则">
          <div style="width: 100%">
            <div v-for="(rule, idx) in scanForm.nullRules" :key="idx" class="rule-row">
              <el-input v-model="rule.column" placeholder="列名(* 表示所有列)" style="width: 180px" />
              <el-input v-model="rule.valuesText" placeholder="视为空的取值,逗号分隔" style="flex: 1" />
              <el-button link type="danger" @click="scanForm.nullRules.splice(idx, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="scanForm.nullRules.push({ column: '', valuesText: '' })">
              + 添加规则
            </el-button>
            <div class="form-tip">例如:列名 *,取值 0,-1 表示所有列中值为 0 或 -1 的也视为空</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scanDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitScan">提交扫描</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'
import { formatBytes, formatNumber } from '../utils/format'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schema = route.params.schema
const db = route.query.db || ''

const tables = ref([])
const loading = ref(false)
const keyword = ref('')
const selectedTables = ref([])

const scanDialogVisible = ref(false)
const submitting = ref(false)
const scanForm = reactive({ forceFull: false, nullRules: [] })

const filteredTables = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return tables.value
  return tables.value.filter((t) =>
    t.name.toLowerCase().includes(kw) || (t.comment || '').toLowerCase().includes(kw))
})

async function load() {
  loading.value = true
  try {
    tables.value = await request.get(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables${dbQuery()}`)
  } finally {
    loading.value = false
  }
}

function dbQuery() {
  return db ? `?db=${encodeURIComponent(db)}` : ''
}

function onSelectionChange(rows) {
  selectedTables.value = rows
}

function openScanDialog() {
  scanForm.forceFull = false
  scanForm.nullRules = []
  scanDialogVisible.value = true
}

async function submitScan() {
  const nullRules = scanForm.nullRules
    .filter((r) => r.column.trim() && r.valuesText.trim())
    .map((r) => ({
      column: r.column.trim(),
      values: r.valuesText.split(',').map((v) => v.trim()).filter(Boolean)
    }))
    .filter((r) => r.values.length > 0)

  submitting.value = true
  try {
    const res = await request.post('/scans', {
      datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
      schema,
      database: db || null,
      tables: selectedTables.value.length ? selectedTables.value.map((t) => t.name) : null,
      forceFull: scanForm.forceFull,
      nullRules
    })
    ElMessage.success('扫描任务已提交')
    scanDialogVisible.value = false
    // 带上库名标签,供页签标题展示
    const schemaLabel = db ? `${db}.${schema}` : schema
    router.push(`/scans/${res.jobId}?schema=${encodeURIComponent(schemaLabel)}`)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.rule-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}
</style>
