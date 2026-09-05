<template>
  <!-- 全局语言配置:按需引入后原 app.use(ElementPlus, { locale }) 不再全量注册,locale 改走组件方式 -->
  <el-config-provider :locale="zhCn">
    <!-- 激活页为全屏独立页,不渲染框架(侧边栏/页签栏) -->
    <div v-if="route.path === '/activate'" class="activate-wrap">
      <router-view />
    </div>
    <el-container v-else class="layout">
    <!-- 侧边栏:一级功能导航(「数据源」为可展开树,含新增/导入/导出下拉;下钻页高亮对应数据源;「数据源」一级页不占页签,其余一级功能页各占一个固定页签) -->
    <el-aside :width="sidebarWidth" :class="['sidebar', { 'sidebar-collapsed': sidebarCollapsed, 'sidebar-resizing': sidebarResizing }]">
      <div class="sidebar-brand">
        <template v-if="!sidebarCollapsed">
          <span class="brand-logo">
            <el-icon><Coin /></el-icon>
          </span>
          <span class="brand-name">数据质量检测工具</span>
        </template>
        <span class="brand-toggle" :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'" @click="toggleSidebar">
          <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
        </span>
      </div>
      <el-menu :default-active="activeNav" :default-openeds="openeds" class="sidebar-menu"
        :collapse="sidebarCollapsed"
        @select="onMenuSelect" @open="onMenuOpen" @close="onMenuClose">
        <!-- 数据源:可展开的数据源树 + 标题右侧操作下拉(新增/导入/导出) -->
        <el-sub-menu index="ds-root" class="ds-root">
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
          <!-- 数据源列表可能很长:限高滚动,「全部数据源」固定在可视区不随列表滚动;
               命名分组渲染为可折叠子菜单,未分组的数据源平铺在末尾 -->
          <div class="ds-list">
            <template v-for="g in groupedDatasources" :key="g.key">
              <el-sub-menu v-if="g.key !== UNGROUPED_GROUP" :index="'ds-group:' + g.key" class="ds-group-sub">
                <template #title>
                  <el-icon><Folder /></el-icon>
                  <span class="ds-name">{{ g.name }}</span>
                  <span class="ds-group-count">{{ g.items.length }}</span>
                </template>
                <el-menu-item v-for="ds in g.items" :key="ds.id" :index="`/datasources/${ds.id}/schemas`">
                  <DbTypeIcon :type="ds.dbType" :size="15" />
                  <span class="ds-name">{{ ds.name }}</span>
                  <span class="ds-edit" title="编辑数据源" @click.stop="onEditDs(ds)">
                    <el-icon><EditPen /></el-icon>
                  </span>
                </el-menu-item>
              </el-sub-menu>
              <template v-else>
                <el-menu-item v-for="ds in g.items" :key="ds.id" :index="`/datasources/${ds.id}/schemas`">
                  <DbTypeIcon :type="ds.dbType" :size="15" />
                  <span class="ds-name">{{ ds.name }}</span>
                  <span class="ds-edit" title="编辑数据源" @click.stop="onEditDs(ds)">
                    <el-icon><EditPen /></el-icon>
                  </span>
                </el-menu-item>
              </template>
            </template>
          </div>
          <el-menu-item v-if="!datasources.length" index="/datasources" class="ds-empty">
            <span>暂无数据源,点 ⋮ 新增</span>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item v-for="item in otherNav" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <span v-if="item.dev" class="dev-badge">dev</span>
        </el-menu-item>
        <el-menu-item v-if="isAdmin && hasFeature('license_admin')" index="/license-admin">
          <el-icon><Key /></el-icon>
          <span>授权管理</span>
          <span class="dev-badge">dev</span>
        </el-menu-item>
      </el-menu>
      <!-- 宽度拖拽手柄:贴右边框,左右拖动调整侧边栏宽度(收起态隐藏,由收起按钮恢复) -->
      <div v-if="!sidebarCollapsed" class="sidebar-resizer" title="拖动调整宽度" @mousedown="startSidebarResize" />
    </el-aside>
    <el-container direction="vertical">
      <el-header class="header" height="48px">
        <!-- 浏览器式导航:返回/前进基于路由历史(无历史置灰),刷新为软刷新(重挂载当前页,不丢页签状态) -->
        <div class="nav-buttons">
          <el-tooltip content="返回" placement="bottom">
            <span>
              <el-button text circle :disabled="!navState.canBack" @click="goBack">
                <el-icon><Back /></el-icon>
              </el-button>
            </span>
          </el-tooltip>
          <el-tooltip content="前进" placement="bottom">
            <span>
              <el-button text circle :disabled="!navState.canForward" @click="goForward">
                <el-icon><Right /></el-icon>
              </el-button>
            </span>
          </el-tooltip>
          <el-tooltip content="刷新" placement="bottom">
            <el-button text circle @click="refreshPage">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <!-- 页签栏内嵌头栏:下钻页(库/表/字段/任务详情)与数据源外的一级功能页占用,全部关闭后隐藏 -->
        <div v-if="tabState.tabs.length" class="tab-bar">
          <el-tabs v-model="tabState.activeKey" type="card" @tab-click="onTabClick" @tab-remove="onTabRemove">
            <el-tab-pane v-for="t in tabState.tabs" :key="t.key" :name="t.key" :closable="t.closable">
              <template #label><span class="tab-label">{{ t.title }}</span></template>
            </el-tab-pane>
          </el-tabs>
        </div>
        <el-tooltip :content="`主题:${themeModeText}(点击切换)`" placement="bottom">
          <el-button class="theme-toggle" text circle @click="cycleTheme">
            <el-icon><Monitor v-if="themeState.mode === 'auto'" /><Sunny v-else-if="themeState.mode === 'light'" /><Moon v-else /></el-icon>
          </el-button>
        </el-tooltip>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <keep-alive :max="20">
            <component :is="Component" :key="`${route.fullPath}#${refreshStamp}`" />
          </keep-alive>
        </router-view>
      </el-main>
      <!-- 全局底部授权信息条:所有页面可见(客户/用户名/有效期/版本号 + 更换授权码);授权码管理入口在侧边栏 -->
      <LicenseFooter />
    </el-container>
    </el-container>
  </el-config-provider>
