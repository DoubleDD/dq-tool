/** 格式化字节数 */
export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return '-'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / Math.pow(1024, i)
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${units[i]}`
}

/** 数字千分位 */
export function formatNumber(n) {
  if (n === null || n === undefined) return '-'
  return Number(n).toLocaleString('zh-CN')
}

/** 状态 → tag 类型 */
export const STATUS_TAG_TYPE = {
  DONE: 'success',
  RUNNING: 'primary',
  FAILED: 'danger',
  CANCELED: 'info',
  INTERRUPTED: 'warning',
  PENDING: 'info'
}

/** 状态 → 中文文案 */
export const STATUS_TEXT = {
  DONE: '完成',
  RUNNING: '运行中',
  FAILED: '失败',
  CANCELED: '已取消',
  INTERRUPTED: '已中断',
  PENDING: '等待中'
}

export function statusTagType(status) {
  return STATUS_TAG_TYPE[status] || 'info'
}

export function statusText(status) {
  return STATUS_TEXT[status] || status || '-'
}

/** 日期时间格式化 */
export function formatDateTime(v) {
  if (!v) return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const pad = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 耗时(毫秒 → 可读文案) */
export function formatDuration(startedAt, finishedAt) {
  if (!startedAt) return '-'
  const start = new Date(startedAt).getTime()
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) return '-'
  let ms = Math.max(0, end - start)
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}秒`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}分${s % 60}秒`
  const h = Math.floor(m / 60)
  return `${h}时${m % 60}分`
}
