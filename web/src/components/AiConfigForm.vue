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
    <el-form-item label="连接测试">
      <el-button size="small" :loading="testing" @click="test">测试连接</el-button>
      <span class="test-hint">按当前填写内容测试接口可用性(未保存也可)</span>
    </el-form-item>
  </el-form>
</template>

<script setup>
/**
 * AI 大模型接口配置表单(共用组件):「AI 配置」弹窗与「系统设置」页共用的编辑表单。
 * GET/PUT /api/ai-config;apiKey 明文不回传,仅 hasKey 标记。
 * 由父级调 load() 拉取、save() 保存(返回是否成功);保存成功 emit('saved')。
 */
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'

const emit = defineEmits(['saved'])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const hasKey = ref(false)
const form = reactive({ baseUrl: '', apiKey: '', model: '' })

async function load() {
  loading.value = true
  try {
    const cfg = await request.get('/ai-config')
    form.baseUrl = cfg.baseUrl || ''
    form.model = cfg.model || ''
    form.apiKey = '' // 明文不回传,留空表示不修改
    hasKey.value = !!cfg.hasKey
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
    await request.put('/ai-config', { baseUrl: form.baseUrl, apiKey: form.apiKey, model: form.model })
    ElMessage.success('已保存')
    emit('saved')
    return true
  } finally {
    saving.value = false
  }
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
</style>
