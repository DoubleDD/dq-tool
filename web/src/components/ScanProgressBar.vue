<template>
  <div class="scan-progress">
    <!-- 详情模式:每段的图例(色点 + 标签 + 计数/百分比)放在各自进度条正上方 -->
    <div v-if="!compact" class="bar">
      <div v-for="s in segments" :key="s.label" class="seg-block">
        <div class="seg-label">
          <i class="dot" :style="{ background: s.color }" />{{ s.label }} {{ s.text }}
        </div>
        <div class="seg">
          <div class="fill" :style="{ width: s.pct + '%', background: s.color }" />
        </div>
      </div>
    </div>
    <!-- 紧凑模式(扫描记录列表):只显示分段条,明细经悬浮提示展示 -->
    <el-tooltip v-else placement="top" :show-after="200">
      <template #content>
        <div v-for="s in segments" :key="s.label">{{ s.label }}:{{ s.text }}</div>
      </template>
      <div class="bar">
        <div v-for="s in segments" :key="s.label" class="seg">
          <div class="fill" :style="{ width: s.pct + '%', background: s.color }" />
        </div>
      </div>
    </el-tooltip>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 扫描任务三段式进度条:扫描 / AI 打标 / AI 表描述 在一行内分三段并行更新。
 * 三者执行关系是并行(表 DONE 即触发 AI 后续,AI 在独立线程池跑,任务收尾等 AI 清零),
 * 所以每段按各自进度独立填充;AI 类别开关关闭时该段置灰显示「未启用」。
 */
const props = defineProps({
  job: { type: Object, required: true },
  // 紧凑模式(扫描记录列表):只显示分段条,明细经悬浮提示展示
  compact: { type: Boolean, default: false }
})

const TERMINAL = ['DONE', 'FAILED', 'CANCELED', 'INTERRUPTED']
const DISABLED_COLOR = 'var(--el-text-color-placeholder)'

const segments = computed(() => {
  const j = props.job
  const ai = j.ai || {}
  const status = j.status
  // AI 类别进度:有入队按 完成/入队;开关开着但无入队(扫描未产出表/全空表跳过)时,任务 DONE 算 100%,否则 0
  const aiPct = (done, total) => (total > 0 ? Math.min(100, Math.round((done / total) * 100)) : (status === 'DONE' ? 100 : 0))
  const aiSeg = (label, enabled, done, total, color) => {
    if (!enabled) {
      return { label, color: DISABLED_COLOR, pct: 0, text: '未启用' }
    }
    if (total > 0) {
      return { label, color, pct: aiPct(done, total), text: `${done}/${total} ${aiPct(done, total)}%` }
    }
    return { label, color, pct: aiPct(0, 0), text: status === 'DONE' ? '无任务' : (TERMINAL.includes(status) ? '无任务' : '等待扫描出表') }
  }
  const scanPct = Math.round(j.progressPercent || 0)
  return [
    {
      label: '扫描',
      color: status === 'FAILED' ? 'var(--el-color-danger)' : 'var(--el-color-primary)',
      pct: scanPct,
      text: `${j.doneTables}/${j.totalTables} 表 ${scanPct}%`
    },
    aiSeg('AI 打标', j.autoTag, ai.tagDone || 0, ai.tagTotal || 0, 'var(--el-color-success)'),
    aiSeg('AI 表描述', j.genDoc, ai.docDone || 0, ai.docTotal || 0, 'var(--el-color-warning)')
  ]
})
</script>

<style scoped>
.seg-block {
  flex: 1;
  min-width: 0;
}
.seg-label {
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: -1px;
}
.bar {
  display: flex;
  gap: 2px;
}
.seg {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--el-border-color-lighter);
  overflow: hidden;
}
.fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s;
}
</style>
