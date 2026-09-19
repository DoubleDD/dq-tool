import { ElMessage } from './notify'
import request, { exportLanded, exportFailed } from '../api'
import { isTauriEnv } from '../api/base'

/** tauri 套壳环境(webview 注入 __TAURI_INTERNALS__);统一由 api/base 判定,此处转出一个布尔值保持既有引用可用 */
export const isTauri = isTauriEnv()

/**
 * 通用下载:两种形态同一手感——「直存 <数据目录>/exports/ + 完成后通知(手动打开文件/文件夹)」。
 * 桌面端(Tauri)由 Rust `save_download` 命令 GET 本地后端流式接口写盘;浏览器 / jpackage --app
 * 由后端 `POST /api/system/save-download` 自调目标导出接口写盘(与 Tauri 里 Rust 同一角色,
 * 全部流式导出端点零改动)。文件名统一以后端 Content-Disposition 为准;后端生成整份导出才写出
 * 首字节,等待期间弹常驻「正在导出」。
 * @param {string} apiPath 完整接口路径(含 query),如 `/api/scans/1/export?cols=`
 */
export async function downloadFile(apiPath) {
  let loading = null
  try {
    loading = await ElMessage.info('正在导出,请稍候…', { duration: 0 })
    const saved = isTauri
      ? await window.__TAURI_INTERNALS__.invoke('save_download', { path: apiPath })
      : (await request.post('/system/save-download', { path: apiPath })).path
    notifyExportSaved(saved)
    // 导出中心(push 模型):统一直存成功后回填登记记录的 rel_path + 实测大小(按文件名关联最新登记)
    exportLanded(saved.split(/[\\/]/).pop()).catch(() => { })
  } catch (e) {
    // 导出中心:点击时已登记「生成中」,失败按路径翻 FAILED(错误提示仍由拦截器/Tauri 分支负责)
    exportFailed(apiPath, String(e?.message ?? e).slice(0, 200)).catch(() => { })
    // 浏览器形态 HTTP 错误已由 axios 拦截器统一弹提示;Tauri 的 invoke 错误在这里兜底
    if (isTauri) ElMessage.error(String(e))
  } finally {
    loading?.close?.()
  }
}

/**
 * 导出完成统一反馈:成功通知,留「打开文件 / 打开文件夹」手动入口(与用户确认:不自动弹文件管理器)。
 * 供不走 downloadFile 的服务端直存导出(如比对报告 exportToFile)在完成后的回调复用;
 * 通知中心抽屉的历史导出通知经 exportPath 复用 openSavedFile/showSavedFolder。
 * @param {string} saved 导出件绝对路径
 */
export function notifyExportSaved(saved) {
  const name = saved.split(/[\\/]/).pop()
  ElMessage.success(`已导出:${name}`, {
    // 10s 自动消失(用户确认):足够点「打开文件/打开文件夹」,不常驻堆叠
    duration: 10000,
    title: '导出完成',
    exportPath: saved,
    actions: [
      { label: '打开文件', onClick: () => openSavedFile(saved) },
      { label: '打开文件夹', onClick: () => showSavedFolder(saved) }
    ]
  })
}

/** 调系统默认关联程序打开产物文件(通知中心抽屉的历史导出通知也复用) */
export function openSavedFile(path) {
  request.post('/system/open', { path }).catch(() => { /* 拦截器已弹错误提示 */ })
}

/** 打开产物文件所在目录并选中(macOS Finder / Windows 资源管理器;同上复用) */
export function showSavedFolder(path) {
  request.post('/system/reveal', { path }).catch(() => { /* 拦截器已弹错误提示 */ })
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
