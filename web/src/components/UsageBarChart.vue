<template>
  <div ref="wrapRef" class="usage-bar-chart">
    <svg v-if="width > 0" :width="width" :height="height" :viewBox="`0 0 ${width} ${height}`">
      <!-- 横向网格线 + Y 轴刻度 -->
      <g v-for="t in yTicks" :key="t.value">
        <line class="grid-line" :x1="padL" :y1="t.y" :x2="width - padR" :y2="t.y" />
        <text class="axis-label" :x="padL - 8" :y="t.y + 4" text-anchor="end">{{ t.text }}</text>
      </g>
      <!-- 柱体组(每根柱带透明命中区用于悬停) -->
      <g v-for="(d, i) in data" :key="d.date">
        <rect
          class="hit-area"
          :x="bandX(i) - 2"
          :y="padT"
          :width="band + 4"
          :height="plotH - padT"
          @mouseenter="hover = i"
          @mousemove="moveTooltip"
          @mouseleave="hover = -1"
        />
        <!-- 金额模式:单根柱 -->
        <template v-if="metric === 'cost'">
          <rect
            v-if="d.cost > 0"
            class="bar-cost"
            :x="barX(i)"
            :y="yScale(d.cost)"
            :width="barW"
            :height="Math.max(1, plotH - padT - yScale(d.cost))"
            rx="2"
          />
        </template>
        <!-- Token 模式:输入/输出堆叠柱 -->
        <template v-else>
          <rect
            v-if="d.completionTokens > 0"
            class="bar-completion"
            :x="barX(i)"
            :y="yScale(d.promptTokens + d.completionTokens)"
            :width="barW"
            :height="Math.max(1, plotH - padT - yScale(d.promptTokens + d.completionTokens))"
            rx="2"
          />
          <rect
            v-if="d.promptTokens > 0"
            class="bar-prompt"
            :x="barX(i)"
            :y="yScale(d.promptTokens)"
            :width="barW"
            :height="Math.max(1, plotH - padT - yScale(d.promptTokens))"
            rx="2"
          />
        </template>
        <!-- X 轴日期标签(抽稀显示) -->
        <text
          v-if="i % xLabelStep === 0"
          class="axis-label"
          :x="bandX(i) + band / 2"
          :y="height - 8"
          text-anchor="middle"
        >{{ d.xLabel || d.date.slice(5) }}</text>
      </g>
      <!-- 悬停高亮竖线 -->
      <line v-if="hover >= 0" class="hover-line" :x1="bandX(hover) + band / 2" :y1="padT"
            :x2="bandX(hover) + band / 2" :y2="plotH" />
    </svg>

    <!-- 悬停提示 -->
    <div
      v-if="hover >= 0 && data[hover]"
      class="chart-tooltip"
      :style="{ left: tooltipX + 'px', top: '8px' }"
    >
      <div class="tip-title">{{ data[hover].title || data[hover].date }}</div>
      <div class="tip-row" v-if="metric === 'token'">
        <span class="tip-dot dot-prompt" />输入 {{ formatNumber(data[hover].promptTokens) }}<template v-if="data[hover].promptCost != null"> · {{ formatCost(data[hover].promptCost) }}</template>
      </div>
      <div class="tip-row" v-if="metric === 'token'">
        <span class="tip-dot dot-completion" />输出 {{ formatNumber(data[hover].completionTokens) }}<template v-if="data[hover].completionCost != null"> · {{ formatCost(data[hover].completionCost) }}</template>
      </div>
      <div class="tip-row" v-if="metric === 'token'">
        <span class="tip-dot dot-total" />总计 {{ formatNumber(data[hover].totalTokens) }} · {{ formatCost(data[hover].cost) }}
      </div>
      <div class="tip-row" v-else><span class="tip-dot dot-cost" />{{ formatCost(data[hover].cost) }}</div>
      <div class="tip-row tip-sub">{{ data[hover].calls }} 次调用</div>
    </div>
  </div>
</template>

<script setup>
/**
 * Token/费用消耗柱状图(纯 SVG,无第三方图表依赖):
 * 金额模式 = 单柱;Token 模式 = 输入/输出堆叠柱。悬停显示明细,颜色/网格跟随主题 CSS 变量。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { formatNumber } from '../utils/format'

const props = defineProps({
  /** 每日序列:[{date:'yyyy-MM-dd', calls, promptTokens, completionTokens, totalTokens, cost, promptCost?, completionCost?}] */
  data: { type: Array, default: () => [] },
  /** 展示指标:'cost' 金额 | 'token' Token */
  metric: { type: String, default: 'cost' }
})

