/**
 * 后台任务中心:统一跟踪关系推导(relation-infer)与数据比对(compare)两类后台长任务。
 *
 * - 任务种类注册表 KINDS:每种任务一个适配器(fetch 活动清单/toRow 行渲染/notify 终态文案与落点),
 *   新任务类型(扫描/元数据同步/报告导出)接入只需加一个适配器;
 * - 单 poll 循环:存在活动任务时 1s 一轮并发拉各 kind 的 active 接口,全部终态即停,零任务零开销;
 *   App 挂载时先拉一次,兜住「提交后刷新页面」的场景;单个 kind 故障(如 compare 未授权 403)静默跳过,不炸循环;
 * - 终态检测:上一轮仍在活动清单、本轮消失的任务,回源详情接口定 DONE/FAILED 后经 utils/notify.js
 *   弹通知(可点击直达结果现场)并留痕通知中心铃铛;
 * - ack 去重:页面自行渲染到任务终态(推导弹窗/CompareTasks/CompareDiff)先 ackTask(kind, id),
 *   跟踪器跳过已 ack 的任务不再弹通知(1s 轮询同窗内可能有漏网,属可接受 race,不追求强一致)。
 */
import { reactive } from 'vue'
import router, { fetchLicenseStatus } from '../router'
import request, { listActiveCompareJobs, listActiveInferJobs, getCompareJob } from '../api'
import { ElMessage } from '../utils/notify'

const POLL_INTERVAL = 1000

// 推导阶段枚举 → 中文名(与 RelationInferDialog 同款口径)
const INFER_STAGE_TEXT = { NAME_MATCH: '名字匹配', SEMANTIC_TABLE: '语义筛选', SEMANTIC_COLUMN: '字段精判', VERIFY: '值验证' }

/** 推导结果落点:表字段明细页 ER 页签(带 db query,多库方言需要;tab=er 为现有落点) */
function inferLink(j) {
  const db = j.dbName ? `?db=${encodeURIComponent(j.dbName)}&tab=er` : '?tab=er'
  return `/datasources/${j.datasourceId}/schemas/${encodeURIComponent(j.schemaName)}/tables/${encodeURIComponent(j.anchorTable)}${db}`
}

/** 任务种类注册表:key = kind,适配器见文件头注释 */
const KINDS = {
  'relation-infer': {
    label: '关系推导',
    fetch: listActiveInferJobs,
    toRow: (j) => ({
      key: `relation-infer:${j.id}`,
      kind: 'relation-infer',
      id: j.id,
      raw: j,
      title: `锚点表 ${j.anchorTable}`,
      locate: [j.dbName, j.schemaName].filter(Boolean).join('.'),
      stage: j.status === 'PENDING' ? '排队中' : (INFER_STAGE_TEXT[j.stage] || '准备中'),
      percent: j.totalSteps ? Math.min(100, Math.round((j.doneSteps / j.totalSteps) * 100)) : 0,
      steps: `${j.doneSteps}/${j.totalSteps}`,
      extra: j.foundCount ? `已发现 ${j.foundCount} 条候选` : '',
      link: inferLink(j),
      startedAt: j.startedAt
    }),
    notify: (j) => j.status === 'DONE'
      ? { type: 'success', title: '推导完成', text: `锚点表 ${j.anchorTable}:发现 ${j.foundCount} 条候选关系,点击前往 ER 关系确认`, link: inferLink(j) }
      : { type: 'error', title: '推导失败', text: `锚点表 ${j.anchorTable}:${(j.error || '').slice(0, 200) || '未知错误'}` }
  },
  compare: {
    label: '数据比对',
    fetch: listActiveCompareJobs,
    toRow: (j) => ({
      key: `compare:${j.id}`,
      kind: 'compare',
      id: j.id,
      raw: j,
      title: j.name,
      locate: [j.baseDb, j.baseSchema, j.baseTable].filter(Boolean).join('.'),
      stage: j.stage || '比对中',
      percent: j.progressPercent || 0,
      steps: `${j.doneUnits}/${j.totalUnits}`,
      extra: j.targetTotal ? `目标 ${j.targetDone}/${j.targetTotal}` : '',
      link: `/compare/${j.id}/diff`,
      startedAt: j.startedAt
    }),
    notify: (j) => j.status === 'DONE'
      ? { type: 'success', title: '比对完成', text: `任务「${j.name}」已完成,点击前往差异明细查看结果`, link: `/compare/${j.id}/diff` }
      : { type: 'error', title: '比对失败', text: `任务「${j.name}」:${(j.error || '').slice(0, 200) || '未知错误'}` }
  }
}

