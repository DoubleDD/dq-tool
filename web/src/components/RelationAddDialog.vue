<template>
  <!-- 手动补充关系对话框:选两端表+字段、基数(1:N 时指定哪端为「一」)→ POST /relations(source=MANUAL 直接 CONFIRMED);表详情入口传 lockedTable 锁定端一为当前表 -->
  <el-dialog
    :model-value="modelValue"
    title="手动补充关系"
    width="560px"
    :close-on-press-escape="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <el-form label-width="90px">
      <el-form-item v-for="side in ['a', 'b']" :key="side" :label="side === 'a' ? '端一' : '端二'">
        <div class="side-row">
          <el-select
            v-model="form[side].table"
            filterable
            placeholder="选择表"
            style="width: 46%"
            :disabled="side === 'a' && !!lockedTable"
            @change="onTableChange(side)"
          >
            <el-option v-for="t in sideTableOptions(side)" :key="t" :value="t" :label="t" />
          </el-select>
          <el-select
            v-model="form[side].column"
            filterable
            placeholder="选择字段"
            style="width: 46%"
            :loading="form[side].columnsLoading"
            :disabled="!form[side].table"
          >
            <el-option v-for="c in form[side].columns" :key="c.name" :value="c.name" :label="c.name">
              <span>{{ c.name }}</span>
              <span style="float:right;color:var(--el-text-color-secondary);font-size:12px">
                {{ c.displayType || '' }}{{ c.comment ? ` · ${c.comment}` : '' }}
              </span>
            </el-option>
          </el-select>
        </div>
      </el-form-item>
      <el-form-item label="基数">
        <el-radio-group v-model="form.cardinality">
          <el-radio-button value="ONE_TO_ONE">一对一 (1:1)</el-radio-button>
          <el-radio-button value="ONE_TO_MANY">一对多 (1:N)</el-radio-button>
        </el-radio-group>
        <template v-if="form.cardinality === 'ONE_TO_MANY'">
          <span class="form-tip" style="margin: 0 8px">「一」侧:</span>
          <el-radio-group v-model="form.oneSide">
            <el-radio-button value="a">端一</el-radio-button>
            <el-radio-button value="b">端二</el-radio-button>
          </el-radio-group>
        </template>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" placeholder="可选,如补充依据" maxlength="200" />
      </el-form-item>
      <el-alert v-if="formError" type="error" :closable="false" :title="formError" show-icon />
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import request, { addRelation } from '../api'

// 手动补充关系(自成闭环):表清单由父级传入(当前图节点,全库总图=整库表);
// 字段清单按表懒拉(与 RelationInferDialog 同款字段 API,会话内按表缓存);
// lockedTable 非空时端一锁定为该表(表详情入口),带入并禁改;端二选项排除端一已选表(不可自己关联自己);
// 命中唯一键的已存在关系后端转 CONFIRMED 返回原 id(existing=true),提示「已存在,已转为确认」
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  dsId: { type: [String, Number], required: true },
  db: { type: String, default: '' },
  schema: { type: String, required: true },
  // 可选表清单(图节点表名)
  tables: { type: Array, default: () => [] },
  // 锁定端一为指定表(表详情入口传当前表名):端一带入并禁改;空=不锁定(独立 ER 图页保持原行为)
  lockedTable: { type: String, default: '' }
})

// done:保存成功(父级刷新图并提示)
const emit = defineEmits(['update:modelValue', 'done'])

const blank = () => ({ table: '', column: '', columns: [], columnsLoading: false })
const form = reactive({
  a: blank(),
  b: blank(),
  cardinality: 'ONE_TO_ONE',
  oneSide: 'a', // 1:N 时哪端为「一」
  remark: ''
})
const formError = ref('')
const submitting = ref(false)

// 端一选项:锁定当前表时确保其在清单里(整库清单懒拉未回/缺项也能正常显示)
const tableOptions = computed(() => {
  if (props.lockedTable && !props.tables.includes(props.lockedTable)) {
    return [props.lockedTable, ...props.tables]
  }
  return props.tables
})

/** 端二选项:排除端一已选的表(不能自己跟自己建关系);端一未选时不过滤 */
function sideTableOptions(side) {
  if (side === 'b' && form.a.table) {
    return tableOptions.value.filter((t) => t !== form.a.table)
  }
  return tableOptions.value
}

const canSubmit = computed(() =>
  form.a.table && form.b.table && form.a.table !== form.b.table &&
  form.a.column && form.b.column &&
  !(form.a.table === form.b.table && form.a.column === form.b.column))

/** 表选定后懒拉字段清单(同 RelationInferDialog 的字段 API);对话框存续期间按表缓存 */
async function loadColumns(side) {
  const s = form[side]
  s.column = ''
  s.columns = []
  if (!s.table) return
  s.columnsLoading = true
  try {
    const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
    const q = props.db ? `?db=${encodeURIComponent(props.db)}` : ''
    s.columns = await request.get(`${base}/tables/${encodeURIComponent(s.table)}/columns${q}`) || []
  } catch {
    formError.value = `字段列表加载失败:${s.table}`
  } finally {
    s.columnsLoading = false
  }
}

function onTableChange(side) {
  formError.value = ''
  // 端一改到与端二同表时清空端二(端二选项已过滤同表,这里兜底「先选端二后改端一」的时序)
  if (side === 'a' && form.b.table === form.a.table) {
    form.b.table = ''
    form.b.column = ''
    form.b.columns = []
  }
  loadColumns(side)
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    // 1:N 时 one 侧=用户选定的「一」端;1:1 方向无语义,后端按字典序归一化
    const one = form.cardinality === 'ONE_TO_MANY' && form.oneSide === 'b' ? form.b : form.a
    const many = one === form.a ? form.b : form.a
    const res = await addRelation({
      datasourceId: /^\d+$/.test(String(props.dsId)) ? Number(props.dsId) : props.dsId,
      dbName: props.db || null,
      schemaName: props.schema,
      oneTable: one.table,
      oneColumn: one.column,
      manyTable: many.table,
      manyColumn: many.column,
      cardinality: form.cardinality,
      remark: form.remark || null
    })
    emit('update:modelValue', false)
    emit('done', res)
  } catch {
    // 提交失败由响应拦截器弹出提示,停留在表单
  } finally {
    submitting.value = false
  }
}

watch(() => props.modelValue, (v) => {
  if (v) {
    form.a = blank()
    form.b = blank()
    form.cardinality = 'ONE_TO_ONE'
    form.oneSide = 'a'
    form.remark = ''
    formError.value = ''
    // 端一锁定当前表(表详情入口):带入并预载字段清单,表选择器已禁用
    if (props.lockedTable) {
      form.a.table = props.lockedTable
      loadColumns('a')
    }
  }
})
</script>

<style scoped>
.side-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.side-row :deep(.el-select) {
  margin-left: 0;
}
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
