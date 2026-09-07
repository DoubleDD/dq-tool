import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from '../utils/notify'

const routes = [
  { path: '/', redirect: '/datasources' },
  { path: '/activate', component: () => import('../views/Activate.vue') },
  { path: '/dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/tags', component: () => import('../views/TagStats.vue') },
  { path: '/manual-collects', component: () => import('../views/ManualCollects.vue') },
  { path: '/ai-usage', component: () => import('../views/AiUsage.vue') },
  { path: '/report-exports', component: () => import('../views/ReportExports.vue') },
  { path: '/sample-exports', component: () => import('../views/SampleExports.vue') },
  { path: '/sql-console', component: () => import('../views/SqlConsole.vue') },
  { path: '/lan-share', component: () => import('../views/LanShare.vue') },
  { path: '/settings', component: () => import('../views/Settings.vue') },
  { path: '/logs', component: () => import('../views/Logs.vue') },
  { path: '/diagnostics', component: () => import('../views/Diagnostics.vue') },
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
    licenseStatusPromise = fetch(`/api/license/status?_t=${Date.now()}`)
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
  const features = status.features || []
  // 授权码管理页:仅管理员实例 + 授权码包含 license_admin 功能(未激活的管理员实例也放行);否则跳回首页
  if (to.path === '/license-admin') {
    return (status.admin && features.includes('license_admin')) ? true : '/'
  }
  const ok = !!(status.activated && !status.expired)
  return ok ? true : '/activate'
})

export default router
