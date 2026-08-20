<template>
  <div class="activate-page">
    <el-card class="activate-card">
      <h2 class="activate-title">数据质量检测工具</h2>

      <template v-if="status && status.activated && !status.expired">
        <el-result icon="success" title="已激活">
          <template #sub-title>
            授权给 {{ status.customer }},{{ status.expiresAt ? `有效期至 ${status.expiresAt}(剩余 ${status.daysLeft} 天)` : '永久有效' }}
            <template v-if="status.username">,用户 {{ status.username }}</template>
            <template v-if="status.sid">,SID {{ status.sid }}</template>
            <template v-if="status.timestamp">,签发于 {{ formatTimestamp(status.timestamp) }}</template>
          </template>
        </el-result>
        <div class="activate-actions">
          <el-button type="primary" @click="goHome">进入系统</el-button>
          <el-button link type="primary" @click="showRenew = !showRenew">更换授权码</el-button>
        </div>
      </template>

      <template v-else>
        <el-alert
          v-if="status && status.expired"
          type="warning"
          :closable="false"
          :title="`授权已于 ${status.expiresAt} 到期,请输入新授权码续期`"
          style="margin-bottom: 16px"
        />
        <el-alert
          v-else
          type="info"
          :closable="false"
          title="请输入授权码激活后使用"
          style="margin-bottom: 16px"
        />
      </template>

      <template v-if="!status || !status.activated || status.expired || showRenew">
        <el-input
          v-model="code"
          type="textarea"
          :rows="4"
          placeholder="粘贴授权码(DQ1. 开头)"
          style="margin-bottom: 16px"
        />
        <el-button type="primary" :loading="submitting" :disabled="!code.trim()" style="width: 100%" @click="onActivate">
          激活
        </el-button>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'
import { markActivated } from '../router'

const router = useRouter()
const status = ref(null)
// 内测版:授权码输入框默认填入内测授权码,打开激活页即可直接点「激活」
const code = ref('DQ1.5YaF6YOo5rWL6K-VfFBFUk1BTkVOVHwxLjh8fHwwMWQyYzA3NzI1N2I0YTcyYTU3NDgxNDc4YmZmYWE3ZXwxNzg2NDY3OTg4MjM1fHNjYW4sZGF0YXNvdXJjZSxleGNlbCxyZXBvcnQsYWlfZG9jLGFpX3RhZyx0YWc.8Yz5TdM5PQ1fjiFtTuacqpeGr_cEqWWrBj1sd5iQ-rs0oMEP92YbpDm0Mdfv9efKFiofJ0hX1xl_AQft6M1wBQ')
const submitting = ref(false)
const showRenew = ref(false)

onMounted(async () => {
  status.value = await request.get('/license/status')
})

async function onActivate() {
  submitting.value = true
  try {
    status.value = await request.post('/license/activate', { code: code.value })
    code.value = ''
    showRenew.value = false
    markActivated(status.value)
    ElMessage.success('激活成功')
    router.push('/')
  } finally {
    submitting.value = false
  }
}

function goHome() {
  router.push('/')
}

/** 授权码签发时间(epoch 毫秒)转本地时间串 */
function formatTimestamp(ts) {
  return new Date(Number(ts)).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.activate-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--dq-page-bg);
}
.activate-card {
  width: 480px;
}
.activate-title {
  margin: 0 0 20px;
  text-align: center;
}
.activate-actions {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
