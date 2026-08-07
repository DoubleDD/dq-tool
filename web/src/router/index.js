import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/datasources' },
  { path: '/activate', component: () => import('../views/Activate.vue') },
  { path: '/dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/tags', component: () => import('../views/TagStats.vue') },
  { path: '/datasources', component: () => import('../views/Datasources.vue') },
  { path: '/datasources/:id/schemas', component: () => import('../views/Schemas.vue') },
  { path: '/datasources/:id/schemas/:schema/tables', component: () => import('../views/Tables.vue') },
  { path: '/datasources/:id/schemas/:schema/scans', component: () => import('../views/Scans.vue') },
  { path: '/scans/:jobId', component: () => import('../views/ScanDetail.vue') },
  { path: '/scans/:jobId/tables/:tableName', component: () => import('../views/TableColumns.vue') },
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
    licenseStatusPromise = fetch('/api/license/status')
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
}

router.beforeEach(async (to) => {
  if (to.path === '/activate') return true
  const status = await fetchLicenseStatus()
  // 授权码管理页:仅管理员可见(未激活的管理员实例也放行);非管理员跳回首页
  if (to.path === '/license-admin') {
    return status.admin ? true : '/'
  }
  const ok = !!(status.activated && !status.expired)
  return ok ? true : '/activate'
})

export default router
