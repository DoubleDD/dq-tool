<template>
  <!-- 关系批量处理对话框:整库关系列表(默认仅候选)+ 多选批量确认/否决/删除,
       补足 ER 图上逐条点边确认/否决无法批量操作的短板;
       操作成功后 emit done 由父级刷新图数据(ER 关系页与字段明细页 ER 页签共用) -->
  <el-dialog
    :model-value="modelValue"
    title="关系批量处理"
    width="80%"
    style="min-width: 1180px"
    top="6vh"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @open="loadList(true)"
  >
    <!-- 筛选行:状态(默认仅候选,选项带计数)+ 表名关键字(两端任一命中,前端过滤不回源) -->
    <div class="rb-filter">
      <el-select v-model="statusFilter" style="width: 150px">
        <el-option v-for="opt in statusOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
      </el-select>
      <el-input v-model="tableFilter" placeholder="按表名过滤(两端任一命中)" clearable style="width: 230px" />
      <span class="rb-count">共 {{ filteredRows.length }} 条</span>
      <span class="rb-flex" />
      <el-button link type="primary" :disabled="!filteredRows.length" @click="toggleSelectAll">
        {{ allSelected ? '取消全选' : '全选筛选结果' }}
      </el-button>
    </div>

    <!-- 批量操作条:随选中数联动;删除带确认弹窗,且确认/否决关系后端静默跳过 -->
    <div class="rb-bar">
      <span>已选 {{ selection.length }} 条</span>
      <el-button size="small" type="primary" :disabled="!selection.length" :loading="opLoading" @click="doBatch('confirm')">
        批量确认
      </el-button>
      <el-button size="small" type="warning" :disabled="!selection.length" :loading="opLoading" @click="doBatch('reject')">
        批量否决
      </el-button>
      <el-button size="small" type="danger" :disabled="!selection.length" :loading="opLoading" @click="doBatch('delete')">
        批量删除
      </el-button>
      <span class="rb-tip">删除不限状态,误删可重新推导找回;否决的关系再次推导命中时会重新验证</span>
    </div>

    <!-- 各列定宽(备注列 min-width 弹性),合计超出弹窗宽度时表格原生横向滚动,不挤压不截断 -->
    <el-table
      ref="tableRef"
      :data="filteredRows"
      v-loading="loading"
      size="small"
      row-key="id"
      max-height="460"
      empty-text="没有符合条件的关系记录"
      @selection-change="(rows) => (selection = rows)"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column label="一端(唯一侧)" width="250" show-overflow-tooltip>
        <template #default="{ row }">{{ row.oneTable }}.{{ row.oneColumn }}</template>
      </el-table-column>
      <!-- 多端表名:中文注释(加粗)+ 英文表名两行展示,不截断;注释取整库表清单(meta_table 缓存口径) -->
      <el-table-column label="多端表名" width="320">
        <template #default="{ row }">
          <div class="tn-cell">
            <div class="tn-cn">{{ commentOf(row.manyTable) || row.manyTable }}</div>
            <div v-if="commentOf(row.manyTable)" class="tn-en">{{ row.manyTable }}</div>
          </div>
        </template>
      </el-table-column>
      <!-- 多端字段:表名已在「多端表名」列展示,此处只显示字段名 -->
      <el-table-column label="多端字段" width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.manyColumn }}</template>
      </el-table-column>
      <el-table-column label="基数" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.cardinality === 'SUSPECT_MANY_TO_MANY' ? 'danger' : 'primary'">
            {{ cardinalityText(row.cardinality) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">{{ sourceText(row.source) }}</template>
      </el-table-column>
      <el-table-column label="备注" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.remark">{{ row.remark }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, nextTick } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import request, { batchConfirmRelations, batchDeleteRelations, batchRejectRelations, listRelations } from '../api'

// 关系批量处理(自成闭环):打开时拉整库关系清单(一次拉全状态,状态/表名过滤在前端做,切换不回源);
// 默认只看候选——推导扫出待人工确认的关系正是本对话框的主场景;批量操作成功后重拉清单并 emit done 让父级刷新图
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  dsId: { type: [String, Number], required: true },
  db: { type: String, default: '' },
  schema: { type: String, required: true },
  // 可选预填表名过滤(星型图入口带入当前表;可清空看整库)
  initialTable: { type: String, default: '' }
})

// done:批量操作成功 { action: confirm/reject/delete, updated: 实际影响数 },父级刷新图
const emit = defineEmits(['update:modelValue', 'done'])

const rows = ref([])
const loading = ref(false)
const opLoading = ref(false)
const statusFilter = ref('CANDIDATE')
const tableFilter = ref('')
const selection = ref([])
const tableRef = ref(null)
// 表名 -> 注释(含小写键兜底,忽略大小写命中);供「多端表名」列两行展示
const tableComments = ref({})

/** 表注释查询(忽略大小写兜底);无注释返回空串 */
function commentOf(table) {
  return tableComments.value[table] ?? tableComments.value[String(table).toLowerCase()] ?? ''
}

