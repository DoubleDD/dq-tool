<script setup>
/**
 * 全局通知摘要体(配合 utils/notify.js 的 ElNotification 使用):
 * 内容默认折叠为两行,超长时显示「展开全部/收起」;展开/收起均为原地进行,
 * 通过 expand/collapse 事件通知外层重算堆叠位置(下方通知跟随高度变化移动)。
 * actions 为可选操作按钮(如导出完成「打开文件/打开文件夹」),点击行为由 notify.js 包装。
 */
import { ref, onMounted, nextTick } from 'vue'

defineProps({
  text: { type: String, default: '' },
  /** [{label, onClick}] 操作按钮,notify.js 已包装「先关通知再执行」 */
  actions: { type: Array, default: () => [] }
})
const emit = defineEmits(['expand', 'collapse'])

const expanded = ref(false)
const overflow = ref(false)
const textRef = ref(null)

onMounted(async () => {
  await nextTick()
  const el = textRef.value
  // 两行折叠态下 scrollHeight 超出可视高度即判定内容过长
  if (el) overflow.value = el.scrollHeight > el.clientHeight + 1
})

function toggle() {
  expanded.value = !expanded.value
  emit(expanded.value ? 'expand' : 'collapse')
}
</script>

<template>
  <div class="notify-body">
    <div ref="textRef" class="notify-text" :class="{ expanded }">{{ text }}</div>
    <div v-if="actions.length" class="notify-actions">
      <a v-for="(a, i) in actions" :key="i" class="notify-action" @click.stop="a.onClick">{{ a.label }}</a>
    </div>
    <a v-if="overflow || expanded" class="notify-toggle" @click.stop="toggle">
      {{ expanded ? '收起' : '展开全部' }}
    </a>
  </div>
</template>

<style scoped>
.notify-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
  white-space: pre-wrap;
}

.notify-text.expanded {
  display: block;
  max-height: 40vh;
  overflow-y: auto;
}

.notify-toggle {
  display: inline-block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-primary);
  cursor: pointer;
}

.notify-actions {
  margin-top: 6px;
  display: flex;
  gap: 12px;
}

.notify-action {
  font-size: 12px;
  color: var(--el-color-primary);
  cursor: pointer;
}
</style>
