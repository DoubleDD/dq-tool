<template>
  <el-form label-width="100px" v-loading="loading || saving">
    <el-form-item label="接口地址">
      <el-input v-model="form.baseUrl" placeholder="如 https://api.deepseek.com 或 http://localhost:11434/v1" />
    </el-form-item>
    <el-form-item label="API Key">
      <el-input v-model="form.apiKey" type="password" show-password
                :placeholder="hasKey ? '已配置,留空则不修改' : '请输入 API Key'" />
    </el-form-item>
    <el-form-item label="模型">
      <el-input v-model="form.model" placeholder="如 deepseek-chat / qwen-plus" />
    </el-form-item>

    <el-form-item label="计费价格">
      <div class="price-block">
        <!-- 峰谷计价开关:关闭时只用单一价格,不区分时段 -->
        <div class="price-row">
          <span class="price-label price-label-wide">峰谷计价</span>
          <el-switch v-model="form.peakValleyEnabled" size="small" />
          <span class="price-unit">关闭后仅按单一价格计费,不区分时段</span>
        </div>

        <!-- 开启:两档价格(工作时间/非工作时间)+ 工作时间段 + 周末 -->
        <template v-if="form.peakValleyEnabled">
          <div class="price-row">
            <span class="price-label price-label-wide">工作时间(高峰)</span>
            <span class="price-cell">输入价 <el-input-number v-model="form.peakInputPrice" :min="0" :precision="2"
              :step="0.5" :controls="false" size="small" /></span>
            <span class="price-cell">输出价 <el-input-number v-model="form.peakOutputPrice" :min="0" :precision="2"
              :step="0.5" :controls="false" size="small" /></span>
            <span class="price-unit">元/百万 token</span>
          </div>
          <div class="price-row">
            <span class="price-label price-label-wide">非工作时间(谷价)</span>
            <span class="price-cell">输入价 <el-input-number v-model="form.valleyInputPrice" :min="0" :precision="2"
              :step="0.5" :controls="false" size="small" /></span>
            <span class="price-cell">输出价 <el-input-number v-model="form.valleyOutputPrice" :min="0" :precision="2"
              :step="0.5" :controls="false" size="small" /></span>
            <span class="price-unit">元/百万 token</span>
          </div>

          <div class="period-row">
            <span class="price-label price-label-wide">工作时间段</span>
            <div class="period-list">
              <div v-for="(p, i) in form.workPeriods" :key="i" class="period-item">
                <el-time-select v-model="p.start" start="00:00" end="23:30" step="00:30" size="small"
                                placeholder="开始" style="width: 108px" />
                <span class="price-unit">~</span>
                <el-time-select v-model="p.end" start="00:00" end="23:30" step="00:30" size="small"
                                placeholder="结束" style="width: 108px" />
                <el-button link type="danger" :icon="Delete" :disabled="form.workPeriods.length <= 1"
                           title="删除该时段" @click="removePeriod(i)" />
              </div>
              <el-button link type="primary" :icon="Plus" @click="addPeriod">添加时段</el-button>
            </div>
          </div>

          <div class="price-row">
            <span class="price-label price-label-wide">周末按谷价</span>
            <el-switch v-model="form.weekendValley" size="small" />
            <span class="price-unit">周六/日全天按谷价计费</span>
          </div>

          <div class="price-hint">
            工作时间段(如 09:00-12:00、14:00-18:00)内按高峰价,其余时间与周末按谷价;时间段可添加多段。
            默认价为 DeepSeek 官方价(2026-08 起,旗舰 V4-Pro):工作时间输入 9 元/输出 27 元,非工作时间输入 4.5
            元/输出 13.5 元(每百万 token);使用其他模型请按实际价格修改。
          </div>
        </template>

        <!-- 关闭:单一价格 -->
        <template v-else>
          <div class="price-row">
            <span class="price-label price-label-wide">输入价</span>
            <el-input-number v-model="form.peakInputPrice" :min="0" :precision="2" :step="0.5"
                             :controls="false" size="small" />
            <span class="price-unit">元/百万 token</span>
          </div>
          <div class="price-row">
            <span class="price-label price-label-wide">输出价</span>
            <el-input-number v-model="form.peakOutputPrice" :min="0" :precision="2" :step="0.5"
                             :controls="false" size="small" />
            <span class="price-unit">元/百万 token</span>
          </div>
          <div class="price-hint">
            按单一输入/输出价计费,不区分工作时间与周末。
            默认价为 DeepSeek 官方价(2026-08 起,旗舰 V4-Pro):输入 9 元、输出 27 元(每百万 token);
            使用其他模型请按实际价格修改。
          </div>
        </template>
      </div>
    </el-form-item>

    <el-form-item label="连接测试">
      <el-button size="small" :loading="testing" @click="test">测试连接</el-button>
      <span class="test-hint">按当前填写内容测试接口可用性(未保存也可)</span>
    </el-form-item>
  </el-form>