// 状态下拉(选项带各状态计数,"全部"不计入标签避免冗长)
const statusOptions = computed(() => {
  const count = (s) => rows.value.filter((r) => r.status === s).length
  return [
    { value: 'CANDIDATE', label: `仅候选 (${count('CANDIDATE')})` },
    { value: 'CONFIRMED', label: `已确认 (${count('CONFIRMED')})` },
    { value: 'REJECTED', label: `已否决 (${count('REJECTED')})` },
    { value: '', label: '全部状态' }
  ]
})

// 过滤:状态 + 表名关键字(忽略大小写,两端表任一命中)
const filteredRows = computed(() => {
  const kw = tableFilter.value.trim().toLowerCase()
  return rows.value.filter((r) => {
    if (statusFilter.value && r.status !== statusFilter.value) return false
    if (kw && !`${r.oneTable}\n${r.manyTable}`.toLowerCase().includes(kw)) return false
    return true
  })
})

const allSelected = computed(() => filteredRows.value.length > 0 && selection.value.length >= filteredRows.value.length)

/** 全选/取消全选筛选结果(无分页,所有行均已渲染,逐行 toggle 可靠) */
function toggleSelectAll() {
  if (allSelected.value) {
    tableRef.value?.clearSelection()
    return
  }
  tableRef.value?.clearSelection()
  for (const r of filteredRows.value) tableRef.value?.toggleRowSelection(r, true)
}

/** 拉关系清单;applyInitial=true(仅对话框每次打开)时把预填表名带入过滤,批量操作后的重拉保留用户当前过滤;
 *  打开时顺带拉整库表清单(meta_table 缓存口径)建注释 map 供「多端表名」列,失败不阻塞(表名照常显示) */
async function loadList(applyInitial = false) {
  loading.value = true
  try {
    const q = props.db ? `?db=${encodeURIComponent(props.db)}` : ''
    const tasks = [listRelations({ datasourceId: props.dsId, dbName: props.db || undefined, schemaName: props.schema })]
    if (applyInitial) {
      tasks.push(request
        .get(`/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}/tables${q}`)
        .catch(() => []))
    }
    const [list, tables] = await Promise.all(tasks)
    rows.value = list
    if (applyInitial) {
      tableFilter.value = props.initialTable || ''
      const map = {}
      for (const t of tables || []) {
        if (!t?.name) continue
        map[t.name] = t.comment || ''
        map[t.name.toLowerCase()] = t.comment || ''
      }
      tableComments.value = map
    }
    // 重拉后旧选中行已失效,清空避免误操作;等表格用新数据渲染完再清
    await nextTick()
    tableRef.value?.clearSelection()
  } finally {
    loading.value = false
  }
}

const BATCH_ACTIONS = {
  confirm: { fn: batchConfirmRelations, done: '已确认' },
  reject: { fn: batchRejectRelations, done: '已否决' },
  delete: { fn: batchDeleteRelations, done: '已删除' }
}

async function doBatch(action) {
  const ids = selection.value.map((r) => r.id)
  if (!ids.length) return
  // 删除是唯一不可逆操作:二次确认(误删可重新推导找回)
  if (action === 'delete') {
    try {
      await ElMessageBox.confirm(`将删除选中的 ${ids.length} 条关系(误删可重新推导找回),确定删除?`, '批量删除关系', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch { return }
  }
  opLoading.value = true
  try {
    const res = await BATCH_ACTIONS[action].fn(ids)
    const updated = res?.updated ?? 0
    ElMessage.success(`${BATCH_ACTIONS[action].done} ${updated} 条关系`)
    emit('done', { action, updated })
    await loadList()
  } finally {
    opLoading.value = false
  }
}

// 与 RelationEdgeDrawer 同款文案
function cardinalityText(c) {
  return { ONE_TO_ONE: '1:1', ONE_TO_MANY: '1:N', SUSPECT_MANY_TO_MANY: 'M:N(疑似)' }[c] || c
}
function statusText(s) {
  return { CANDIDATE: '候选', CONFIRMED: '确认', REJECTED: '否决' }[s] || s
}
function statusTagType(s) {
  return { CANDIDATE: 'warning', CONFIRMED: 'success', REJECTED: 'info' }[s] || 'info'
}
function sourceText(s) {
  return { NAME_MATCH: '名字匹配', SEMANTIC: '语义匹配', MANUAL: '人工补充' }[s] || s
}
</script>

<style scoped>
.rb-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.rb-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rb-flex {
  flex: 1;
}
.rb-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.rb-bar :deep(.el-button) {
  margin-left: 0;
}
.rb-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 多端表名两行:中文注释加粗为主行,英文表名次级色为副行;均不换行,列定宽 + 表格横向滚动保证不截断 */
.tn-cell {
  line-height: 1.4;
}
.tn-cn {
  font-weight: 600;
  white-space: nowrap;
}
.tn-en {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
</style>
