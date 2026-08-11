import { reactive } from 'vue'

/**
 * 顶部页签状态(轻量实现,未引入 pinia)
 * tab: { key, title, path, closable }
 *  - key   页签身份:每个数据源一个页签,库→表/扫描记录在其内下钻;
 *          扫描任务详情每个任务一个页签
 *  - path  页签当前停留的路由 fullPath,切换页签时恢复
 *  - title 随路由动态计算,反映页签当前停留的页面(库列表/表列表/扫描记录/扫描详情/字段统计)
 * 一级功能页(数据源/任务看板/标记统计/导出任务)不占页签,由侧边栏导航直接切换
 */
export const tabState = reactive({
  tabs: [],
  activeKey: '',
  // 侧边栏「数据源」操作下拉命令(new/import/export):由 App.vue 写入,数据源页消费后清空。
  // 不用路由 query 传递——App.vue 的 keep-alive 以 route.fullPath 为 key,query 变化会导致组件重挂载,弹框状态丢失。
  pendingDsDialog: ''
})

// 路由里拿不到名称时的兜底缓存:数据源 id -> 数据源名,任务 id -> 库名标签,任务 id -> 数据源 id
const dsNames = reactive({})
const scanSchemas = reactive({})
const scanDsIds = reactive({})

/** 页面从接口拿到数据源名后回写,供页签标题和面包屑使用 */
export function setDsName(id, name) {
  if (name) dsNames[id] = name
}

/** 读取缓存的数据源名(在 computed 中调用可响应式更新) */
export function getDsName(id) {
  return dsNames[id] || ''
}

/** 数据源名兜底解析:缓存未命中时按 id 从 /datasources 列表获取并回写缓存(同 id 并发只发一次请求)。
 * 只做尽力而为的补全——失败/找不到时返回空串,调用方维持「数据源 ${id}」兜底显示,不弹错误提示。
 * 用裸 fetch 而非 api 封装,避免失败时触发全局错误提示。 */
const dsNamePending = new Map()

export function ensureDsName(id) {
  if (dsNames[id]) return Promise.resolve(dsNames[id])
  let p = dsNamePending.get(id)
  if (!p) {
    p = fetch('/api/datasources')
      .then((res) => (res.ok ? res.json() : []))
      .then((list) => {
        const ds = (list || []).find((d) => String(d.id) === String(id))
        if (ds && ds.name) {
          dsNames[id] = ds.name
          return ds.name
        }
        return ''
      })
      .catch(() => '')
      .finally(() => dsNamePending.delete(id))
    dsNamePending.set(id, p)
  }
  return p
}

/** 任务详情页从接口拿到库名后回写,供页签标题使用 */
export function setScanSchema(jobId, schema) {
  if (schema) scanSchemas[jobId] = schema
}

/** 任务详情页从接口拿到数据源 id 后回写,供关闭页签时跳回父级数据源页签 */
export function setScanDs(jobId, dsId) {
  if (dsId) scanDsIds[jobId] = dsId
}

/** 库名标签:有数据库实例时拼成 db.schema */
function schemaLabel(route) {
  const schema = route.params.schema || route.query.schema || ''
  return route.query.db ? `${route.query.db}.${schema}` : schema
}

/** 根据路由解析所属页签及标题;不属于任何页签的路由(一级功能页、/ 重定向中间态、/license-admin 等)返回 null */
function resolveTab(route) {
  const p = route.path
  if (p.startsWith('/datasources/')) {
    const id = route.params.id
    if (route.query.name) dsNames[id] = route.query.name
    const dsName = route.query.name || dsNames[id] || `数据源 ${id}`
    let title = `${dsName} - 库列表`
    if (p.endsWith('/tables')) title = `${schemaLabel(route)} - 表列表`
    else if (p.includes('/tables/')) title = `${route.params.tableName} - 字段明细`
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
  // 不匹配任何页签的路由(如启动时 START_LOCATION 的 '/':若会话落在非首页路由,
  // 兜底归到首页会把 home.path 污染成 '/',导致点击「首页」跳到 '/';/license-admin 同理)
  return null
}

/** 路由变化后同步页签:不存在则新建,存在则更新停留位置和标题;路由不属于任何页签时不动 */
export function syncTab(route) {
  const meta = resolveTab(route)
  if (!meta) return
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
    // 关闭扫描任务页签时,优先跳回其来源数据源页签(而非相邻页签)
    if (key.startsWith('scan-')) {
      const jobId = key.slice(5)
      const dsId = scanDsIds[jobId]
      if (dsId) {
        const dsTab = tabState.tabs.find((t) => t.key === `ds-${dsId}`)
        if (dsTab) {
          tabState.activeKey = dsTab.key
          return dsTab.path
        }
      }
    }
    // 关闭后无任何页签时回到数据源一级页(侧边栏可重新下钻)
    if (tabState.tabs.length === 0) {
      tabState.activeKey = ''
      return '/datasources'
    }
    const next = tabState.tabs[Math.min(idx, tabState.tabs.length - 1)]
    tabState.activeKey = next.key
    return next.path
  }
  return null
}
