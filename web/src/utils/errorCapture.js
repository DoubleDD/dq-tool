/**
 * 全局前端错误采集:把浏览器里任何地方产生的错误统一上报到后端「错误中心」。
 *
 * 覆盖范围(对应后端 /api/errors/report):
 *  - window 'error'                 → JS 运行时错误 + 资源加载失败(capture 阶段)
 *  - window 'unhandledrejection'    → 未捕获的 Promise 拒绝
 *  - Vue app.config.errorHandler    → 组件渲染/生命周期/事件处理里的异常
 *  - console.error 包装             → 业务/三方库主动打的错误日志
 *  - axios 响应拦截器(api/index.js)→ 接口 4xx/5xx 与网络错误
 *
 * 防刷屏与自保:
 *  - 同指纹 3s 窗口内只上报一次;本地队列攒批(满 10 条或 1s)合并发送,单批最多 50 条;
 *  - 上报请求失败静默丢弃(绝不再触发上报,避免递归);
 *  - 明确的噪音(ResizeObserver 循环、路由重复导航、401/503 这类状态响应)直接忽略。
 *
 * 上报失败/字段缺失都不能影响业务页面,所有入口 try/catch 兜底。
 */
import { apiUrl, authHeaders } from '../api/base'

/** 本地待上报队列上限;超出丢弃最旧(错误中心有后端聚合,前端不追求一条不漏) */
const MAX_QUEUE = 50
/** 攒批阈值:达到即发送 */
const BATCH_SIZE = 10
/** 攒批定时器间隔 */
const FLUSH_INTERVAL_MS = 1000
/** 同指纹去重窗口 */
const DEDUP_WINDOW_MS = 3000
const MAX_MESSAGE = 2000
const MAX_STACK = 8000
const MAX_CONTEXT = 4000
const MAX_ROUTE = 500

/** 明确噪音,不进错误中心(仍在控制台原样输出,便于开发排查) */
const NOISE_PATTERNS = [
  /ResizeObserver loop/i,
  /Avoided redundant navigation/i,
  /NavigationDuplicated/i,
  /^Script error\.?$/
]

const queue = []
const recentFingerprints = new Map()
let flushTimer = null
let sending = false
let installed = false
let currentRouter = null
/** 上报自身执行期间置位:console.error 包装据此跳过,防止 self-report 递归 */
let internal = false

/** 原始 console.error:包装后仍要原样输出,并供内部日志绕过包装使用 */
let rawConsoleError = typeof console !== 'undefined' ? console.error.bind(console) : () => {}

/** 字符串截断(后端也会截断,这里先省流量) */
function cut(text, max) {
  if (text == null) return null
  const s = String(text)
  return s.length <= max ? s : s.slice(0, max)
}

/** 当前页面路由(vue-router 可用时取 fullPath,否则退回 location) */
function currentRoute() {
  try {
    const r = currentRouter?.currentRoute?.value
    if (r?.fullPath) return r.fullPath
  } catch { /* 路由未就绪,退回地址栏 */ }
  return window.location.pathname + window.location.search
}

/** 采集上下文:出错页面/浏览器等排错必需信息(不含任何令牌) */
function buildContext(extra) {
  const lines = []
  lines.push('页面: ' + currentRoute())
  lines.push('地址: ' + window.location.href)
  if (extra) {
    for (const [k, v] of Object.entries(extra)) {
      if (v !== undefined && v !== null && v !== '') lines.push(k + ': ' + v)
    }
  }
  if (typeof navigator !== 'undefined' && navigator.userAgent) {
    lines.push('浏览器: ' + navigator.userAgent)
  }
  return cut(lines.join('\n'), MAX_CONTEXT)
}

/** 轻量指纹(仅前端本地去重用;后端按归一化消息 + 栈帧计算真正的聚合指纹) */
function fingerprint(kind, message, stack) {
  const firstFrame = String(stack || '').split('\n').find((l) => l.trim().startsWith('at ')) || ''
  const raw = `${kind}|${String(message || '').slice(0, 200)}|${firstFrame.trim()}`
  let hash = 5381
  for (let i = 0; i < raw.length; i++) hash = ((hash << 5) + hash + raw.charCodeAt(i)) | 0
  return String(hash)
}

function isNoise(message) {
  const text = String(message || '').trim()
  return NOISE_PATTERNS.some((re) => re.test(text))
}

/**
 * 上报一条前端错误(唯一出口);内部完成去重、入队与攒批。
 * @param {{kind?: string, level?: string, message: string, stack?: string, context?: string}} payload
 */
export function reportError(payload) {
  if (internal || !payload || !payload.message) return
  try {
    if (isNoise(payload.message)) return
    const fp = fingerprint(payload.kind, payload.message, payload.stack)
    const now = Date.now()
    if (now - (recentFingerprints.get(fp) || 0) < DEDUP_WINDOW_MS) return
    recentFingerprints.set(fp, now)
    // 顺手清理过期指纹,避免 Map 无限增长
    for (const [k, t] of recentFingerprints) {
      if (now - t > DEDUP_WINDOW_MS) recentFingerprints.delete(k)
    }
    if (queue.length >= MAX_QUEUE) queue.shift()
    queue.push({
      kind: cut(payload.kind || 'JS_ERROR', 128),
      level: payload.level || 'ERROR',
      message: cut(payload.message, MAX_MESSAGE),
      stack: cut(payload.stack, MAX_STACK),
      route: cut(payload.route || currentRoute(), MAX_ROUTE),
      context: cut(payload.context || buildContext(), MAX_CONTEXT),
      logger: cut(payload.logger || 'frontend', 255)
    })
    scheduleFlush()
  } catch { /* 采集自身异常绝不能影响业务 */ }
}