</template>

<script setup>
/**
 * AI 大模型接口配置表单(「系统设置」页使用)。
 * GET/PUT /api/ai-config;apiKey 明文不回传,仅 hasKey 标记。
 * 计费价格按「工作时间(高峰)/ 非工作时间(谷价)」两档,工作时间段可多段(元素 "HH:mm-HH:mm")。
 * 由父级调 load() 拉取、save() 保存(返回是否成功);保存成功 emit('saved')。
 */
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import request from '../api'

const emit = defineEmits(['saved'])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const hasKey = ref(false)
const form = reactive({
  baseUrl: '',
  apiKey: '',
  model: '',
  peakValleyEnabled: true,
  peakInputPrice: 9,
  peakOutputPrice: 27,
  valleyInputPrice: 4.5,
  valleyOutputPrice: 13.5,
  workPeriods: [{ start: '09:00', end: '12:00' }, { start: '14:00', end: '18:00' }],
  weekendValley: true
})

async function load() {
  loading.value = true
  try {
    const cfg = await request.get('/ai-config')
    form.baseUrl = cfg.baseUrl || ''
    form.model = cfg.model || ''
    form.apiKey = '' // 明文不回传,留空表示不修改
    hasKey.value = !!cfg.hasKey
    // 价格字段为合并默认值后的有效值(未配置时即 DeepSeek 默认价)
    form.peakValleyEnabled = cfg.peakValleyEnabled ?? true
    form.peakInputPrice = cfg.peakInputPrice ?? 9
    form.peakOutputPrice = cfg.peakOutputPrice ?? 27
    form.valleyInputPrice = cfg.valleyInputPrice ?? 4.5
    form.valleyOutputPrice = cfg.valleyOutputPrice ?? 13.5
    form.weekendValley = cfg.weekendValley ?? true
    form.workPeriods = (cfg.workPeriods || []).map((p) => {
      const [s, e] = String(p).split('-')
      return { start: s || '09:00', end: e || '18:00' }
    })
    if (!form.workPeriods.length) form.workPeriods.push({ start: '09:00', end: '18:00' })
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.baseUrl.trim() || !form.model.trim()) {
    ElMessage.warning('接口地址和模型不能为空')
    return false
  }
  if (!hasKey.value && !form.apiKey.trim()) {
    ElMessage.warning('请填写 API Key')
    return false
  }
  saving.value = true
  try {
    await request.put('/ai-config', {
      baseUrl: form.baseUrl,
      apiKey: form.apiKey,
      model: form.model,
      peakValleyEnabled: form.peakValleyEnabled,
      peakInputPrice: form.peakInputPrice,
      peakOutputPrice: form.peakOutputPrice,
      valleyInputPrice: form.valleyInputPrice,
      valleyOutputPrice: form.valleyOutputPrice,
      workPeriods: form.workPeriods.map((p) => `${p.start}-${p.end}`),
      weekendValley: form.weekendValley
    })
    ElMessage.success('已保存')
    emit('saved')
    return true
  } finally {
    saving.value = false
  }
}

function addPeriod() {
  form.workPeriods.push({ start: '09:00', end: '18:00' })
}
function removePeriod(i) {
  form.workPeriods.splice(i, 1)
}

/** 测试连接:按当前表单值(未保存也可)调用 POST /api/ai-config/test;失败由响应拦截器统一提示 */
async function test() {
  if (!form.baseUrl.trim() || !form.model.trim()) {
    ElMessage.warning('接口地址和模型不能为空')
    return
  }
  if (!hasKey.value && !form.apiKey.trim()) {
    ElMessage.warning('请填写 API Key')
    return
  }
  testing.value = true
  try {
    const res = await request.post('/ai-config/test', { baseUrl: form.baseUrl, apiKey: form.apiKey, model: form.model })
    ElMessage.success(res.message || '连接成功')
  } finally {
    testing.value = false
  }
}

defineExpose({ load, save, test })
</script>

<style scoped>
.test-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.price-block {
  width: 100%;
}
.price-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-bottom: 10px;
}
.price-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}
.price-label-wide {
  min-width: 96px;
}
.price-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}
.price-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.period-row {
  display: flex;
  align-items: flex-start;
  gap: 8px 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.period-row .price-label-wide {
  line-height: 30px;
  min-width: 96px;
}
.period-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.period-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.price-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  margin-top: 4px;
}
</style>
