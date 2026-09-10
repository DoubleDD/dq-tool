<template>
  <!-- 挂载表对话框:数据源固定为对象管理页已选;选表面板(库→schema→复选框清单)多选批量挂载;备注可选 -->
  <el-dialog
    :model-value="modelValue"
    title="挂载表"
    width="960px"
    :close-on-press-escape="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <el-form label-width="80px">
      <el-form-item label="数据源">
        <el-input :model-value="datasource?.name || ''" disabled />
      </el-form-item>
      <ObjectTablePickList ref="pickRef" :datasource="datasource" list-label="表" show-rel-kind :table-height="420" />
      <el-form-item label="备注">
        <el-input v-model="remark" placeholder="可选,如挂载用途(应用到本次全部选中表)" maxlength="200" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { mountObjectTable } from '../api'
import ObjectTablePickList from './ObjectTablePickList.vue'

// 目录批量挂载表(自成闭环):保存成功后 emit('done', {mounted, existing}),父级重拉整树并提示
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 当前数据源对象(取 id/name/dbType)
  datasource: { type: Object, required: true },
  // 挂载目标目录 id
  dirId: { type: [String, Number], required: true }
})

// done:保存成功,带 {mounted, existing} 批量统计
const emit = defineEmits(['update:modelValue', 'done'])

const pickRef = ref(null)
const remark = ref('')
const submitting = ref(false)

const canSubmit = computed(() => {
  const p = pickRef.value
  return !!p?.schema && p.selectedNames.length > 0
})

async function submit() {
  const p = pickRef.value
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const r = await mountObjectTable(props.dirId, {
      dbName: p.db || null,
      schemaName: p.schema,
      items: p.selectedItems,
      remark: remark.value || null
    })
    emit('update:modelValue', false)
    emit('done', r)
  } catch {
    // 提交失败由响应拦截器弹出提示,停留在表单
  } finally {
    submitting.value = false
  }
}

// 打开时重置备注并让选表面板重拉库/schema 列表;先等一帧再 reset,
// 因为 el-dialog 首次打开才懒挂载插槽内容,pre-flush watch 里 pickRef 还是 null,reset 会被静默跳过
watch(() => props.modelValue, async (v) => {
  if (!v) return
  remark.value = ''
  await nextTick()
  pickRef.value?.reset()
})
</script>
