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
  { path: '/scans/:jobId/tables/:tableName', component: () => import('../views/TableColumns.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 授权状态缓存:null=未检查,true=已激活未过期,false=需跳激活页 */
let licenseOk = null

/** 激活成功后由激活页调用,避免守卫再次查状态 */
export function markActivated() {
  licenseOk = true
}

// 注意用原生 fetch 而非 api/index.js 的 axios 实例,避免与拦截器/守卫循环依赖
async function ensureLicenseChecked() {
  if (licenseOk !== null) return licenseOk
  try {
    const res = await fetch('/api/license/status')
    const data = await res.json()
    licenseOk = !!(data.activated && !data.expired)
  } catch {
    // 后端不可达时放行,让页面里的 API 错误提示正常展示
    licenseOk = true
  }
  return licenseOk
}

router.beforeEach(async (to) => {
  if (to.path === '/activate') return true
  const ok = await ensureLicenseChecked()
  return ok ? true : '/activate'
})

export default router