function scheduleFlush() {
  if (queue.length >= BATCH_SIZE) {
    flush()
    return
  }
  if (flushTimer != null) return
  flushTimer = setTimeout(() => {
    flushTimer = null
    flush()
  }, FLUSH_INTERVAL_MS)
}

/** 批量发送队列里的错误;失败静默丢弃(不重试、不再报错,避免递归与刷屏) */
function flush() {
  if (sending || queue.length === 0) return
  const batch = queue.splice(0, 50)
  sending = true
  internal = true
  try {
    fetch(apiUrl('/errors/report'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ events: batch }),
      // keepalive:页面正在卸载/刷新时也让请求发出去
      keepalive: true
    }).catch(() => { /* 上报失败静默 */ }).finally(() => {
      sending = false
      internal = false
    })
  } catch {
    sending = false
    internal = false
  }
}

/** 提取任意 rejection reason / console 参数里的可读消息与堆栈 */
function describe(value) {
  if (value instanceof Error) {
    return { kind: value.name || 'Error', message: value.message || String(value), stack: value.stack }
  }
  if (typeof value === 'string') return { kind: 'Error', message: value, stack: undefined }
  try {
    return { kind: 'Error', message: JSON.stringify(value), stack: undefined }
  } catch {
    return { kind: 'Error', message: String(value), stack: undefined }
  }
}

/** 安装全局采集;在 app.mount 之前调用一次 */
export function installErrorCapture(app, router) {
  if (installed) return
  installed = true
  currentRouter = router || null

  // 1) JS 运行时错误 + 资源加载失败(capture 阶段才能拿到资源错误)
  window.addEventListener('error', (event) => {
    try {
      if (internal) return
      // 资源加载失败(img/script/link):event 是 Event 而非 ErrorEvent,target 上是失败资源
      if (event && !event.error && event.target && event.target !== window) {
        const target = event.target
        const url = target.src || target.href || ''
        reportError({
          kind: 'RESOURCE_ERROR',
          message: '资源加载失败: ' + (url || target.tagName || 'unknown'),
          context: buildContext({ 资源标签: target.tagName, 资源地址: url })
        })
        return
      }
      const err = event?.error
      const d = describe(err || event?.message || '未知脚本错误')
      reportError({
        kind: d.kind,
        message: d.message,
        stack: d.stack,
        context: buildContext({
          文件: event?.filename,
          行列: event?.lineno != null ? `${event.lineno}:${event.colno}` : undefined
        })
      })
    } catch { /* 忽略 */ }
  }, true)

  // 2) 未捕获的 Promise 拒绝
  window.addEventListener('unhandledrejection', (event) => {
    try {
      if (internal) return
      const d = describe(event?.reason)
      reportError({
        kind: d.kind === 'Error' ? 'UNHANDLED_REJECTION' : d.kind,
        message: d.message,
        stack: d.stack,
        context: buildContext({ 来源: 'unhandledrejection' })
      })
    } catch { /* 忽略 */ }
  })

  // 3) Vue 组件错误:覆盖默认 errorHandler 后必须自己输出控制台,否则开发态看不到(走原始 console.error)
  app.config.errorHandler = (err, instance, info) => {
    try {
      const name = instance?.$options?.name || instance?.$?.type?.__name || instance?.$?.type?.name
      const d = describe(err)
      reportError({
        kind: d.kind,
        message: d.message,
        stack: d.stack,
        logger: 'vue',
        context: buildContext({ 组件: name, 钩子: info })
      })
    } catch { /* 忽略 */ }
    rawConsoleError('[dq] Vue 组件错误:', err, info)
  }

  // 4) console.error 包装:业务/三方库主动打的错误也收集(仍保留原输出行为)
  rawConsoleError = console.error.bind(console)
  console.error = (...args) => {
    try {
      if (!internal) {
        const withError = args.find((a) => a instanceof Error)
        const d = withError ? describe(withError) : { kind: 'CONSOLE_ERROR', message: args.map(readable).join(' ') }
        reportError({
          kind: d.kind === 'Error' ? 'CONSOLE_ERROR' : d.kind,
          message: d.message,
          stack: d.stack,
          logger: 'console',
          context: buildContext({ 来源: 'console.error' })
        })
      }
    } catch { /* 忽略 */ }
    rawConsoleError(...args)
  }
}

/** 把 console 参数转成可读文本(对象尽量 JSON 化,失败退回 String) */
function readable(value) {
  if (value instanceof Error) return value.stack || value.message
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

/**
 * axios 响应拦截器专用:上报接口错误。
 * 排除:上报接口自身、401(授权失效属状态,由跳激活页处理)、503(内核就绪前的启动状态)。
 * @param {any} error axios error
 * @param {string} url 请求路径
 * @param {string} method 请求方法
 * @param {string} [messageOverride] 拦截器已解析出的响应消息(blob 响应体需要单独解析)
 */
export function reportApiError(error, url, method, messageOverride) {
  try {
    if (internal) return
    const status = error?.response?.status
    if (status === 401 || status === 503) return
    if (url && url.includes('/errors/report')) return
    const message = messageOverride || error?.response?.data?.message || error?.message || '请求失败'
    const kind = status ? 'API_' + status : 'API_NETWORK'
    reportError({
      kind,
      level: status && status >= 500 ? 'ERROR' : 'WARN',
      message: `${method || 'GET'} ${url} → ${message}`,
      stack: error?.stack,
      route: currentRoute(),
      logger: 'axios',
      context: buildContext({
        接口: `${method || 'GET'} ${url}`,
        状态码: status != null ? String(status) : '无响应(网络错误/超时)',
        响应消息: message
      })
    })
  } catch { /* 忽略 */ }
}
