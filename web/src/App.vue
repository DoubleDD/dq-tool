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
.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
}
.header .title {
  font-size: 16px;
  font-weight: 600;
}
.tab-bar {
  background: #fff;
  padding: 6px 12px 0;
  border-bottom: 1px solid #e4e7ed;
}
.tab-bar .el-tabs {
  --el-tabs-header-height: 34px;
}
.tab-bar .el-tabs__header {
  margin: 0;
  border-bottom: none;
}
.main {
  background: #f0f2f5;
}
.page-card {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
