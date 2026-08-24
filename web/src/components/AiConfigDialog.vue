<template>
  <el-button v-if="!buttonless" @click="open">AI 配置</el-button>
  <el-dialog v-model="visible" title="AI 配置" width="520px" append-to-body>
    <div style="margin-bottom: 12px; color: var(--el-text-color-secondary); font-size: 13px">
      用于生成「表说明」的大模型接口,任意 OpenAI 兼容服务均可(DeepSeek / 通义 / 本地 vLLM 等)。
      生成时只发送表结构元数据(表名、字段、注释),不发送业务数据。
      全局配置也可在「系统设置」页统一维护。
    </div>
    <AiConfigForm ref="formRef" @saved="onSaved" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import AiConfigForm from './AiConfigForm.vue'

defineProps({
  // 无按钮模式:由父级通过 ref 调用 open() 触发(用于收入「更多」下拉)
  buttonless: { type: Boolean, default: false }
})
const visible = ref(false)
const formRef = ref(null)

async function open() {
  visible.value = true
  await formRef.value?.load()
}

async function save() {
  await formRef.value?.save()
}

function onSaved() {
  // 保存成功统一由 form 提示,弹窗在此关闭
  visible.value = false
}

defineExpose({ open })
</script>
