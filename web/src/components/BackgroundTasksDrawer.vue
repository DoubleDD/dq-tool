<script setup>
/**
 * 后台任务抽屉(头栏指示器点击打开):按任务种类分区展示进行中的关系推导/数据比对,
 * 行内 = 定位 + 阶段 + 进度条 + 耗时;点击行跳转结果现场(推导 → 表字段明细 ER 页签,
 * 比对 → 差异明细页签,页签体系自动落位)。历史完成/失败记录在各功能页与通知中心铃铛查看,
 * 抽屉只聚焦「正在跑」。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { backgroundTasks } from '../stores/backgroundTasks'
import { formatDuration } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const router = useRouter()

// 按 kind 分组,组内保持跟踪器清单顺序(接口侧 id 升序,先提交在前)
const groups = computed(() => {
  const map = new Map()
  for (const row of backgroundTasks.list) {
    if (!map.has(row.kind)) map.set(row.kind, [])
    map.get(row.kind).push(row)
  }
  return [...map.entries()].map(([kind, rows]) => ({ kind, rows }))
})

const KIND_LABEL = { 'relation-infer': '关系推导', compare: '数据比对' }
const KIND_TAG_TYPE = { 'relation-infer': 'primary', compare: 'warning' }

/** 点击行:关闭抽屉并跳转结果现场(进度页本身就是结果入口) */
function go(row) {
  emit('update:modelValue', false)
  router.push(row.link)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="后台任务"
    direction="rtl"
    size="420px"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <el-empty v-if="!backgroundTasks.list.length" description="没有进行中的后台任务" />
    <div v-for="g in groups" :key="g.kind" class="bt-group">
      <div class="bt-group-title">
        <el-tag size="small" :type="KIND_TAG_TYPE[g.kind]" effect="plain">{{ KIND_LABEL[g.kind] || g.kind }}</el-tag>
        <span class="bt-group-count">{{ g.rows.length }}</span>
      </div>
      <div v-for="row in g.rows" :key="row.key" class="bt-row" @click="go(row)">
        <div class="bt-row-head">
          <span class="bt-row-title" :title="row.title">{{ row.title }}</span>
          <span class="bt-row-locate" :title="row.locate">{{ row.locate }}</span>
        </div>
        <div class="bt-row-stage">
          <span>{{ row.stage }}</span>
          <span class="bt-row-steps">{{ row.steps }}</span>
          <span v-if="row.extra" class="bt-row-extra">{{ row.extra }}</span>
          <span class="bt-row-elapsed">{{ formatDuration(row.startedAt, null) }}</span>
        </div>
        <el-progress :percentage="row.percent" :stroke-width="8" :show-text="false" />
      </div>
    </div>
    <div class="bt-tip">任务在后台继续执行,关闭本抽屉不影响进度;完成或失败会弹出通知,也可在铃铛里回看</div>
  </el-drawer>
</template>

<style scoped>
.bt-group + .bt-group {
  margin-top: 14px;
}
.bt-group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.bt-group-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.bt-row {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.bt-row:hover {
  border-color: var(--el-color-primary);
}
.bt-row-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}
.bt-row-title {
  font-weight: 600;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bt-row-locate {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  max-width: 55%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bt-row-stage {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}
.bt-row-steps,
.bt-row-elapsed {
  color: var(--el-text-color-secondary);
}
.bt-row-extra {
  color: var(--el-color-primary);
}
.bt-row-elapsed {
  margin-left: auto;
}
.bt-tip {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
