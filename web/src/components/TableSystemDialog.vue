<template>
  <el-dialog
    :model-value="modelValue"
    title="设置所属系统"
    width="480px"
    append-to-body
    :close-on-press-escape="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <div v-loading="saving">
      <div class="tip">将对勾选的 {{ tableNames.length }} 张表生效,一张表只属于一个系统(重复设置会覆盖)</div>
      <el-select
        v-model="systemName"
        filterable
        allow-create
        clearable
        default-first-option
        placeholder="选择或输入系统名称"
        style="width: 100%; margin-top: 10px"
      >
        <el-option v-for="name in existingSystems" :key="name" :label="name" :value="name" />
      </el-select>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="clearSystem">清除所属系统</el-button>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import request from '../api'

const props = defineProps({
  modelValue: Boolean,
  dsId: [String, Number],
  schema: String,
  db: { type: String, default: '' },
  // 勾选的表名列表
  tableNames: { type: Array, default: () => [] },
  // 当前库已使用过的系统名(去重),作下拉候选
  existingSystems: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const saving = ref(false)
const systemName = ref('')

function onOpen() {
  systemName.value = ''
}

function url() {
  const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
  const q = props.db ? `?db=${encodeURIComponent(props.db)}` : ''
  return `${base}/table-systems${q}`
}

// 确定:批量设置;清除:systemName 传空,后端按清除处理
async function submit(name) {
  saving.value = true
  try {
    const res = await request.put(url(), { tableNames: props.tableNames, systemName: name })
    emit('saved')
    emit('update:modelValue', false)
    if (name) {
      ElMessage.success(`已设置 ${res?.updated ?? props.tableNames.length} 张表的所属系统`)
    } else {
      ElMessage.success(`已清除 ${res?.cleared ?? 0} 张表的所属系统`)
    }
  } finally {
    saving.value = false
  }
}

async function save() {
  const name = systemName.value.trim()
  if (!name) {
    ElMessage.warning('请选择或输入系统名称;要清除请点「清除所属系统」')
    return
  }
  await submit(name)
}

async function clearSystem() {
  await ElMessageBox.confirm(`确定清除勾选的 ${props.tableNames.length} 张表的所属系统吗?`, '清除所属系统', {
    type: 'warning',
    confirmButtonText: '清除',
    cancelButtonText: '取消'
  })
  await submit('')
}
</script>

<style scoped>
.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
