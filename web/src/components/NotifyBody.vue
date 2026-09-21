<script setup>
/**
 * 全局通知摘要体(配合 utils/notify.js 的 ElNotification 使用):
 * 内容固定折叠为两行,不提供原地展开——完整内容去通知中心(头部铃铛抽屉)看。
 * actions 为可选操作按钮(如导出完成「打开文件/打开文件夹」),点击行为由 notify.js 包装。
 */
defineProps({
  text: { type: String, default: '' },
  /** [{label, onClick}] 操作按钮,notify.js 已包装「先关通知再执行」 */
  actions: { type: Array, default: () => [] }
})
</script>

<template>
  <div class="notify-body">
    <div class="notify-text">{{ text }}</div>
    <div v-if="actions.length" class="notify-actions">
      <a v-for="(a, i) in actions" :key="i" class="notify-action" @click.stop="a.onClick">{{ a.label }}</a>
    </div>
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
