import { reactive } from 'vue'

/**
 * 顶部页签状态(轻量实现,未引入 pinia)
 * tab: { key, title, path, closable }
 *  - key   页签身份:首页为固定页签;每个数据源一个页签,库→表/扫描记录在其内下钻;
 *          扫描任务详情每个任务一个页签
 *  - path  页签当前停留的路由 fullPath,切换页签时恢复
 *  - title 随路由动态计算,反映页签当前停留的页面(库列表/表列表/扫描记录/扫描详情/字段统计)
 */
export const tabState = reactive({
  tabs: [
    { key: 'home', title: '首页', path: '/datasources', closable: false },
    { key: 'dashboard', title: '任务看板', path: '/dashboard', closable: false }
  ],
  activeKey: 'home'
})

// 路由里拿不到名称时的兜底缓存:数据源 id -> 数据源名,任务 id -> 库名标签
const dsNames = reactive({})
const scanSchemas = reactive({})

/** 页面从接口拿到数据源名后回写,供页签标题使用 */
export function setDsName(id, name) {
  if (name) dsNames[id] = name
}

/** 任务详情页从接口拿到库名后回写,供页签标题使用 */
export function setScanSchema(jobId, schema) {
  if (schema) scanSchemas[jobId] = schema
}

/** 库名标签:有数据库实例时拼成 db.schema */
function schemaLabel(route) {
  const schema = route.params.schema || route.query.schema || ''
  return route.query.db ? `${route.query.db}.${schema}` : schema
}

/** 根据路由解析所属页签及标题 */
function resolveTab(route) {
  const p = route.path
  if (p === '/dashboard') {
    return { key: 'dashboard', title: '任务看板', closable: false }
  }
  if (p.startsWith('/datasources/')) {
    const id = route.params.id
    if (route.query.name) dsNames[id] = route.query.name
    const dsName = route.query.name || dsNames[id] || `数据源 ${id}`
    let title = `${dsName} - 库列表`
    if (p.endsWith('/tables')) title = `${schemaLabel(route)} - 表列表`
    else if (p.endsWith('/scans')) title = `${schemaLabel(route)} - 扫描记录`
    return { key: `ds-${id}`, title, closable: true }
  }
  if (p.startsWith('/scans/')) {
    const jobId = route.params.jobId
    if (route.query.schema) scanSchemas[jobId] = route.query.schema
    const schema = route.query.schema || scanSchemas[jobId] || ''
    let title = schema ? `${schema} - 扫描 #${jobId}` : `扫描 #${jobId}`
    if (p.includes('/tables/')) title = `${route.params.tableName} - 字段统计`
    return { key: `scan-${jobId}`, title, closable: true }
  }
  return { key: 'home', title: '首页', closable: false }
}

/** 路由变化后同步页签:不存在则新建,存在则更新停留位置和标题 */
export function syncTab(route) {
  const meta = resolveTab(route)
  let tab = tabState.tabs.find((t) => t.key === meta.key)
  if (!tab) {
    tab = { key: meta.key, title: meta.title, path: route.fullPath, closable: meta.closable }
    tabState.tabs.push(tab)
  } else {
    tab.path = route.fullPath
    tab.title = meta.title
  }
  tabState.activeKey = tab.key
}

/**
 * 关闭页签,返回需要跳转的 path(关闭的是当前页签时),否则返回 null
 */
export function closeTab(key) {
  const idx = tabState.tabs.findIndex((t) => t.key === key)
  if (idx === -1 || !tabState.tabs[idx].closable) return null
  tabState.tabs.splice(idx, 1)
  if (tabState.activeKey === key) {
    const next = tabState.tabs[Math.min(idx, tabState.tabs.length - 1)]
    tabState.activeKey = next.key
    return next.path
  }
  return null
}