/** 活动任务统一视图行(BackgroundTasksDrawer 与 App.vue 头栏指示器共用) */
export const backgroundTasks = reactive({ list: [] })

// 已被页面自行消化终态的任务 key('kind:id'),全局通知跳过;终态处理完即清,防止无限增长
const acked = new Set()
let prevKeys = new Set() // 上一轮活动任务的 key 集合:消失 = 进入终态
let timer = null
let ticking = false // 防重入:上一轮未跑完不叠下一轮

/** 消失任务回源详情定终态(静默,失败按 null 处理不通知) */
function fetchTerminal(kind, id) {
  return kind === 'compare'
    ? getCompareJob(id, true).then((d) => d?.job).catch(() => null)
    : request.get(`/relation-infer-jobs/${id}`, { _silent: true }).catch(() => null)
}

async function tick() {
  if (ticking) return
  ticking = true
  try {
    const results = await Promise.all(Object.entries(KINDS).map(async ([kind, k]) => {
      try {
        return [kind, await k.fetch()]
      } catch {
        return [kind, null] // 单 kind 故障(compare 未授权 403/会话过期等)静默跳过
      }
    }))
    const rows = []
    const curKeys = new Set()
    for (const [kind, list] of results) {
      if (!Array.isArray(list)) continue
      for (const j of list) {
        curKeys.add(`${kind}:${j.id}`)
        rows.push(KINDS[kind].toRow(j))
      }
    }
    backgroundTasks.list = rows
    // 消失检测:上一轮在、本轮不在 → 已终态,未 ack 的弹通知
    for (const key of prevKeys) {
      if (curKeys.has(key)) continue
      const sep = key.indexOf(':')
      const [kind, id] = [key.slice(0, sep), Number(key.slice(sep + 1))]
      const detail = await fetchTerminal(kind, id)
      const status = detail?.status
      if (status === 'DONE' || status === 'FAILED') {
        if (!acked.has(key)) {
          const n = KINDS[kind].notify(detail)
          if (n) {
            ElMessage[n.type](n.text, {
              title: n.title,
              onClick: n.link ? () => router.push(n.link) : undefined
            })
          }
        }
        acked.delete(key)
      }
    }
    prevKeys = curKeys
    if (rows.length === 0) stopPolling()
  } finally {
    ticking = false
  }
}

function startPolling() {
  if (!timer) timer = setInterval(tick, POLL_INTERVAL)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** App 挂载时调用一次:先拉一轮(兜住「提交后刷新页面」),有活动任务才开 1s 轮询 */
export async function initBackgroundTasks() {
  // 未激活/已过期实例:业务接口一律 401(激活页同样过不了授权前置校验),这里直接跳过首轮拉取——
  // 否则激活页一挂载就打到 401,axios 拦截器把整页重定向到 /activate,构成「激活页反复消失又出现」
  // 的死循环(2026-09 首装未激活实例实测),用户连授权码都输不进去。
  // 复用路由守卫的授权状态缓存(同一次请求);后端不可达时 fetchLicenseStatus 放行,口径同守卫
  const status = await fetchLicenseStatus()
  if (!(status.activated && !status.expired)) return
  await tick()
  if (backgroundTasks.list.length) startPolling()
}

/** 业务提交后台任务后调用:确保轮询在跑(任务下个 tick 即出现在 list) */
export function watchTask() {
  if (!timer) startPolling()
}

/** 页面自行渲染到任务终态时调用(推导弹窗/CompareTasks/CompareDiff),全局通知为其去重 */
export function ackTask(kind, id) {
  acked.add(`${kind}:${id}`)
}
