<template>
  <el-button v-if="!buttonless" @click="open">AI 配置</el-button>
  <el-dialog v-model="visible" title="AI 配置" width="520px" append-to-body>
    <div style="margin-bottom: 12px; color: var(--el-text-color-secondary); font-size: 13px">
      用于生成「表说明」的大模型接口,任意 OpenAI 兼容服务均可(DeepSeek / 通义 / 本地 vLLM 等)。
      生成时只发送表结构元数据(表名、字段、注释),不发送业务数据。
    </div>
    <el-form label-width="100px" v-loading="loading">
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
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'

defineProps({
  // 无按钮模式:由父级通过 ref 调用 open() 触发(用于收入「更多」下拉)
  buttonless: { type: Boolean, default: false }
})
const visible = ref(false)
const loading = ref(false)
const saving = ref(false)
const hasKey = ref(false)
const form = reactive({ baseUrl: '', apiKey: '', model: '' })

async function open() {
  visible.value = true
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
    return
  }
  if (!hasKey.value && !form.apiKey.trim()) {
    ElMessage.warning('请填写 API Key')
    return
  }
  saving.value = true
  try {
    await request.put('/ai-config', { baseUrl: form.baseUrl, apiKey: form.apiKey, model: form.model })
    ElMessage.success('已保存')
    visible.value = false
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>
