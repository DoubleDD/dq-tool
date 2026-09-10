<template>
  <!-- 添加关系表对话框:为某张已挂载表批量补充关系表;选表面板排除挂载表自身,标题注明目标表 -->
  <el-dialog
    :model-value="modelValue"
    :title="`为 ${tableName} 添加关系表`"
    width="720px"
    :close-on-press-escape="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <el-form label-width="80px">
      <el-form-item label="数据源">
        <el-input :model-value="datasource?.name || ''" disabled />
      </el-form-item>
      <ObjectTablePickList ref="pickRef" :datasource="datasource" list-label="关系表" :exclude-table="tableName"
                           :fixed-db="dbName" :fixed-schema="schemaName" show-rel-kind />
      <el-form-item label="备注">
        <el-input v-model="remark" placeholder="可选,如关联说明(应用到本次全部选中表)" maxlength="200" />
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
import { addObjectTableRelation } from '../api'
import ObjectTablePickList from './ObjectTablePickList.vue'

// 挂载表批量添加关系表(自成闭环):保存成功后 emit('done', {mounted, existing}),父级重拉整树并提示
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 当前数据源对象(取 id/name/dbType)
  datasource: { type: Object, required: true },
  // 目标挂载表(ObjectTableView.id)
  objectTableId: { type: [String, Number], required: true },
  // 目标挂载表名(标题注明「为 xxx 添加关系表」,并从清单中排除自身)
  tableName: { type: String, default: '' },
  // 目标挂载表所在库/schema:关系表默认与挂载表同 schema,锁定不可改
  dbName: { type: String, default: '' },
  schemaName: { type: String, default: '' }
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
    const r = await addObjectTableRelation(props.objectTableId, {
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
// 因为 el-dialog 首次打开才懒挂载插槽内容,pre-flush watch 里 pickRef 还是 null,reset 会被静默跳过。
// immediate 不能省:父级用 v-if="relTarget" 挂载本组件,首次点击时组件随 modelValue=true 一起创建,
// 非 immediate 的 watch 不会触发,首次打开 schema 列表会是空的
watch(() => props.modelValue, async (v) => {
  if (!v) return
  remark.value = ''
  await nextTick()
  pickRef.value?.reset()
}, { immediate: true })
</script>
