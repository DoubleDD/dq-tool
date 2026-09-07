import { ElMessage } from './notify'

/** tauri 套壳环境(webview 注入 __TAURI_INTERNALS__) */
export const isTauri = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__

/**
 * 通用下载:桌面端(Tauri)弹原生保存对话框让用户自选保存位置(Rust 侧 save_download_as
 * 命令 GET 本地后端流式接口并写盘,文件名以后端 Content-Disposition 为准);
 * 浏览器环境没有通用的选目录 Web API,保持默认下载行为(window.open 绕开 axios JSON 拦截器)。
 * @param {string} apiPath 完整接口路径(含 query),如 `/api/scans/1/export?cols=`
 */
export async function downloadFile(apiPath) {
  if (!isTauri) {
    window.open(apiPath, '_blank')
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
