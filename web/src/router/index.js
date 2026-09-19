import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from '../utils/notify'
import { apiUrl, authHeaders } from '../api/base'

const routes = [
  { path: '/', redirect: '/datasources' },
  { path: '/activate', component: () => import('../views/Activate.vue') },
  { path: '/dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/tags', component: () => import('../views/TagStats.vue') },
  { path: '/manual-collects', component: () => import('../views/ManualCollects.vue') },
  { path: '/ai-usage', component: () => import('../views/AiUsage.vue') },
  { path: '/report-exports', component: () => import('../views/ReportExports.vue') },
  { path: '/sample-exports', component: () => import('../views/SampleExports.vue') },
  // 导出中心:全部导出入口统一登记可查(V66),授权恒显
  { path: '/export-center', component: () => import('../views/ExportCenter.vue') },
  // 数据比对:任务列表 / 新建(三步向导)/ 差异明细 / 质量报告
  { path: '/compare', component: () => import('../views/CompareTasks.vue') },
  { path: '/compare/new', component: () => import('../views/CompareNew.vue') },
  { path: '/compare/:id/diff', component: () => import('../views/CompareDiff.vue') },
  { path: '/compare/:id/report', component: () => import('../views/CompareReport.vue') },
  { path: '/relations', component: () => import('../views/RelationGraph.vue') },
  { path: '/object-manage', component: () => import('../views/ObjectManage.vue') },
  { path: '/sql-console', component: () => import('../views/SqlConsole.vue') },
  { path: '/lan-share', component: () => import('../views/LanShare.vue') },
  { path: '/settings', component: () => import('../views/Settings.vue') },
  { path: '/logs', component: () => import('../views/Logs.vue') },
  { path: '/diagnostics', component: () => import('../views/Diagnostics.vue') },
  // 错误中心:统一错误收集(前端 JS/接口 + 后端异常/数据库/任务/启动),与系统诊断同授权口径
  { path: '/errors', component: () => import('../views/Errors.vue') },
  // 更新日志:两路由共用组件,props.mode 区分(latest=本次更新,仅当前版本 / all=更新记录,全部历史版本)
  { path: '/whats-new', component: () => import('../views/Changelog.vue'), props: { mode: 'latest' } },
  { path: '/changelog', component: () => import('../views/Changelog.vue'), props: { mode: 'all' } },
  { path: '/datasources', component: () => import('../views/Datasources.vue') },
  { path: '/datasources/:id/schemas', component: () => import('../views/Schemas.vue') },
  { path: '/datasources/:id/schemas/:schema/tables', component: () => import('../views/Tables.vue') },
  { path: '/datasources/:id/schemas/:schema/tables/:tableName', component: () => import('../views/TableDetail.vue') },
  { path: '/datasources/:id/schemas/:schema/scans', component: () => import('../views/Scans.vue') },
  { path: '/scans/:jobId', component: () => import('../views/ScanDetail.vue') },
  { path: '/scans/:jobId/tables/:tableName', component: () => import('../views/TableDetail.vue') },
  // 授权码管理:仅管理员实例(配置了签发私钥),守卫按 status.admin 放行
  { path: '/license-admin', component: () => import('../views/LicenseAdmin.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 授权状态缓存(Promise,并发调用共享同一次请求):/api/license/status 响应(activated/expired/admin) */
let licenseStatusPromise = null

/**
 * 查询授权状态(带缓存,页面级消费者如 LicenseFooter 也用此方法,避免重复请求)。
 * 注意用原生 fetch 而非 api/index.js 的 axios 实例,避免与拦截器/守卫循环依赖。
 */
export function fetchLicenseStatus() {
  if (!licenseStatusPromise) {
    licenseStatusPromise = fetch(apiUrl('/license/status?_t=' + Date.now()), { headers: authHeaders() })
      .then((res) => res.json())
      .catch(() => {
        // 后端不可达时放行,让页面里的 API 错误提示正常展示
        return { activated: true, expired: false, admin: false }
      })
  }
  return licenseStatusPromise
}

/** 激活成功后由激活页/页脚调用:传入激活接口返回的最新状态替换缓存,避免守卫再次查状态 */
export function markActivated(newStatus) {
  if (newStatus) {
    licenseStatusPromise = Promise.resolve({ ...newStatus, activated: true, expired: false })
  } else if (licenseStatusPromise) {
    licenseStatusPromise = licenseStatusPromise.then((s) => ({ ...s, activated: true, expired: false }))
  }
  // 广播授权变化:侧边栏菜单按授权功能过滤(App.vue),换码成功后需整体重算
  window.dispatchEvent(new CustomEvent('dq-license-changed'))
}

/** 懒加载资源失败后已自动刷新过一次的本会话标记(sessionStorage,随页签关闭清除) */
const CHUNK_RELOAD_FLAG = 'dq-chunk-reload'

/** 一级菜单清单(顺序=侧边栏展示顺序,与后端 LicenseMenu 一致) */
const MENU_ITEMS = [
  { menu: 'datasource', path: '/datasources' },
  { menu: 'dashboard', path: '/dashboard' },
  { menu: 'tags', path: '/tags' },
  { menu: 'manual-collects', path: '/manual-collects' },
  { menu: 'report-exports', path: '/report-exports' },
  { menu: 'sample-exports', path: '/sample-exports' },
  { menu: 'export-center', path: '/export-center' },
  { menu: 'compare', path: '/compare' },
  { menu: 'relations', path: '/relations' },
  { menu: 'object-manage', path: '/object-manage' },
  { menu: 'sql-console', path: '/sql-console' },
  { menu: 'lan-share', path: '/lan-share' },
  { menu: 'ai-usage', path: '/ai-usage' },
  { menu: 'settings', path: '/settings' },
  { menu: 'diagnostics', path: '/diagnostics' },
  { menu: 'error-center', path: '/errors' },
  { menu: 'logs', path: '/logs' },
  { menu: 'license-admin', path: '/license-admin' }
]

/**
 * 已开放菜单 key 列表;兼容旧后端(响应无 menus 字段时按旧 features 推导,与后端 LicenseMenu 口径一致:
 * 旧码开放除 数据比对/授权管理 外的全部菜单,两者按旧功能段显式包含恢复)
 */
export function grantedMenus(status) {
  if (Array.isArray(status.menus)) return status.menus
  const legacy = status.features || []
  const menus = MENU_ITEMS.map((m) => m.menu).filter((k) => k !== 'compare' && k !== 'license-admin')
  if (legacy.includes('compare')) menus.push('compare')
  if (legacy.includes('license_admin')) menus.push('license-admin')
  return menus
}

/** 路由路径 → 授权菜单 key;数据源下钻页归属 datasource,扫描任务域归属 dashboard;非菜单页返回 null */
export function routeMenuKey(path) {
  if (path === '/datasources' || path.startsWith('/datasources/')) return 'datasource'
  if (path === '/scans' || path.startsWith('/scans/')) return 'dashboard'
  const hit = MENU_ITEMS.find((m) => path === m.path || path.startsWith(m.path + '/'))
  return hit ? hit.menu : null
}

/** 授权开放的首个菜单路径作首页(全部未开放时兜底 /datasources) */
export function firstGrantedHome(menus) {
  return MENU_ITEMS.find((m) => menus.includes(m.menu))?.path || '/datasources'
}

router.onError((error) => {
  const msg = String((error && error.message) || error)
  // 懒加载 chunk/CSS 加载失败:多为前端发版后旧页面仍开着,旧 hash 资源在新构建中已不存在。
  // vue-router 默认把它吞成未捕获 Promise,表现为「点按钮没反应」——自动整页刷新一次换新资源;
  // 本会话已刷新过仍失败则改为提示,避免反复刷新
  if (!/dynamically imported module|Unable to preload CSS|module script failed/i.test(msg)) return
  if (sessionStorage.getItem(CHUNK_RELOAD_FLAG)) {
    // grouping: 一次失败导航可能同时报 JS chunk 与 CSS preload 两个错,相同提示合并为一条
    ElMessage({ type: 'error', grouping: true, message: '页面资源加载失败,请关闭窗口重新打开' })
    return
  }
  sessionStorage.setItem(CHUNK_RELOAD_FLAG, '1')
  window.location.reload()
})

router.beforeEach(async (to) => {
  if (to.path === '/activate') return true
  // 系统诊断页:未激活/过期也放行(排错场景常是授权问题本身;敏感操作仍由后端逐项校验)
  if (to.path === '/diagnostics') return true
  const status = await fetchLicenseStatus()
  const menus = grantedMenus(status)
  // 授权码管理页:仅管理员实例 + 授权码开放 license-admin 菜单(未激活的管理员实例也放行);否则跳回首页
  if (to.path === '/license-admin') {
    return status.admin && menus.includes('license-admin') ? true : firstGrantedHome(menus)
  }
  const ok = !!(status.activated && !status.expired)
  if (!ok) return '/activate'
  // 菜单级授权:未开放菜单的页面直接输入 URL 也跳回首个开放菜单页(后端接口另有 403 兜底)
  const key = routeMenuKey(to.path)
  if (key && !menus.includes(key)) {
    const home = firstGrantedHome(menus)
    return home === to.path ? true : home
  }
  return true
})

export default router