const wrapRef = ref(null)
const width = ref(0)
const height = 240
const padL = 58
const padR = 12
const padT = 14
let observer = null

onMounted(() => {
  observer = new ResizeObserver(() => {
    width.value = Math.max(200, Math.floor(wrapRef.value?.clientWidth || 0))
  })
  observer.observe(wrapRef.value)
})
onBeforeUnmount(() => observer?.disconnect())

// 图表区几何
const plotH = height - 30 // 底部留 X 轴标签空间
const n = computed(() => props.data.length)
const band = computed(() => (n.value ? (width.value - padL - padR) / n.value : 0))
const barW = computed(() => Math.min(30, Math.max(4, band.value * 0.62)))
const bandX = (i) => padL + i * band.value
const barX = (i) => bandX(i) + (band.value - barW.value) / 2

// Y 轴
const maxValue = computed(() => {
  if (!n.value) return 0
  const v = props.metric === 'cost'
    ? Math.max(...props.data.map((d) => d.cost))
    : Math.max(...props.data.map((d) => d.totalTokens))
  return v > 0 ? v : 1
})
const yMax = computed(() => niceMax(maxValue.value))
const yScale = (v) => plotH - (v / yMax.value) * (plotH - padT)
const yTicks = computed(() => {
  const ticks = []
  for (let i = 0; i <= 4; i++) {
    const v = (yMax.value * i) / 4
    ticks.push({ value: v, y: yScale(v), text: props.metric === 'cost' ? formatCost(v) : formatToken(v) })
  }
  return ticks
})
// X 轴标签抽稀:8 个以内全显示,否则每 ceil(n/8) 个显示一个
const xLabelStep = computed(() => Math.max(1, Math.ceil(n.value / 8)))

// 悬停
const hover = ref(-1)
const tooltipX = ref(0)
function moveTooltip(e) {
  tooltipX.value = Math.max(0, Math.min(width.value - 150, e.offsetX + 12))
}

// 数值格式
function niceMax(v) {
  const exp = Math.floor(Math.log10(v))
  const base = Math.pow(10, exp)
  const frac = v / base
  if (frac <= 1) return base
  if (frac <= 2) return 2 * base
  if (frac <= 5) return 5 * base
  return 10 * base
}
/** Token 缩写:>=1 亿用亿,>=1 万用万 */
function formatToken(v) {
  if (v >= 1e8) return (v / 1e8).toFixed(1).replace(/\.0$/, '') + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(1).replace(/\.0$/, '') + '万'
  return String(Math.round(v))
}
/** 金额格式:大额整数,小额保留足够精度 */
function formatCost(v) {
  if (v >= 1000) return '¥' + v.toFixed(0)
  if (v >= 1) return '¥' + v.toFixed(2)
  return '¥' + v.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

// 数据/指标变化时重置悬停
watch(() => [props.data, props.metric], () => { hover.value = -1 }, { deep: true })
</script>

<style scoped>
.usage-bar-chart {
  position: relative;
  width: 100%;
}
.usage-bar-chart :deep(svg) {
  display: block;
}
.grid-line {
  stroke: var(--el-border-color-lighter);
  stroke-width: 1;
}
.hover-line {
  stroke: var(--el-border-color);
  stroke-width: 1;
  stroke-dasharray: 3 3;
  pointer-events: none;
}
.axis-label {
  font-size: 11px;
  fill: var(--el-text-color-secondary);
}
.hit-area {
  fill: transparent;
  cursor: pointer;
}
.bar-cost {
  fill: var(--el-color-primary);
  transition: opacity 0.15s;
}
/* 柱体不拦截鼠标事件,保证悬停命中区在任何位置都能触发 */
.bar-cost,
.bar-prompt,
.bar-completion {
  pointer-events: none;
}
.bar-prompt {
  fill: var(--el-color-primary);
}
.bar-completion {
  fill: var(--el-color-success);
}
.chart-tooltip {
  position: absolute;
  min-width: 150px;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-light);
  background: var(--dq-surface);
  box-shadow: var(--el-box-shadow-light);
  font-size: 12px;
  color: var(--el-text-color-regular);
  pointer-events: none;
  z-index: 10;
}
.tip-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 4px;
}
.tip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 1.7;
}
.tip-sub {
  color: var(--el-text-color-secondary);
}
.tip-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
}
.dot-prompt { background: var(--el-color-primary); }
.dot-completion { background: var(--el-color-success); }
.dot-total { background: var(--el-text-color-secondary); }
.dot-cost { background: var(--el-color-primary); }
</style>
