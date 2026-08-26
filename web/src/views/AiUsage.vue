<template>
  <div class="page-card ai-usage">
    <div v-loading="loading" class="usage-body">
      <!-- 指标卡 -->
      <div class="metric-cards">
        <div class="metric-card">
          <div class="metric-value">{{ formatNumber(summary.calls) }}</div>
          <div class="metric-label">调用次数</div>
          <div class="metric-note">最近 {{ days }} 天</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ formatNumber(summary.promptTokens) }}</div>
          <div class="metric-label">输入 Token</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ formatNumber(summary.completionTokens) }}</div>
          <div class="metric-label">输出 Token</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ formatNumber(summary.totalTokens) }}</div>
          <div class="metric-label">总 Token</div>
        </div>
        <div class="metric-card">
          <div class="metric-value cost-value">{{ formatCost(summary.cost) }}</div>
          <div class="metric-label">总费用</div>
          <div class="metric-note">按当前价格配置,峰谷价已计入</div>
        </div>
      </div>

      <!-- 柱状图:金额 / Token 切换 + 时间范围 -->
      <div class="chart-card">
        <div class="chart-toolbar">
          <span class="chart-title">消耗趋势</span>
          <div class="chart-controls">
            <el-radio-group v-model="days" size="small" @change="loadStats">
              <el-radio-button :value="7">7 天</el-radio-button>
              <el-radio-button :value="30">30 天</el-radio-button>
              <el-radio-button :value="90">90 天</el-radio-button>
            </el-radio-group>
            <el-radio-group v-model="metric" size="small" class="metric-switch">
              <el-radio-button value="cost">金额</el-radio-button>
              <el-radio-button value="token">Token</el-radio-button>
            </el-radio-group>
          </div>
        </div>
        <el-empty v-if="summary.calls === 0" description="暂无 AI 调用记录" :image-size="80" />
        <template v-else>
          <UsageBarChart :data="series" :metric="metric" />
          <div v-if="metric === 'token'" class="chart-legend">
            <span class="legend-item"><span class="legend-dot dot-prompt" />输入 Token</span>
            <span class="legend-item"><span class="legend-dot dot-completion" />输出 Token</span>
          </div>
        </template>
      </div>

      <!-- 场景分布 -->
      <div class="section-title">场景分布</div>
      <el-table v-if="scenes.length" :data="scenes" border class="usage-table" :max-height="280">
        <el-table-column prop="label" label="场景" min-width="120" />
        <el-table-column label="次数" width="110" align="right" sortable>
          <template #default="{ row }">{{ formatNumber(row.calls) }}</template>
        </el-table-column>
        <el-table-column label="输入 Token" width="130" align="right" sortable>
          <template #default="{ row }">{{ formatNumber(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出 Token" width="130" align="right" sortable>
          <template #default="{ row }">{{ formatNumber(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="总 Token" width="130" align="right" sortable>
          <template #default="{ row }">{{ formatNumber(row.totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="费用" width="120" align="right" sortable>
          <template #default="{ row }">{{ formatCost(row.cost) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无调用记录" :image-size="70" />

      <!-- 最近调用明细 -->
      <div class="section-title">最近调用</div>
      <el-table :data="logs" border class="usage-table" :max-height="360">
        <el-table-column label="时间" width="200">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="sceneLabel" label="场景" width="110" />
        <el-table-column prop="model" label="模型" min-width="150" show-overflow-tooltip />
        <el-table-column label="输入" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="总计" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="时段" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.period === 'PEAK'" size="small" type="warning">峰</el-tag>
            <el-tag v-else-if="row.period === 'VALLEY'" size="small" type="info">谷</el-tag>
            <el-tag v-else size="small">平</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="费用" width="120" align="right">
          <template #default="{ row }">{{ formatCost(row.cost) }}</template>
        </el-table-column>
      </el-table>
      <div class="log-pager">
        <el-pagination
          v-model:current-page="logPage"
          v-model:page-size="logSize"
          :total="logTotal"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadLogs"
          @size-change="onLogSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * AI 调用 Token/费用统计页:
 * 指标卡 + 金额/Token 可切换的消耗柱状图(时间范围 7/30/90 天)+ 场景分布 + 最近调用明细。
 * 数据来自 GET /api/ai-usage/stats(汇总+每日序列+场景)与 /api/ai-usage/logs(明细)。
 */
import { onActivated, ref } from 'vue'
import request from '../api'
import { formatDateTime, formatNumber } from '../utils/format'
import UsageBarChart from '../components/UsageBarChart.vue'

const loading = ref(false)
const days = ref(30)
const metric = ref('cost') // 'cost' 金额 | 'token' Token
const summary = ref({ calls: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0, cost: 0 })
const series = ref([])
const scenes = ref([])
const logs = ref([])
const logPage = ref(1)
const logSize = ref(20)
const logTotal = ref(0)

async function loadStats() {
  loading.value = true
  try {
    const stats = await request.get('/ai-usage/stats', { params: { days: days.value } })
    summary.value = stats.summary || summary.value
    series.value = stats.series || []
    scenes.value = stats.scenes || []
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  try {
    const res = await request.get('/ai-usage/logs', { params: { page: logPage.value, size: logSize.value } })
    logs.value = res.items || []
    logTotal.value = res.total || 0
  } catch { /* 拦截器已提示 */ }
}

/** 每页条数变化:回到第一页再加载 */
function onLogSizeChange() {
  logPage.value = 1
  loadLogs()
}

/** 金额格式:大额整数,小额保留足够精度 */
function formatCost(v) {
  if (v === null || v === undefined) return '-'
  if (v >= 1000) return '¥' + v.toFixed(0)
  if (v >= 1) return '¥' + v.toFixed(2)
  return '¥' + v.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

onActivated(() => {
  loadStats()
  loadLogs()
})
</script>

<style scoped>
.usage-body {
  min-height: 300px;
}
.metric-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.metric-card {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.metric-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.cost-value {
  color: var(--el-color-danger);
}
.metric-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.metric-note {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chart-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 20px;
}
.chart-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
  gap: 8px;
}
.chart-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.chart-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chart-legend {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
}
.dot-prompt { background: var(--el-color-primary); }
.dot-completion { background: var(--el-color-success); }
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin: 20px 0 10px;
}
.usage-table {
  width: 100%;
}
.log-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}
</style>
