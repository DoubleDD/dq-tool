import { ElMessage } from './notify'
import request from '../api'
import { isTauriEnv } from '../api/base'

/** tauri 套壳环境(webview 注入 __TAURI_INTERNALS__);统一由 api/base 判定,此处转出一个布尔值保持既有引用可用 */
export const isTauri = isTauriEnv()

/**
 * 通用下载:桌面端(Tauri)弹原生保存对话框让用户自选保存位置(Rust 侧 save_download_as
 * 命令 GET 本地后端流式接口并写盘,文件名以后端 Content-Disposition 为准);
 * 浏览器 / jpackage --app 形态在本窗口内 fetch 成 Blob 再走 a[download](存浏览器默认下载目录)。
 * @param {string} apiPath 完整接口路径(含 query),如 `/api/scans/1/export?cols=`
 */
export async function downloadFile(apiPath) {
  if (!isTauri) {
    // 不再 window.open 新开窗口:--app 窗口会把 _blank 新窗口甩给系统默认浏览器(另一个浏览器配置),
    // 现场出现过新窗口空白、文件导不出来(2026-09 jpackage 免安装版);留在本窗口下载还顺带让
    // 404(导出 token 过期)/403/500 等错误响应经 axios 拦截器解析出 message 弹提示,而非空白页。
    // timeout: 0 取消 30s 默认超时——大文件慢速内网下载不受限;错误提示由拦截器统一弹出,这里静默
    try {
      const resp = await request.get(apiPath.startsWith('/api/') ? apiPath.slice(4) : apiPath, {
        responseType: 'blob',
        timeout: 0,
        _raw: true
      })
      saveBlob(filenameFromDisposition(resp.headers['content-disposition']), resp.data)
    } catch { /* 拦截器已弹错误提示并上报错误中心 */ }
    return
  }
  try {
    // Rust 命令返回保存的绝对路径;用户取消返回 null(静默)
    const saved = await window.__TAURI_INTERNALS__.invoke('save_download_as', { path: apiPath })
    if (saved) ElMessage.success(`已保存到 ${saved}`)
  } catch (e) {
    ElMessage.error(String(e))
  }
}

/** Blob 落盘:a[download] 触发浏览器默认下载行为(与 downloadText 同一模式,--app 窗口与 Tauri webview 均可用) */
function saveBlob(filename, blob) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 从 Content-Disposition 取下载文件名:优先 filename*=UTF-8''(各导出接口统一口径),退回 filename="..." */
function filenameFromDisposition(header) {
  if (header) {
    const star = /filename\*=UTF-8''([^;]+)/i.exec(header)
    if (star) {
      try {
        return decodeURIComponent(star[1].trim())
      } catch { /* 百分号编码非法时退回 quoted 解析 */ }
    }
    const quoted = /filename="([^"]+)"/i.exec(header)
    if (quoted) return quoted[1]
  }
  return 'download'
}

/**
 * 文本下载(诊断报告等前端组装的内容):Blob + a[download],浏览器与 Tauri webview 均可用。
 * @param {string} filename 下载文件名
 * @param {string} text 文本内容
 * @param {string} [mime] MIME 类型,默认 text/markdown
 */
export function downloadText(filename, text, mime = 'text/markdown') {
  const blob = new Blob([text], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * DataURL 下载(前端画布生成的图片等):a[download] 直载,浏览器与 Tauri webview 均可用。
 * @param {string} filename 下载文件名
 * @param {string} dataUrl canvas.toDataURL 等产出的 data:... URL
 */
export function downloadDataUrl(filename, dataUrl) {
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename
  a.click()
}
