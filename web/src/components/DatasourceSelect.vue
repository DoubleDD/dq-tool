<script setup>
/**
 * 数据源下拉选择(可过滤,选项带数据库类型图标)。
 * 数据源列表由调用方拉取传入(本组件不自带请求),
 * 同一页面工具栏/空状态多处复用时保证选项口径一致。
 */
import DbTypeIcon from './DbTypeIcon.vue'

defineProps({
  modelValue: { type: String, default: '' },
  datasources: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'change'])
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    placeholder="选择数据源"
    :loading="loading"
    @update:model-value="emit('update:modelValue', $event)"
    @change="emit('change')"
  >
    <el-option v-for="ds in datasources" :key="ds.id" :value="String(ds.id)" :label="ds.name">
      <div class="ds-option">
        <DbTypeIcon :type="ds.dbType" :size="14" />
        <span>{{ ds.name }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<style scoped>
.ds-option {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
