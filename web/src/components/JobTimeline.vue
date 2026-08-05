<template>
  <el-tooltip placement="top" :show-after="200" :disabled="!events || events.length === 0">
    <template #content>
      <div v-for="(e, i) in events" :key="i" class="timeline-row">
        <span class="timeline-label">{{ eventLabel(e, i) }}</span>
        <span>{{ formatDateTime(e.at) }}</span>
      </div>
    </template>
    <span><slot /></span>
  </el-tooltip>
</template>

<script setup>
import { formatDateTime } from '../utils/format'

// 任务状态变更事件 [{status, at}],按时间升序;无事件(老数据)时 tooltip 自动禁用
const props = defineProps({
  events: { type: Array, default: () => [] }
})

// 首次 RUNNING 为"开始",后续 RUNNING 为"继续"(续扫);其余状态直接映射文案
function eventLabel(e, i) {
  if (e.status === 'RUNNING') {
    return props.events.findIndex(x => x.status === 'RUNNING') === i ? '开始' : '继续'
  }
  return { PENDING: '创建', DONE: '完成', FAILED: '失败', CANCELED: '取消', INTERRUPTED: '中断' }[e.status] || e.status
}
</script>

<style scoped>
.timeline-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  line-height: 1.8;
}

.timeline-label {
  font-weight: 600;
}
</style>
