<template>
  <!-- 选表面板:库(仅多库方言)→ schema → 带复选框的表清单(英文名/中文名/描述,挂载/添加关系表场景加「关系」下拉列),支持过滤多选;
       描述取自 AI 表说明(table-doc)。挂载表/添加关系表两个对话框共用;fixedDb/fixedSchema 锁定库/schema(添加关系表场景) -->
  <el-form-item v-if="isMultiDb" label="数据库">
    <el-select v-model="db" filterable placeholder="选择数据库" style="width: 100%"
               :loading="dbLoading" :disabled="fixed" @change="onDbChange">
      <el-option v-for="d in databases" :key="d" :value="d" :label="d" />
    </el-select>
  </el-form-item>
  <el-form-item label="schema">
    <el-select v-model="schema" filterable placeholder="选择库/schema" style="width: 100%"
               :loading="schemaLoading" :disabled="fixed || (isMultiDb && !db)" @change="onSchemaChange">
      <el-option v-for="s in schemas" :key="s" :value="s" :label="s" />
    </el-select>
  </el-form-item>
  <el-form-item :label="listLabel">
    <div class="otpl-list">
      <el-input v-model="keyword" size="small" clearable :prefix-icon="Search"
                placeholder="按表名/注释/描述过滤" :disabled="!schema" />
      <el-table :data="filtered" size="small" border :height="tableHeight" v-loading="tableLoading"
                :empty-text="schema ? '该 schema 下没有表' : '请先选择 schema'"
                @selection-change="(rows) => (selected = rows)">
        <el-table-column type="selection" width="38" />
        <el-table-column prop="name" label="英文名" min-width="170" show-overflow-tooltip />
        <el-table-column prop="comment" label="中文名" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.comment || '—' }}</template>
        </el-table-column>
        <!-- 关系列:仅挂载表场景(showRelKind)展示;逐表选择挂载表与目录的关系,默认空,在目录图连线上展示 -->
        <el-table-column v-if="showRelKind" label="关系" width="96" align="center">
          <template #default="{ row }">
            <el-select v-model="relKinds[row.name]" size="small" clearable placeholder="—">
              <el-option label="包含" value="INCLUDE" />
              <el-option label="关联" value="ASSOC" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
      </el-table>
      <div class="otpl-foot">共 {{ filtered.length }} 张,已选 {{ selected.length }} 张</div>
    </div>
  </el-form-item>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import request from '../api'

// 数据源由父级固定传入;selected/db/schema 通过 defineExpose 给父级取,reset() 在对话框打开时调用
const props = defineProps({
  datasource: { type: Object, required: true },
  // 清单列标题(「表」/「关系表」)
  listLabel: { type: String, default: '表' },
  // 从清单中排除的表名(如添加关系表时排除挂载表自身,避免提交后被后端 400)
  excludeTable: { type: String, default: '' },
  // 是否展示「关系」列(挂载表场景:逐表选择挂载表与目录的关系,随挂载提交)
  showRelKind: { type: Boolean, default: false },
  // 表清单高度(弹窗加高时由父级调大)
  tableHeight: { type: [String, Number], default: 300 },
  // 锁定库/schema(添加关系表场景:关系表与挂载表同 schema,自动选中且不可改,打开即直接拉表清单)
  fixedDb: { type: String, default: '' },
  fixedSchema: { type: String, default: '' }
})

const fixed = computed(() => !!props.fixedSchema)

const isMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(props.datasource?.dbType))

const db = ref('')
const schema = ref('')
const keyword = ref('')
const databases = ref([])
const schemas = ref([])
const tables = ref([])
const selected = ref([])
// 逐表关系选择:表名 → INCLUDE/ASSOC(仅 showRelKind 时使用,默认空)
const relKinds = ref({})
const dbLoading = ref(false)
const schemaLoading = ref(false)
const tableLoading = ref(false)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return tables.value
  return tables.value.filter((t) =>
    t.name.toLowerCase().includes(kw) ||
    (t.comment || '').toLowerCase().includes(kw) ||
    (t.description || '').toLowerCase().includes(kw))
})

async function loadDatabases() {
  dbLoading.value = true
  try {
    databases.value = await request.get(`/datasources/${props.datasource.id}/databases`).catch(() => [])
  } finally {
    dbLoading.value = false
  }
}

async function loadSchemas() {
  schema.value = ''
  schemas.value = []
  tables.value = []
  selected.value = []
  if (isMultiDb.value && !db.value) return
  schemaLoading.value = true
  try {
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    schemas.value = await request.get(`/datasources/${props.datasource.id}/schemas${q}`).catch(() => [])
  } finally {
    schemaLoading.value = false
  }
}

/** 表清单 + AI 表说明合并:[{name, comment, description}];失败按空处理 */
async function loadTables() {
  tables.value = []
  selected.value = []
  keyword.value = ''
  relKinds.value = {}
  if (!schema.value) return
  tableLoading.value = true
  try {
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    const base = `/datasources/${props.datasource.id}/schemas/${encodeURIComponent(schema.value)}`
    const [list, docs] = await Promise.all([
      request.get(`${base}/tables${q}`).catch(() => []),
      request.get(`${base}/table-docs${q}`).catch(() => ({}))
    ])
    tables.value = (list || []).filter((t) => t && t.name && t.name !== props.excludeTable)
      .map((t) => ({ name: t.name, comment: t.comment || '', description: (docs || {})[t.name] || '' }))
  } finally {
    tableLoading.value = false
  }
}

function onDbChange() {
  loadSchemas()
}

function onSchemaChange() {
  loadTables()
}

/** 对话框打开时重置并按方言预拉库/schema 列表;锁定 schema 时直接以固定值拉表清单 */
function reset() {
  db.value = ''
  schema.value = ''
  keyword.value = ''
  databases.value = []
  schemas.value = []
  tables.value = []
  selected.value = []
  relKinds.value = {}
  if (fixed.value) {
    db.value = props.fixedDb
    schema.value = props.fixedSchema
    loadTables()
  } else if (isMultiDb.value) loadDatabases()
  else loadSchemas()
}

defineExpose({
  db,
  schema,
  // 已选表名数组
  selectedNames: computed(() => selected.value.map((t) => t.name)),
  // 已选表挂载项:[{tableName, relKind}](relKind 未选为 null;挂载弹窗提交用)
  selectedItems: computed(() => selected.value.map((t) => ({ tableName: t.name, relKind: relKinds.value[t.name] || null }))),
  reset
})
</script>

<style scoped>
/* 清单占满表单项宽度,过滤框与表格留间距 */
.otpl-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.otpl-foot {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: right;
}
</style>
