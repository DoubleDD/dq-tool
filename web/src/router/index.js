import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/datasources' },
  { path: '/dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/datasources', component: () => import('../views/Datasources.vue') },
  { path: '/datasources/:id/schemas', component: () => import('../views/Schemas.vue') },
  { path: '/datasources/:id/schemas/:schema/tables', component: () => import('../views/Tables.vue') },
  { path: '/datasources/:id/schemas/:schema/scans', component: () => import('../views/Scans.vue') },
  { path: '/scans/:jobId', component: () => import('../views/ScanDetail.vue') },
  { path: '/scans/:jobId/tables/:tableName', component: () => import('../views/TableColumns.vue') }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
