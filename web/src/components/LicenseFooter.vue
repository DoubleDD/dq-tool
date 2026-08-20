<template>
  <!-- 已激活实例显示授权信息与更换授权码入口;版本号始终显示(授权码管理入口在侧边栏,管理员 + 授权码含 license_admin 功能时可见) -->
  <div v-if="status && (status.activated || status.admin)" class="license-footer">
    <span v-if="status.activated" :class="{ 'license-expiring': expiringSoon, 'license-critical': expiringCritical }">
      <!-- 方案 B:客户 · 用户 · 有效期;SID/签发时间等细节见激活页 -->
      {{ status.customer }}
      <template v-if="status.username"> · {{ status.username }}</template> ·
      <template v-if="status.expiresAt">有效期至 {{ status.expiresAt }}(剩 {{ status.daysLeft }} 天)</template>
      <template v-else>永久有效</template>
    </span>
    <el-button v-if="status.activated" link type="primary" size="small" @click="openDialog">更换授权码</el-button>

    <el-dialog v-model="dialogVisible" title="更换授权码" width="520px" destroy-on-close>
      <el-input v-model="code" type="textarea" :rows="4" placeholder="粘贴新授权码(DQ1. 开头)" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!code.trim()" @click="onActivate">激活</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'
import { fetchLicenseStatus, markActivated } from '../router'

const status = ref(null)
const dialogVisible = ref(false)
const code = ref('')
const submitting = ref(false)

/** 剩余 30 天内视为临期,橙色提醒;最后 7 天红色 */
const expiringSoon = computed(() => status.value && status.value.daysLeft != null && status.value.daysLeft <= 30)
const expiringCritical = computed(() => status.value && status.value.daysLeft != null && status.value.daysLeft <= 7)
onMounted(load)

async function load() {
  // 复用路由守卫的缓存请求,进首页不再重复调 status 接口
  status.value = await fetchLicenseStatus()
}

function openDialog() {
  code.value = ''
  dialogVisible.value = true
}
async function onActivate() {
  submitting.value = true
  try {
    status.value = await request.post('/license/activate', { code: code.value })
    markActivated(status.value)
    dialogVisible.value = false
    ElMessage.success('授权码已更新')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
/* 全局底部授权信息条:挂在 App.vue 主内容区(el-main)下方,不随页面滚动;高度固定,主区 flex 收缩 */
.license-footer {
  flex-shrink: 0;
  padding: 8px 20px;
  /* 无背景无上边框,融入整体页面背景 */
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--dq-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.license-expiring {
  color: var(--el-color-warning);
}
.license-critical {
  color: var(--el-color-danger);
  font-weight: 600;
}
.license-version {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}
</style>