</template>

<script setup>
import { computed, watch, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import request from './api'
import { Coin, Document, Download, EditPen, Expand, Files, FirstAidKit, Fold, Folder, Grid, Key, Monitor, MoreFilled, Odometer, PriceTag, Setting, Star, Sunny, Moon, TrendCharts, Back, Right, Refresh } from '@element-plus/icons-vue'
import { tabState, syncTab, closeTab } from './stores/tabs'
import { themeState, initTheme, cycleTheme } from './stores/theme'
import { fetchLicenseStatus } from './router'
import LicenseFooter from './components/LicenseFooter.vue'
import DbTypeIcon from './components/DbTypeIcon.vue'
import { loadDsFavorites, sortDsByFavorite, DS_FAVORITES_CHANGED_EVENT } from './utils/dsFavorites'
import { DS_LIST_CHANGED_EVENT } from './utils/dsListChanged'

// 恢复上次主题(需在挂载早期执行,避免首帧闪烁)
initTheme()

const THEME_MODE_TEXT = { auto: '跟随系统', light: '浅色', dark: '深色' }
const themeModeText = computed(() => THEME_MODE_TEXT[themeState.mode])

const route = useRoute()
const router = useRouter()

// 「数据源」为特殊导航(可展开树 + 操作下拉);其余一级功能项均为普通功能,恒显示
const licenseFeatures = ref([])
function hasFeature(key) {
  return licenseFeatures.value.includes(key)
}
const otherNav = computed(() => {
  const navs = [
    { path: '/dashboard', label: '扫描记录', icon: Odometer },
    { path: '/tags', label: '标记统计', icon: PriceTag },
    { path: '/manual-collects', label: '人工采集', icon: Star },
    { path: '/report-exports', label: '报告列表', icon: Download },
    { path: '/sample-exports', label: '抽样导出', icon: Files },
    { path: '/sql-console', label: 'SQL 控制台', icon: Monitor },
    { path: '/ai-usage', label: '模型用量统计', icon: TrendCharts },
    { path: '/settings', label: '系统设置', icon: Setting },
    { path: '/diagnostics', label: '系统诊断', icon: FirstAidKit },
    { path: '/logs', label: '运行日志', icon: Document }
  ]
  return navs
})

// 浏览器式返回/前进/刷新(头栏):可否回退/前进读 vue-router 写入的 history.state,
// 每次导航后(afterEach 覆盖 push/replace/back/forward)刷新;刷新为软刷新——
// 递增 refreshStamp 改变 keep-alive key 强制重挂载当前页(组件 mounted 逻辑重新拉数),
// 不做 location.reload()(硬刷新会丢失内存页签状态并重走启动轮询)
const navState = reactive({ canBack: false, canForward: false })
const refreshStamp = ref(0)
function updateNavState() {
  navState.canBack = !!window.history.state?.back
  navState.canForward = !!window.history.state?.forward
}
const removeAfterEach = router.afterEach(updateNavState)
onMounted(updateNavState)
onUnmounted(removeAfterEach)
function goBack() {
  if (navState.canBack) router.back()
}
function goForward() {
  if (navState.canForward) router.forward()
}
function refreshPage() {
  refreshStamp.value++
}

// 侧边栏收起/展开(持久化到 localStorage)
const sidebarCollapsed = ref(localStorage.getItem('dq-sidebar-collapsed') === 'true')
// 侧边栏宽度(可拖拽调整,持久化):最小 160px,最大为视口宽度的 40%(随窗口缩放动态计算)
const SIDEBAR_MIN_WIDTH = 160
const maxSidebarWidth = () => Math.round(window.innerWidth * 0.4)
const sidebarWidthPx = ref(Number(localStorage.getItem('dq-sidebar-width')) || 200)
const sidebarResizing = ref(false)
const sidebarWidth = computed(() =>
  sidebarCollapsed.value ? '64px' : Math.min(sidebarWidthPx.value, maxSidebarWidth()) + 'px'
)
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  localStorage.setItem('dq-sidebar-collapsed', String(sidebarCollapsed.value))
}

