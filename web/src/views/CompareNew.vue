<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">新建比对任务</h3>
      <div class="toolbar-actions">
        <el-button @click="router.push('/compare')">返回列表</el-button>
      </div>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom: 16px">
      <template #title>
        选定基准表与关注字段,再指定要比对的系统;任务将按比对主键逐字段对齐比对
      </template>
    </el-alert>
    <el-steps :active="step" align-center style="margin-bottom: 24px">
      <el-step title="选择基准表" />
      <el-step title="选择比对字段" />
      <el-step title="选择比对系统并确认" />
    </el-steps>

    <!-- 步骤 1:选择基准表 -->
    <div v-show="step === 0" class="step-body">
      <el-form label-width="90px" style="max-width: 640px">
        <el-form-item label="任务名称">
          <el-input v-model="form.name" placeholder="如:客户主数据三系统比对" maxlength="100" />
        </el-form-item>
        <el-form-item label="数据源">
          <DatasourceSelect v-model="form.datasourceId" :datasources="datasources" style="width: 100%" @change="onBaseDsChange" />
        </el-form-item>
        <el-form-item v-if="baseMultiDb" label="数据库">
          <el-select v-model="form.db" filterable placeholder="选择数据库" style="width: 100%" :loading="baseDbLoading" @change="onBaseDbChange">
            <el-option v-for="d in baseDatabases" :key="d" :value="d" :label="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="库/schema">
          <el-select v-model="form.schema" filterable placeholder="选择库/schema" style="width: 100%"
                     :loading="baseSchemaLoading" :disabled="baseMultiDb && !form.db" @change="onBaseSchemaChange">
            <el-option v-for="s in baseSchemas" :key="s" :value="s" :label="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="基准表">
          <el-select v-model="form.table" filterable placeholder="选择基准表" style="width: 100%"
                     :loading="baseTableLoading" :disabled="!form.schema" @change="onBaseTableChange">
            <el-option v-for="t in baseTables" :key="t.name" :value="t.name" :label="t.comment ? `${t.name}(${t.comment})` : t.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="step-tip">基准表为权威数据,其他系统的数据将按比对主键逐字段对齐到该表</div>
    </div>

    <!-- 步骤 2:选择比对字段 -->
    <div v-show="step === 1" class="step-body">
      <el-table ref="fieldTableRef" :data="columns" v-loading="columnsLoading" border row-key="name"
                max-height="440" @selection-change="onFieldSelectionChange">
        <el-table-column type="selection" width="45" :selectable="(row) => row.name !== keyField" />
        <el-table-column label="比对主键" width="90" align="center">
          <template #default="{ row }">
            <el-radio v-model="keyField" :value="row.name" @change="onKeyChange">{{ '' }}</el-radio>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="字段名" min-width="170" show-overflow-tooltip />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <el-tag size="small" type="info" plain>{{ row.displayType || row.typeName || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="comment" label="说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.comment || '—' }}</template>
        </el-table-column>
      </el-table>
      <div class="step-tip">
        已选 {{ selectedNames.length }} 个字段(共 {{ columns.length }} 个);比对主键必选且不可取消;未勾选的字段不参与比对
      </div>
    </div>

    <!-- 步骤 3:选择比对系统并确认 -->
    <div v-show="step === 2" class="step-body">
      <div class="step3-layout">
        <div class="step3-targets">
          <div v-for="(t, i) in targets" :key="i" class="target-row">
            <span class="target-index">{{ i + 1 }}</span>
            <el-select v-model="t.datasourceId" filterable placeholder="数据源" style="width: 200px" @change="onTargetDsChange(t)">
              <el-option v-for="ds in targetDatasourceOptions" :key="ds.id" :value="String(ds.id)" :label="ds.name" />
            </el-select>
            <el-select v-if="targetMultiDb(t)" v-model="t.db" filterable placeholder="数据库" style="width: 150px"
                       :loading="t.dbLoading" @change="onTargetDbChange(t)">
              <el-option v-for="d in t.databases" :key="d" :value="d" :label="d" />
            </el-select>
            <el-select v-model="t.schema" filterable placeholder="库/schema" style="width: 150px"
                       :loading="t.schemaLoading" :disabled="targetMultiDb(t) && !t.db" @change="onTargetSchemaChange(t)">
              <el-option v-for="s in t.schemas" :key="s" :value="s" :label="s" />
            </el-select>
            <el-select v-model="t.table" filterable placeholder="表" style="width: 220px"
                       :loading="t.tableLoading" :disabled="!t.schema">
              <el-option v-for="tb in t.tables" :key="tb.name" :value="tb.name" :label="tb.comment ? `${tb.name}(${tb.comment})` : tb.name" />
            </el-select>
            <el-button link type="danger" :disabled="targets.length <= 1" @click="targets.splice(i, 1)">删除</el-button>
          </div>
          <el-button size="small" plain type="primary" @click="addTarget">+ 添加比对系统</el-button>
        </div>
        <!-- 任务摘要:提交前最终确认 -->
        <el-descriptions class="step3-summary" :column="1" border size="small" title="任务摘要">
          <el-descriptions-item label="任务名称">{{ form.name }}</el-descriptions-item>
          <el-descriptions-item label="基准数据源">{{ baseDs?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="基准表">{{ baseTableLabel }}</el-descriptions-item>
          <el-descriptions-item label="比对主键">{{ keyField }}</el-descriptions-item>
          <el-descriptions-item label="比对字段">{{ selectedNames.length }} 个:{{ selectedNames.join('、') }}</el-descriptions-item>
          <el-descriptions-item label="比对系统">
            <div v-for="(t, i) in targets" :key="i">{{ targetLabel(t) || `目标 ${i + 1}(未选完)` }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="任务类型">长时任务</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <!-- 向导操作按钮 -->
    <div class="wizard-actions">
      <el-button :disabled="step === 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 2" type="primary" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="submitting" @click="submit">开始比对</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '../utils/notify'
import request, { createCompareJob } from '../api'
import DatasourceSelect from '../components/DatasourceSelect.vue'

const router = useRouter()

const step = ref(0)
const datasources = ref([])

// ---------- 步骤 1:基准表 ----------

const form = reactive({ name: '', datasourceId: '', db: '', schema: '', table: '' })
const baseDatabases = ref([])
const baseSchemas = ref([])
const baseTables = ref([])
const baseDbLoading = ref(false)
const baseSchemaLoading = ref(false)
const baseTableLoading = ref(false)

const baseDs = computed(() => datasources.value.find((d) => String(d.id) === String(form.datasourceId)))
// 多库方言(SQL Server/Kingbase)需要额外选数据库,与对象管理选表组件同一判定
const baseMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(baseDs.value?.dbType))

const baseTableLabel = computed(() => {
  if (!form.table) return '-'
  const schemaPart = form.db ? `${form.db}.${form.schema}` : form.schema
  return schemaPart ? `${schemaPart}.${form.table}` : form.table
})

async function onBaseDsChange() {
  form.db = ''
  form.schema = ''
  form.table = ''
  baseDatabases.value = []
  baseSchemas.value = []
  baseTables.value = []
  if (!form.datasourceId) return
  if (baseMultiDb.value) {
    baseDbLoading.value = true
    try {
      baseDatabases.value = await request.get(`/datasources/${form.datasourceId}/databases`).catch(() => [])
    } finally {
      baseDbLoading.value = false
    }
  } else {
    loadBaseSchemas()
  }
}

async function loadBaseSchemas() {
  baseSchemaLoading.value = true
  try {
    const q = form.db ? `?db=${encodeURIComponent(form.db)}` : ''
    baseSchemas.value = await request.get(`/datasources/${form.datasourceId}/schemas${q}`).catch(() => [])
  } finally {
    baseSchemaLoading.value = false
  }
}

function onBaseDbChange() {
  form.schema = ''
  form.table = ''
  baseSchemas.value = []
  baseTables.value = []
  if (form.db) loadBaseSchemas()
}

async function onBaseSchemaChange() {
  form.table = ''
  baseTables.value = []
  if (!form.schema) return
  baseTableLoading.value = true
  try {
    const q = form.db ? `?db=${encodeURIComponent(form.db)}` : ''
    baseTables.value = await request.get(
      `/datasources/${form.datasourceId}/schemas/${encodeURIComponent(form.schema)}/tables${q}`
    ).catch(() => [])
  } finally {
    baseTableLoading.value = false
  }
}

/** 选定基准表后:名称为空时给个默认名,减少手工输入 */
function onBaseTableChange() {
  if (!form.name && form.table) form.name = `${form.table} 数据比对`
}

// ---------- 步骤 2:比对字段 ----------

const columns = ref([])
const columnsLoading = ref(false)
const keyField = ref('')
const selectedNames = ref([])
const fieldTableRef = ref()

async function loadColumns() {
  columnsLoading.value = true
  columns.value = []
  keyField.value = ''
  selectedNames.value = []
  try {
    const q = form.db ? `?db=${encodeURIComponent(form.db)}` : ''
    const list = await request.get(
      `/datasources/${form.datasourceId}/schemas/${encodeURIComponent(form.schema)}/tables/${encodeURIComponent(form.table)}/columns${q}`
    )
    columns.value = list || []
    // 默认比对主键:主键列中 pkSeq 最小的;无主键则取第一列
    const pk = [...columns.value].filter((c) => c.primaryKey).sort((a, b) => (a.pkSeq || 0) - (b.pkSeq || 0))
    keyField.value = (pk[0] || columns.value[0])?.name || ''
    // 默认全选(主键强制勾选且禁取消)
    selectedNames.value = columns.value.map((c) => c.name)
    await nextTick()
    syncFieldSelection()
  } catch {
    ElMessage.error('字段列表加载失败,请返回上一步重试')
  } finally {
    columnsLoading.value = false
  }
}

/** 把 selectedNames 同步到表格勾选态(主键行禁取消,始终强制勾上) */
function syncFieldSelection() {
  const table = fieldTableRef.value
  if (!table) return
  table.clearSelection()
  const want = new Set([...selectedNames.value, keyField.value])
  for (const c of columns.value) {
    if (want.has(c.name)) table.toggleRowSelection(c, true)
  }
}

function onFieldSelectionChange(rows) {
  selectedNames.value = rows.map((r) => r.name)
}

/** 换比对主键:新主键强制勾选(旧主键保持勾选态,用户可自行取消) */
async function onKeyChange() {
  if (keyField.value && !selectedNames.value.includes(keyField.value)) {
    selectedNames.value = [...selectedNames.value, keyField.value]
  }
  await nextTick()
  syncFieldSelection()
}

// ---------- 步骤 3:比对系统 ----------

// 行结构:{ datasourceId, db, schema, table, databases[], schemas[], tables[], dbLoading, schemaLoading, tableLoading }
const targets = ref([])
const submitting = ref(false)

// 目标数据源排除基准数据源(与自己比对无意义)
const targetDatasourceOptions = computed(() =>
  datasources.value.filter((d) => String(d.id) !== String(form.datasourceId))
)

function targetDs(t) {
  return datasources.value.find((d) => String(d.id) === String(t.datasourceId))
}

function targetMultiDb(t) {
  return ['SQLSERVER', 'KINGBASE'].includes(targetDs(t)?.dbType)
}

function targetLabel(t) {
  const ds = targetDs(t)
  if (!ds || !t.table) return ''
  const schemaPart = t.db ? `${t.db}.${t.schema}` : t.schema
  return `${ds.name} · ${schemaPart ? schemaPart + '.' : ''}${t.table}`
}

function addTarget() {
  targets.value.push({
    datasourceId: '', db: '', schema: '', table: '',
    databases: [], schemas: [], tables: [],
    dbLoading: false, schemaLoading: false, tableLoading: false
  })
}

async function onTargetDsChange(t) {
  t.db = ''
  t.schema = ''
  t.table = ''
  t.databases = []
  t.schemas = []
  t.tables = []
  if (!t.datasourceId) return
  if (targetMultiDb(t)) {
    t.dbLoading = true
    try {
      t.databases = await request.get(`/datasources/${t.datasourceId}/databases`).catch(() => [])
    } finally {
      t.dbLoading = false
    }
  } else {
    loadTargetSchemas(t)
  }
}

async function loadTargetSchemas(t) {
  t.schemaLoading = true
  try {
    const q = t.db ? `?db=${encodeURIComponent(t.db)}` : ''
    t.schemas = await request.get(`/datasources/${t.datasourceId}/schemas${q}`).catch(() => [])
  } finally {
    t.schemaLoading = false
  }
}

function onTargetDbChange(t) {
  t.schema = ''
  t.table = ''
  t.schemas = []
  t.tables = []
  if (t.db) loadTargetSchemas(t)
}

/** 目标表清单拉到后:存在与基准表同名的表则默认选中(最常见情形) */
async function onTargetSchemaChange(t) {
  t.table = ''
  t.tables = []
  if (!t.schema) return
  t.tableLoading = true
  try {
    const q = t.db ? `?db=${encodeURIComponent(t.db)}` : ''
    t.tables = await request.get(
      `/datasources/${t.datasourceId}/schemas/${encodeURIComponent(t.schema)}/tables${q}`
    ).catch(() => [])
    if (t.tables.some((tb) => tb.name === form.table)) t.table = form.table
  } finally {
    t.tableLoading = false
  }
}

// ---------- 向导流转与提交 ----------

function next() {
  if (step.value === 0) {
    if (!form.name.trim()) return ElMessage.warning('请填写任务名称')
    if (!form.datasourceId) return ElMessage.warning('请选择基准数据源')
    if (baseMultiDb.value && !form.db) return ElMessage.warning('请选择数据库')
    if (!form.schema) return ElMessage.warning('请选择库/schema')
    if (!form.table) return ElMessage.warning('请选择基准表')
    step.value = 1
    loadColumns()
    return
  }
  if (step.value === 1) {
    if (!columns.value.length) return ElMessage.warning('基准表字段未加载,无法继续')
    if (!keyField.value) return ElMessage.warning('请选择比对主键')
    if (!selectedNames.value.length) return ElMessage.warning('请至少勾选 1 个比对字段')
    if (!targets.value.length) addTarget()
    step.value = 2
  }
}

async function submit() {
  const validTargets = targets.value.filter((t) => t.datasourceId && t.schema && t.table && (!targetMultiDb(t) || t.db))
  if (!validTargets.length) return ElMessage.warning('请至少添加 1 个完整的比对系统(数据源 + 库/schema + 表)')
  if (validTargets.length < targets.value.length) return ElMessage.warning('存在未选完整的比对系统,请补全或删除')
  submitting.value = true
  try {
    // 比对字段保持基准表字段顺序,主键兜底包含
    const fields = columns.value.map((c) => c.name).filter((n) => selectedNames.value.includes(n) || n === keyField.value)
    const res = await createCompareJob({
      name: form.name.trim(),
      baseDatasourceId: Number(form.datasourceId),
      baseDb: form.db || null,
      baseSchema: form.schema,
      baseTable: form.table,
      keyField: keyField.value,
      fields,
      targets: validTargets.map((t) => ({
        datasourceId: Number(t.datasourceId),
        db: t.db || null,
        schema: t.schema,
        table: t.table
      }))
    })
    ElMessage.success(`比对任务 T-${res.jobId} 已创建,开始执行`)
    router.push('/compare')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    datasources.value = await request.get('/datasources') || []
  } catch { /* 拦截器已提示 */ }
})
</script>

<style scoped>
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}

.step-body {
  min-height: 300px;
}

.step-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 步骤 3:左侧目标系统行列表 + 右侧任务摘要卡 */
.step3-layout {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}
.step3-targets {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.target-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.target-index {
  flex: none;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
  font-size: 12px;
}
.step3-summary {
  flex: none;
  width: 420px;
}

.wizard-actions {
  margin-top: 24px;
  display: flex;
  justify-content: center;
  gap: 12px;
}
.wizard-actions :deep(.el-button) {
  margin-left: 0;
}
</style>
