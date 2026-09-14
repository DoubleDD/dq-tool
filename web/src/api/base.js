/**
 * 后端 API 基址与访问令牌统一出口(浏览器 / jpackage 同源 与 Tauri 跨域共用)。
 *
 * 交付 jar 是纯 API 服务,不再内嵌前端:
 *  - 浏览器 / jpackage 模式(同源):apiBase = '/api';地址栏带 ?token=xxx 时捕获到 sessionStorage
 *    (服务端同时种 Cookie,双保险),后续请求带 X-Dq-Token 头,整页跳转/下载靠 Cookie。
 *  - Tauri 模式(webview 从本地 frontendDist 直载,跨域访问 http://127.0.0.1:<动态端口>):
 *    apiBase 与 token 由 Rust 侧 IPC 命令 api_base() 下发(含端口避让回填),请求带 X-Dq-Token 头。
 *
 * 注意:不要在模块顶层缓存端口/基址;apiUrl() 每次读取最新 apiBase,端口避让后自然跟随。
 */

const ACCESS_TOKEN_KEY = 'dq-access-token'
const TAURI = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__

/** 当前 API 基址:浏览器/jpackage 为 '/api';Tauri 为 'http://127.0.0.1:<动态端口>/api'(initApiBase 注入) */
export let apiBase = '/api'

/** 当前访问令牌(未配置 / 未就绪时为空串,行为与改动前零差异) */
let accessToken = ''

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 是否 Tauri 套壳环境(webview 注入 __TAURI_INTERNALS__) */
export function isTauriEnv() {
  return TAURI
}

/** 读取当前访问令牌 */
export function getAccessToken() {
  return accessToken
}

/**
 * 把接口路径解析成可 fetch / window.open 的完整 URL。
 * 兼容两种入参:以 /api 开头的完整路径(历史写法,如 downloadFile('/api/scans/1/export'))
 * 与相对 apiBase 的路径(如 apiUrl('/health'))。
 */
export function apiUrl(path) {
  const p = path.startsWith('/') ? path : '/' + path
  const suffix = p === '/api' ? '' : p.startsWith('/api/') ? p.slice(4) : p
  return apiBase + suffix
}

/** 令牌请求头:未配置 / 未就绪时返回空对象 */
export function authHeaders() {
  return accessToken ? { 'X-Dq-Token': accessToken } : {}
}

/** 同源(浏览器 / jpackage)路径:从地址栏 ?token= 捕获令牌存 sessionStorage,并用 replaceState 抹掉地址栏参数 */
export function initAccessToken() {
  try {
    const params = new URLSearchParams(window.location.search)
    const fromQuery = params.get('token')
    if (fromQuery) {
      sessionStorage.setItem(ACCESS_TOKEN_KEY, fromQuery)
      params.delete('token')
      const qs = params.toString()
      history.replaceState(null, '', window.location.pathname + (qs ? '?' + qs : '') + window.location.hash)
    }
    accessToken = sessionStorage.getItem(ACCESS_TOKEN_KEY) || ''
  } catch {
    accessToken = ''
  }
}

/**
 * 初始化 API 基址与令牌:挂载应用前调用一次。
 * Tauri:轮询 IPC api_base() 直到后端就绪(未就绪返回 null),取 { base, token };
 * 浏览器 / jpackage:基址保持相对 /api,并捕获 ?token= 存入 sessionStorage。
 */
export async function initApiBase(timeoutMs = 60000) {
  if (!TAURI) {
    initAccessToken()
    return
  }
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const info = await window.__TAURI_INTERNALS__.invoke('api_base')
      if (info && info.base) {
        apiBase = info.base
        accessToken = info.token || ''
        return
      }
    } catch {
      // 命令尚未注册 / 后端未就绪:继续轮询
    }
    await sleep(200)
  }
}
