<template>
  <!-- 激活页为全屏独立页,不渲染框架(侧边栏/页签栏) -->
  <div v-if="route.path === '/activate'" class="activate-wrap">
    <router-view />
  </div>
  <el-container v-else class="layout">
    <!-- 侧边栏:一级功能导航(「数据源」为可展开树,含新增/导入/导出下拉;下钻页高亮对应数据源;一级页面不占页签) -->
    <el-aside width="200px" class="sidebar">
      <div class="sidebar-brand">
        <span class="brand-name">数据质量检测工具</span>
      </div>
      <el-menu :default-active="activeNav" :default-openeds="openeds" class="sidebar-menu"
        @select="onMenuSelect" @open="onMenuOpen" @close="onMenuClose">
        <!-- 数据源:可展开的数据源树 + 标题右侧操作下拉(新增/导入/导出) -->
        <el-sub-menu index="ds-root">
          <template #title>
            <el-icon><Coin /></el-icon>
            <span>数据源</span>
            <!-- 操作下拉:整体 stop 冒泡,避免点击触发 sub-menu 展开;用 popover 自绘菜单(不用 el-dropdown——
                 el-dropdown 会在 trigger 外包一层 wrapper,点击 wrapper 空白区会冒泡到 title 触发展开) -->
            <span class="ds-more" @click.stop>
              <el-popover v-model:visible="dsMoreVisible" trigger="click" placement="bottom-start"
                :width="150" popper-class="ds-more-pop" :show-arrow="false">
                <template #reference>
                  <el-icon class="ds-more-btn"><MoreFilled /></el-icon>
                </template>
                <div class="ds-more-menu">
                  <div class="ds-more-item" @click="onDsCommand('new')">新增数据源</div>
                  <div class="ds-more-item" @click="onDsCommand('import')">导入配置</div>
                  <div class="ds-more-item" @click="onDsCommand('export')">导出配置(JSON)</div>
                </div>
              </el-popover>
            </span>
          </template>
          <el-menu-item index="/datasources" class="ds-all">
            <el-icon><Grid /></el-icon>
            <span>全部数据源</span>
          </el-menu-item>
          <el-menu-item v-for="ds in datasources" :key="ds.id" :index="`/datasources/${ds.id}/schemas`">
            <DbTypeIcon :type="ds.dbType" :size="15" />
            <span class="ds-name">{{ ds.name }}</span>
          </el-menu-item>
          <el-menu-item v-if="!datasources.length" index="/datasources" class="ds-empty">
            <span>暂无数据源,点 ⋮ 新增</span>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item v-for="item in otherNav" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
        <el-menu-item v-if="isAdmin" index="/license-admin">
          <el-icon><Key /></el-icon>
          <span>授权管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container direction="vertical">
      <el-header class="header" height="48px">
        <span class="header-title">{{ activeNavLabel }}</span>
        <el-tooltip :content="`主题:${themeModeText}(点击切换)`" placement="bottom">
          <el-button class="theme-toggle" text circle @click="cycleTheme">
            <el-icon><Monitor v-if="themeState.mode === 'auto'" /><Sunny v-else-if="themeState.mode === 'light'" /><Moon v-else /></el-icon>
          </el-button>
        </el-tooltip>
      </el-header>
      <!-- 页签栏:仅下钻页(库/表/字段/任务详情)占用,全部关闭后整条隐藏 -->
      <div v-if="tabState.tabs.length" class="tab-bar">
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
  </el-container>
</template>

