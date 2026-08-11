<template>
  <!-- 激活页为全屏独立页,不渲染框架(头部/页签栏) -->
  <div v-if="route.path === '/activate'" class="activate-wrap">
    <router-view />
  </div>
  <el-container v-else class="layout" direction="vertical">
    <el-header class="header" height="50px">
      <span class="title">数据质量检测工具</span>
      <el-tooltip :content="`主题:${themeModeText}(点击切换)`" placement="bottom">
        <el-button class="theme-toggle" text circle @click="cycleTheme">
          <el-icon><Monitor v-if="themeState.mode === 'auto'" /><Sunny v-else-if="themeState.mode === 'light'" /><Moon v-else /></el-icon>
        </el-button>
      </el-tooltip>
    </el-header>
    <div class="tab-bar">
      <el-tabs v-model="tabState.activeKey" type="card" @tab-click="onTabClick" @tab-remove="onTabRemove">
        <el-tab-pane v-for="t in tabState.tabs" :key="t.key" :name="t.key" :closable="t.closable">
          <template #label>{{ t.title }}</template>
        </el-tab-pane>
      </el-tabs>
    </div>
    <el-main class="main">
      <router-view v-slot="{ Component }">
        <keep-alive :max="20">
          <component :is="Component" :key="route.fullPath" />
        </keep-alive>
      </router-view>
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { Sunny, Moon, Monitor } from '@element-plus/icons-vue'
import { tabState, syncTab, closeTab } from './stores/tabs'
import { themeState, initTheme, cycleTheme } from './stores/theme'

// 恢复上次主题(需在挂载早期执行,避免首帧闪烁)
initTheme()

const THEME_MODE_TEXT = { auto: '跟随系统', light: '浅色', dark: '深色' }
const themeModeText = computed(() => THEME_MODE_TEXT[themeState.mode])

const route = useRoute()
const router = useRouter()

watch(() => route.fullPath, () => {
  if (route.path !== '/activate') syncTab(route)
}, { immediate: true })

// 页面心跳:桌面安装版(--app 窗口)的后端看门狗据此判断窗口是否已关闭,超时未收到心跳则退出进程。
// 用裸 axios 绕过全局拦截器:后端已退出时的连接失败不应弹错误提示
let heartbeatTimer
onMounted(() => {
  heartbeatTimer = setInterval(() => {
    axios.get('/api/heartbeat', { timeout: 5000 }).catch(() => {})
  }, 5000)
})
onUnmounted(() => clearInterval(heartbeatTimer))

function onTabClick(pane) {
  const tab = tabState.tabs.find((t) => t.key === pane.props.name)
  if (tab && tab.path !== route.fullPath) {
    router.push(tab.path)
  }
}

function onTabRemove(key) {
  const next = closeTab(key)
  if (next) router.push(next)
}
</script>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
}
.layout {
  height: 100%;
}
.activate-wrap {
  height: 100%;
}
/* 布局骨架与组件细化见 style.css(全局设计令牌) */
</style>

