<template>
  <!-- 批量设置描述:通用分栏选择器选字典表(库名 -> 描述)+ 两个字段,按库名精准匹配回写 schema_doc;
       字段清单按所选表懒拉,提交后由父级刷新描述列 -->
  <el-dialog
    :model-value="modelValue"
    title="批量设置描述"
    width="1180px"
    style="max-width: 94vw"
    :close-on-press-escape="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="dict-tip">
      从字典表读取「库名 -> 描述」映射,按库名<b>精准匹配</b>(大小写敏感)批量写入库描述;已匹配库的旧描述会被覆盖,描述为空的行跳过。
    </div>

    <!-- 字典表:分栏级联选择器(数据源栏列出全部,默认选中当前数据源,可跨数据源选字典表) -->
    <div v-if="modelValue" class="dict-picker">
      <TableCascadePicker
        v-model:datasource-id="pickedDsId"
        v-model:db="form.db"
        v-model:schema="form.schema"
        v-model:table="form.table"
        :datasources="allDatasources"
        label="字典表"
      />
    </div>

    <el-form label-width="90px" style="margin-top: 12px">
      <el-form-item label="库名字段">
        <el-select v-model="form.nameField" filterable placeholder="选择字段" style="width: 100%" :loading="columnsLoading" :disabled="!form.table">
          <el-option v-for="c in columns" :key="c.name" :value="c.name" :label="c.name">
            <span>{{ c.name }}</span>
            <span style="float:right;color:var(--el-text-color-secondary);font-size:12px">
              {{ c.displayType || c.typeName || '' }}{{ c.comment ? ` · ${c.comment}` : '' }}
            </span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="描述字段">
        <el-select v-model="form.descField" filterable placeholder="选择字段" style="width: 100%" :loading="columnsLoading" :disabled="!form.table">
          <el-option v-for="c in columns" :key="c.name" :value="c.name" :label="c.name">
            <span>{{ c.name }}</span>
            <span style="float:right;color:var(--el-text-color-secondary);font-size:12px">
              {{ c.displayType || c.typeName || '' }}{{ c.comment ? ` · ${c.comment}` : '' }}
            </span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-alert v-if="formError" type="error" :closable="false" :title="formError" show-icon />
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import request from '../api'
import { ElMessage } from '../utils/notify'
import TableCascadePicker from './TableCascadePicker.vue'

// 批量设置描述(库列表页「更多」入口):字典表按库名精准匹配回写 schema_doc;
// 字典表走通用分栏选择器,数据源栏列出全部数据源、默认选中当前(字典表可跨数据源,
// 提交带 dictDatasourceId,后端读字典与写描述分离);提交成功 emit applied(父级刷新描述列)
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  datasourceId: { type: [String, Number], required: true }
})

const emit = defineEmits(['update:modelValue', 'applied'])

// 选择器数据源栏:全部数据源(打开时拉取),默认选中当前数据源
const pickedDsId = ref(String(props.datasourceId))
const allDatasources = ref([])

const form = reactive({
  db: '',
  schema: '',
  table: '',
  nameField: '',
  descField: ''
})
const columns = ref([])
const columnsLoading = ref(false)
const formError = ref('')
const submitting = ref(false)

const canSubmit = computed(() =>
  form.schema && form.table && form.nameField && form.descField)

// 选中表后懒拉字段清单(与字段明细同接口,按所选字典数据源);库/模式/表变化都会触发(table 被清空时复位)
watch(() => form.table, async (table) => {
  form.nameField = ''
  form.descField = ''
  columns.value = []
  formError.value = ''
  if (!table || !form.schema) return
  columnsLoading.value = true
  try {
    const q = form.db ? `?db=${encodeURIComponent(form.db)}` : ''
    columns.value = await request.get(
      `/datasources/${pickedDsId.value}/schemas/${encodeURIComponent(form.schema)}/tables/${encodeURIComponent(table)}/columns${q}`) || []
  } catch {
    formError.value = `字段列表加载失败:${table}`
  } finally {
    columnsLoading.value = false
  }
})

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    const res = await request.post(`/datasources/${props.datasourceId}/schema-descriptions/from-dict`, {
      db: form.db || null,
      schema: form.schema,
      table: form.table,
      nameField: form.nameField,
      descField: form.descField,
      // 字典表所在数据源(可与当前页数据源不同);相同也传,后端空才回落本数据源
      dictDatasourceId: Number(pickedDsId.value)
    })
    emit('update:modelValue', false)
    emit('applied')
    // 结果统计:未匹配附样例名(后端截断前 50 个),便于人工核对
    const unmatchedHint = res.unmatchedTotal
      ? `,未匹配 ${res.unmatchedTotal} 个(如:${res.unmatched.slice(0, 5).join('、')}${res.unmatchedTotal > 5 ? '…' : ''})`
      : ''
    ElMessage.success(`已匹配 ${res.matched} 个库并写入描述${unmatchedHint},跳过空行 ${res.skipped} 条`)
  } catch {
    // 提交失败由响应拦截器弹出提示,停留在表单
  } finally {
    submitting.value = false
  }
}

// 每次打开重置选择并拉全部数据源(选择器随 v-if 重挂载,内部过滤/清单一并复位)
watch(() => props.modelValue, async (v) => {
  if (v) {
    pickedDsId.value = String(props.datasourceId)
    form.db = ''
    form.schema = ''
    form.table = ''
    form.nameField = ''
    form.descField = ''
    columns.value = []
    formError.value = ''
    allDatasources.value = await request.get('/datasources').catch(() => [])
  }
})
</script>

<style scoped>
.dict-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 12px;
}
/* 分栏选择器:固定高度,栏内列表滚动 */
.dict-picker {
  height: 440px;
  display: flex;
  flex-direction: column;
}
</style>