<script setup>
import { computed, watch, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { Coin, Download, Grid, Key, Monitor, MoreFilled, Odometer, PriceTag, Sunny, Moon } from '@element-plus/icons-vue'
import { tabState, syncTab, closeTab } from './stores/tabs'
import { themeState, initTheme, cycleTheme } from './stores/theme'
import { fetchLicenseStatus } from './router'
import DbTypeIcon from './components/DbTypeIcon.vue'

// 恢复上次主题(需在挂载早期执行,避免首帧闪烁)
initTheme()

const THEME_MODE_TEXT = { auto: '跟随系统', light: '浅色', dark: '深色' }
const themeModeText = computed(() => THEME_MODE_TEXT[themeState.mode])

const route = useRoute()
const router = useRouter()

// 「数据源」为特殊导航(可展开树 + 操作下拉),其余一级功能项
const otherNav = [
  { path: '/dashboard', label: '任务看板', icon: Odometer },
  { path: '/tags', label: '标记统计', icon: PriceTag },
  { path: '/report-exports', label: '导出任务', icon: Download }
]

// 侧边栏数据源树:进入数据源相关页/展开「数据源」子菜单时刷新(静默失败,失败维持旧列表)
const datasources = ref([])
async function loadDatasources() {
  try {
    const res = await fetch('/api/datasources')
    if (res.ok) datasources.value = await res.json()
  } catch { /* 后端不可达时维持旧列表,不弹错误 */ }
}

// 「数据源」子菜单展开状态:默认展开;下钻到数据源时强制展开
const openeds = ref(['ds-root'])
function onMenuOpen(index) {
  if (!openeds.value.includes(index)) openeds.value = [...openeds.value, index]
  if (index === 'ds-root') loadDatasources()
}
function onMenuClose(index) {
  openeds.value = openeds.value.filter((i) => i !== index)
}

// 当前路由高亮的一级导航:
//  /datasources/:id/* 下钻页(库/表/字段/扫描记录)→ 高亮对应数据源项(自动展开「数据源」树)
//  /datasources 列表页 → 高亮「全部数据源」
//  /scans/* 任务详情/字段统计 → 高亮「任务看板」(任务域)
const activeNav = computed(() => {
  const p = route.path
  if (p === '/datasources') return '/datasources'
  if (p.startsWith('/datasources/')) {
    const id = route.params.id
    return id ? `/datasources/${id}/schemas` : '/datasources'
  }
  if (p === '/scans' || p.startsWith('/scans/')) return '/dashboard'
  if (p === '/dashboard' || p.startsWith('/dashboard/')) return '/dashboard'
  if (p === '/tags' || p.startsWith('/tags/')) return '/tags'
  if (p === '/report-exports' || p.startsWith('/report-exports/')) return '/report-exports'
  if (p.startsWith('/license-admin')) return '/license-admin'
  return '/datasources'
})

// 下钻到数据源时确保「数据源」树展开
watch(activeNav, (v) => {
  if ((v === '/datasources' || v.startsWith('/datasources/')) && !openeds.value.includes('ds-root')) {
    openeds.value = [...openeds.value, 'ds-root']
  }
})

// 菜单点击:数据源项带 name 进库列表,其余一级项直达
function onMenuSelect(index) {
  if (index.startsWith('/datasources/')) {
    const id = index.split('/')[2]
    const ds = datasources.value.find((d) => String(d.id) === String(id))
    router.push({ path: index, query: { name: ds?.name } })
  } else {
    router.push(index)
  }
}
// 「数据源」操作下拉(popover):写入待弹框命令并跳数据源列表页,由 Datasources.vue 消费(pendingDsDialog)
const dsMoreVisible = ref(false)
function onDsCommand(cmd) {
  dsMoreVisible.value = false
  tabState.pendingDsDialog = cmd
  router.push('/datasources')
}

// 顶栏左侧显示当前一级功能名,给位置感
const activeNavLabel = computed(() => {
  if (activeNav.value === '/datasources' || activeNav.value.startsWith('/datasources/')) return '数据源'
  const item = otherNav.find((n) => n.path === activeNav.value)
  if (item) return item.label
  if (activeNav.value === '/license-admin') return '授权管理'
  return ''
})

// 授权管理入口仅管理员实例可见(复用路由守卫的缓存请求)
const isAdmin = ref(false)
onMounted(async () => {
  const status = await fetchLicenseStatus()
  isAdmin.value = !!status.admin
})

// 侧边栏数据源树初始加载;进入数据源相关页(增删改后回来)时刷新
onMounted(loadDatasources)
watch(
  () => route.path,
  (p) => {
    if (p === '/datasources' || p.startsWith('/datasources/')) loadDatasources()
  }
)

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

