<template>
  <el-container class="layout" direction="vertical">
    <el-header class="header" height="50px">
      <span class="title">数据质量检测工具</span>
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
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tabState, syncTab, closeTab } from './stores/tabs'

const route = useRoute()
const router = useRouter()

watch(() => route.fullPath, () => syncTab(route), { immediate: true })

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
/* 布局骨架与组件细化见 style.css(全局设计令牌) */
</style>