/** 侧边栏宽度拖拽:命中手柄后全局跟踪 mousemove,松手持久化;拖拽期间禁用宽度过渡避免跟手延迟 */
function startSidebarResize(e) {
  e.preventDefault()
  const startX = e.clientX
  const startWidth = sidebarWidthPx.value
  sidebarResizing.value = true
  // 拖拽期间禁止文本选中、统一光标(移到手柄外也保持)
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  const onMove = (ev) => {
    sidebarWidthPx.value = Math.min(maxSidebarWidth(), Math.max(SIDEBAR_MIN_WIDTH, startWidth + ev.clientX - startX))
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    sidebarResizing.value = false
    localStorage.setItem('dq-sidebar-width', String(sidebarWidthPx.value))
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

// 侧边栏数据源树:进入数据源相关页/展开「数据源」子菜单时刷新(静默失败,失败维持旧列表)
const datasources = ref([])
// 收藏 id 数组(localStorage 本地偏好):与主界面同一排序——收藏的在前,同收藏按收藏时间倒序
const dsFavorites = ref(loadDsFavorites())
const sortedDatasources = computed(() => sortDsByFavorite(datasources.value, dsFavorites.value))
// 未分组哨兵 key(与 Datasources.vue 同一约定)
const UNGROUPED_GROUP = '__ungrouped__'
/** 侧边栏数据源按分组成组:命名分组按名称排序渲染为可折叠子菜单,未分组平铺在末尾;组内保持收藏置顶顺序 */
const groupedDatasources = computed(() => {
  const map = new Map()
  sortedDatasources.value.forEach((ds) => {
    const key = ds.groupName || UNGROUPED_GROUP
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(ds)
  })
  return [...map.entries()]
    .map(([key, items]) => ({ key, name: key === UNGROUPED_GROUP ? '未分组' : key, items }))
    .sort((a, b) => {
      if (a.key === UNGROUPED_GROUP) return 1
      if (b.key === UNGROUPED_GROUP) return -1
      return a.key.localeCompare(b.key, 'zh')
    })
})
async function loadDatasources() {
  dsFavorites.value = loadDsFavorites()
  try {
    const res = await fetch(`/api/datasources?_t=${Date.now()}`)
    if (res.ok) datasources.value = await res.json()
  } catch { /* 后端不可达时维持旧列表,不弹错误 */ }
}
// 主界面切换收藏时即时刷新排序,不等下次路由进入
window.addEventListener(DS_FAVORITES_CHANGED_EVENT, onDsFavoritesChanged)
onUnmounted(() => window.removeEventListener(DS_FAVORITES_CHANGED_EVENT, onDsFavoritesChanged))
function onDsFavoritesChanged() {
  dsFavorites.value = loadDsFavorites()
}
// 数据源列表页新增/编辑/删除/导入后即时刷新侧边栏菜单(分组结构可能已变)
window.addEventListener(DS_LIST_CHANGED_EVENT, onDsListChanged)
onUnmounted(() => window.removeEventListener(DS_LIST_CHANGED_EVENT, onDsListChanged))
function onDsListChanged() {
  loadDatasources()
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
//  /scans/* 任务详情/字段统计 → 高亮「扫描记录」(任务域)
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
  if (p === '/manual-collects' || p.startsWith('/manual-collects/')) return '/manual-collects'
  if (p === '/ai-usage' || p.startsWith('/ai-usage/')) return '/ai-usage'
  if (p === '/report-exports' || p.startsWith('/report-exports/')) return '/report-exports'
  if (p === '/sample-exports' || p.startsWith('/sample-exports/')) return '/sample-exports'
  if (p === '/sql-console' || p.startsWith('/sql-console/')) return '/sql-console'
  if (p === '/settings' || p.startsWith('/settings/')) return '/settings'
  if (p === '/logs' || p.startsWith('/logs/')) return '/logs'
  if (p === '/diagnostics' || p.startsWith('/diagnostics/')) return '/diagnostics'
  if (p.startsWith('/license-admin')) return '/license-admin'
  return '/datasources'
})

// 下钻到数据源时确保「数据源」树展开;高亮的数据源在某个分组里时同步展开该分组
watch(activeNav, (v) => {
  if (v !== '/datasources' && !v.startsWith('/datasources/')) return
  if (!openeds.value.includes('ds-root')) {
    openeds.value = [...openeds.value, 'ds-root']
  }
  if (v.startsWith('/datasources/')) {
    const id = v.split('/')[2]
    const ds = datasources.value.find((d) => String(d.id) === String(id))
    const gkey = ds?.groupName ? 'ds-group:' + ds.groupName : null
    if (gkey && !openeds.value.includes(gkey)) {
      openeds.value = [...openeds.value, gkey]
    }
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
// 菜单项右侧编辑图标:写入待编辑数据源 id 并跳数据源列表页,由 Datasources.vue 消费(pendingDsEditId)
function onEditDs(ds) {
  tabState.pendingDsEditId = String(ds.id)
  router.push('/datasources')
}

// 授权管理入口仅管理员实例 + 授权码包含 license_admin 功能可见(复用路由守卫的缓存请求)
const isAdmin = ref(false)
async function refreshLicenseMenus() {
  const status = await fetchLicenseStatus()
  isAdmin.value = !!status.admin
  licenseFeatures.value = status.features || []
}
onMounted(refreshLicenseMenus)

// 新版本首启自动打开「本次更新」页签:版本号变化(升级后第一次打开)且 CHANGELOG 含当前版本条目时触发。
// dq-seen-version 记录上次已展示「本次更新」的版本;先写回再判断,避免接口失败/用户中途关页导致重复弹。
// dev 版本(本地开发)与未激活实例跳过(激活页不渲染主框架,守卫也会拦路由)
const SEEN_VERSION_KEY = 'dq-seen-version'
async function checkWhatsNew() {
  try {
    const status = await fetchLicenseStatus()
    const version = status.appVersion
    if (!version || version === 'dev' || !(status.activated && !status.expired)) return
    if (localStorage.getItem(SEEN_VERSION_KEY) === version) return
    localStorage.setItem(SEEN_VERSION_KEY, version)
    const view = await request.get('/changelog')
    const hasEntry = (view.entries || []).some((e) => e.version === view.currentVersion)
    if (hasEntry) router.push('/whats-new')
  } catch { /* 静默失败:不打扰正常使用;版本号已先写回,接口异常不会重复弹 */ }
}
onMounted(checkWhatsNew)
// 更换授权码成功后(Activate/LicenseFooter 经 markActivated 广播)整体刷新侧边栏:
// 授权功能可能变化(license_admin 入口增删),先重取状态再刷新数据源树
window.addEventListener('dq-license-changed', onLicenseChanged)
onUnmounted(() => window.removeEventListener('dq-license-changed', onLicenseChanged))
async function onLicenseChanged() {
  await refreshLicenseMenus()
  loadDatasources()
  // 当前页可能因新授权失去入口权限(如正在授权管理页而新码不含 license_admin),主动跳回首页,
  // 不等下次路由守卫拦截(授权管理页留着一个永远 403 的界面没有意义)
  const features = licenseFeatures.value
  const lostAdmin = route.path.startsWith('/license-admin') && !(isAdmin.value && features.includes('license_admin'))
  if (lostAdmin) {
    router.replace('/')
  }
}

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

