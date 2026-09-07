<script setup>
/**
 * 头部通知中心:铃铛按钮 + 右侧抽屉展示历史通知(最新在最前),
 * 抽屉内可一键清除所有。数据来源 stores/notifications.js(localStorage 持久化,
 * 由 utils/notify.js 写入)。
 */
import { ref } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import { notifyHistory, clearNotifyHistory } from '../stores/notifications'

const TYPE_TEXT = { success: '操作成功', error: '操作失败', warning: '注意', info: '提示' }

const visible = ref(false)

function fmtTime(t) {
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`
  // 跨天的历史带上日期,避免只显示时分产生歧义
  const today = new Date()
  return d.toDateString() === today.toDateString() ? hm : `${p(d.getMonth() + 1)}-${p(d.getDate())} ${hm}`
}
</script>

<template>
  <el-tooltip content="通知" placement="bottom">
    <el-button class="notify-bell" text circle @click="visible = true">
      <el-icon><Bell /></el-icon>
    </el-button>
  </el-tooltip>
  <el-drawer v-model="visible" direction="rtl" size="380px" class="notify-drawer">
    <template #header>
      <div class="notify-drawer-header">
        <span class="notify-drawer-title">通知</span>
        <el-button v-if="notifyHistory.list.length" text type="danger" size="small" @click="clearNotifyHistory">
          清除所有
        </el-button>
      </div>
    </template>
    <el-empty v-if="!notifyHistory.list.length" description="暂无通知" />
    <div v-for="item in notifyHistory.list" :key="item.id" class="notify-item">
      <span class="notify-item-dot" :class="item.type" />
      <div class="notify-item-main">
        <div class="notify-item-head">
          <span class="notify-item-title">{{ TYPE_TEXT[item.type] || '提示' }}</span>
          <span class="notify-item-time">{{ fmtTime(item.time) }}</span>
        </div>
        <div class="notify-item-text">{{ item.text }}</div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.notify-bell {
  margin-right: 4px;
}

.notify-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.notify-drawer-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.notify-item {
  display: flex;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.notify-item-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
}

.notify-item-dot.success { background: var(--el-color-success); }
.notify-item-dot.warning { background: var(--el-color-warning); }
.notify-item-dot.error { background: var(--el-color-error); }
.notify-item-dot.info { background: var(--el-color-info); }

.notify-item-main {
  flex: 1;
  min-width: 0;
}

.notify-item-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.notify-item-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.notify-item-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.notify-item-text {
  margin-top: 2px;
  font-size: 12px;
  line-height: 17px;
  color: var(--el-text-color-regular);
  word-break: break-word;
  white-space: pre-wrap;
}
</style>
