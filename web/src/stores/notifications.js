/**
 * 通知历史:只存 localStorage(dq-notify-history),不落服务端/H2,刷新与重启后保留。
 * 全局通知弹出的同时在此留痕,头部铃铛抽屉(components/NotificationBell.vue)读取展示,
 * 支持一键清空。list 最新的在最前,上限 100 条。
 */
import { reactive } from 'vue'

const STORAGE_KEY = 'dq-notify-history'
const MAX_HISTORY = 100

function load() {
  try {
    const arr = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
    return Array.isArray(arr) ? arr.slice(0, MAX_HISTORY) : []
  } catch {
    return []
  }
}

export const notifyHistory = reactive({ list: load() })

function save() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(notifyHistory.list))
  } catch { /* 存储满等异常静默忽略,不影响通知弹出 */ }
}

export function addNotifyRecord({ type, title, text }) {
  notifyHistory.list.unshift({
    id: `${Date.now()}-${Math.random()}`,
    type,
    title,
    text,
    time: new Date().toISOString()
  })
  if (notifyHistory.list.length > MAX_HISTORY) notifyHistory.list.length = MAX_HISTORY
  save()
}

export function clearNotifyHistory() {
  notifyHistory.list = []
  save()
}
