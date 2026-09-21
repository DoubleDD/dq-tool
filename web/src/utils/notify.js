/**
 * 全局接口反馈通知(替代 ElMessage):基于 ElNotification,窗口右下角弹出、
 * 从右向左滑入(macOS 通知风格),标题+摘要两行(固定两行折叠,不提供原地展开),
 * 到时长自动关闭(success 2s / info 3s / warning 4s / error 5s,未显式指定时;
 * 通知只报状态,要看完整内容去通知中心)。
 *
 * 对外暴露与 ElMessage 相同的调用形态,业务代码只改 import 来源:
 *   ElMessage.success/error/warning/info(text[, options])
 *   ElMessage({ type, message })
 */
import { h, nextTick } from 'vue'
import { ElNotification } from 'element-plus'
import NotifyBody from '../components/NotifyBody.vue'
import { addNotifyRecord } from '../stores/notifications'

const TYPE_TITLES = { success: '操作成功', error: '操作失败', warning: '注意', info: '提示' }
// 未显式指定 duration 时按类型的默认时长:通知只报状态,完整内容去通知中心看
const TYPE_DURATIONS = { success: 2000, info: 3000, warning: 4000, error: 5000 }

// 通知中心留痕的窗口期去重(防双击/重试把历史刷成同一行);屏幕上的重复不忽略,
// 由 open() 把旧条关掉、新条照常顶上
const DEDUP_WINDOW_MS = 2000
const recent = new Map()

// ElNotification 同一 tick 内连续创建时读不到前一条的高度,堆叠会互相重叠(原生行为),
// 这里把弹出串行化:每条等上一条渲染完成(nextTick)后再创建,保证高度可测、正确堆叠
let openQueue = Promise.resolve()

// 屏幕同时可见通知上限:FIFO——新条从最上方进入,超出时最底下的旧条先关闭
const MAX_VISIBLE = 5
// 存活通知,按创建顺序(最旧在前,即视觉上最底下那条)
const alive = []

function open(type, text, { key, duration, onClick, actions } = {}) {
  const created = openQueue.then(async () => {
    await nextTick()
    // 同内容不叠加:屏幕上还挂着同 key 的旧条先关掉,新条照常从最上方进入
    if (key) alive.find(it => it.key === key)?.close?.()
    // 操作按钮(如导出完成「打开文件/打开文件夹」):点击先关通知再执行业务动作
    const closer = { close: null }
    const wrapped = actions?.length
      ? actions.map(a => ({ label: a.label, onClick: () => { closer.close?.(); a.onClick() } }))
      : undefined
    const item = { key }
    const handle = ElNotification({
      title: TYPE_TITLES[type] || TYPE_TITLES.info,
      type,
      position: 'bottom-right',
      duration,
      onClick,
      message: h(NotifyBody, { text, actions: wrapped }),
      onClose: () => {
        const i = alive.indexOf(item)
        if (i >= 0) alive.splice(i, 1)
      }
    })
    closer.close = handle.close
    item.close = handle.close
    alive.push(item)
    // FIFO:超出上限时关掉最旧的(堆叠最底下的),EP 会把其余通知原地下移补位
    while (alive.length > MAX_VISIBLE) alive.shift().close?.()
    return item
  })
  // 队列容错:单条创建失败(极端)不拖死后续通知;返回值带 close 句柄,
  // 供「正在导出」这类常驻提示完成后主动关闭
  openQueue = created.catch(() => {})
  return created
}

function show(type, message, options = {}) {
  const text = String(message ?? '').trim()
  if (!text) return
  const now = Date.now()
  const key = `${type}\n${text}`
  // 顺手清理过期记录,避免 Map 无限增长
  for (const [k, t] of recent) if (now - t > DEDUP_WINDOW_MS) recent.delete(k)
  const duration = options.duration ?? TYPE_DURATIONS[type] ?? 3000
  // 标题默认「操作成功/失败」等通用文案,业务可自定义(如「推导完成」);留痕到通知中心用同一标题。
  // exportPath 为导出成功通知的落盘路径(可序列化),通知中心抽屉据此重现「打开文件/打开文件夹」
  const title = options.title || TYPE_TITLES[type] || TYPE_TITLES.info
  // 留痕窗口期去重:屏幕上每次都替换旧条照常弹出,通知中心 2s 内同内容只记一条
  if (now - (recent.get(key) || 0) >= DEDUP_WINDOW_MS) {
    recent.set(key, now)
    addNotifyRecord({ type, title, text, exportPath: options.exportPath })
  }
  return open(type, text, { key, duration, onClick: options.onClick, actions: options.actions })
}

function ElMessageCompat(options) {
  if (typeof options === 'string') return show('info', options)
  return show(options?.type || 'info', options?.message, options)
}

export const ElMessage = Object.assign(ElMessageCompat, {
  success: (message, options) => show('success', message, options),
  error: (message, options) => show('error', message, options),
  warning: (message, options) => show('warning', message, options),
  info: (message, options) => show('info', message, options)
})

export default ElMessage
