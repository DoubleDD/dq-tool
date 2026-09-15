<template>
  <!-- 移动弹窗:目录/挂载表/关系表统一「变更所属」入口(与树内拖动等价)。
       dir=移到某目录下(可选根);table=移到某目录下;rel=移到某挂载表下(仅表可选) -->
  <el-dialog
    :model-value="modelValue"
    :title="`移动「${currentName}」`"
    width="480px"
    :close-on-press-escape="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <el-form label-width="80px">
      <el-form-item :label="sourceLabel">
        <el-input :model-value="currentName" disabled />
      </el-form-item>
      <el-form-item :label="targetLabel">
        <el-cascader
          v-model="target"
          :options="options"
          :props="cascaderProps"
          style="width: 100%"
          :placeholder="placeholder"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="target == null" @click="confirm">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

// 移动目标选择:目录树级联(rel 模式叶子为挂载表);确认后 emit('confirm', 目标 id),由父级调对应 move 接口
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // dir=移动目录 / table=移动挂载表 / rel=移动关系表
  mode: { type: String, default: 'dir' },
  // 原始目录树(接口口径,含 children 与 tables),组件内部转级联选项
  catalog: { type: Array, default: () => [] },
  // 当前节点 id:dir 模式=目录自身(自身及子孙禁选);table 模式=所在目录(禁选);rel 模式=所属挂载表(禁选)
  currentId: { type: [String, Number], default: null },
  currentName: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'confirm'])

const target = ref(null)

// 打开时清空上次选择
watch(() => props.modelValue, (v) => {
  if (v) target.value = null
})

const sourceLabel = computed(() => (props.mode === 'rel' ? '关系表' : props.mode === 'table' ? '挂载表' : '目录'))
const targetLabel = computed(() => (props.mode === 'rel' ? '目标挂载表' : '目标目录'))
const placeholder = computed(() => (props.mode === 'rel' ? '选择要移动到哪个挂载表下' : '选择要移动到哪个目录下'))

/** 目录是否为目标排除 id 自身或其子孙(防把目录移到自身下) */
function containsDir(dir, id) {
  if (String(dir.id) === String(id)) return true
  return (dir.children || []).some((c) => containsDir(c, id))
}

/** 目录级联选项;disabledIds 命中的目录及其子孙整支禁选 */
function dirOptions(dirs, disabledIds) {
  return (dirs || []).map((d) => {
    const disabled = (disabledIds || []).some((id) => containsDir(d, id))
    return { value: d.id, label: d.name, disabled, children: dirOptions(d.children, disabledIds) }
  })
}

/** rel 模式级联选项:目录(禁选)下挂挂载表叶子;excludeTableId=当前所属挂载表(禁选) */
function relOptions(dirs, excludeTableId) {
  return (dirs || []).map((d) => {
    const children = [
      ...relOptions(d.children || [], excludeTableId),
      ...(d.tables || []).map((t) => ({
        value: t.id,
        label: t.tableName,
        disabled: String(t.id) === String(excludeTableId)
      }))
    ]
    // 空目录无叶子也不可作为目标,一并禁选
    return { value: `d-${d.id}`, label: d.name, disabled: children.length === 0, children }
  })
}

const options = computed(() => {
  if (props.mode === 'rel') return relOptions(props.catalog, props.currentId)
  if (props.mode === 'table') return dirOptions(props.catalog, [props.currentId])
  // dir 模式:虚拟根节点 + 目录树(自身及子孙禁选,后端同样会 400)
  return [{ value: 0, label: '(根目录)', children: dirOptions(props.catalog, [props.currentId]) }]
})

// dir/table 模式任意层级可选(目录本身即目标);rel 模式仅叶子(挂载表)可选,由 cascader 默认行为保证
const cascaderProps = computed(() =>
  props.mode === 'rel' ? { emitPath: false } : { emitPath: false, checkStrictly: true }
)

function confirm() {
  if (target.value == null) return
  emit('confirm', target.value)
}
</script>
