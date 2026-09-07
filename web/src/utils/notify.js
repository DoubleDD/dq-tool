/**
 * 全局接口反馈通知(替代 ElMessage):基于 ElNotification,窗口右上角弹出、
 * 从右向左滑入(macOS 通知风格),标题+摘要两行,内容过长可点「展开全部/收起」
 * 原地展开查看完整内容,到时长自动关闭(超长内容给更长的展示时长)。
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
const TYPE_DURATIONS = { success: 3000, info: 4500, warning: 5000, error: 6000 }
// 内容超过该长度判定为「长内容」,自动关闭时长放宽到 10s,给展开阅读留时间
const LONG_TEXT_LEN = 50
const LONG_DURATION = 10000

// 相同内容在窗口期内重复弹出直接忽略(替代原 ElMessage 的 grouping 语义,防错误刷屏)
const DEDUP_WINDOW_MS = 2000
const recent = new Map()

// ElNotification 同一 tick 内连续创建时读不到前一条的高度,堆叠会互相重叠(原生行为),
// 这里把弹出串行化:每条等上一条渲染完成(nextTick)后再创建,保证高度可测、正确堆叠
let openQueue = Promise.resolve()

// top-right 堆叠的活跃通知(最新的在最前)。ElNotification 只在创建时算一次 top
// (且新通知固定排最下),展开/收起改变高度后下方通知也不会跟随,这里统一接管定位。
// 注意必须像 EP 自己的 close() 一样改组件的 offset prop——直接改 el.style.top 会在组件
// 再渲染时被它自己的 style 绑定覆盖,导致新通知「从底部出现再斜着上移」。
// 而根元素的 __vueParentComponent 是 BaseTransition(改它的 props 不触发正确重渲染),
// 要沿 parent 链找到真正的 ElNotification 实例(特征:exposed.visible)
const stack = []
let notifySeq = 0

function findNotifyInstance(el) {
  let inst = el?.__vueParentComponent
  while (inst && !inst.exposed?.visible) inst = inst.parent
  return inst || null
}

function setOffset(item, top) {
  if (item.inst) item.inst.props.offset = top
  else if (item.el) item.el.style.top = `${top}px`
}

function reflow() {
  let top = 16
  for (const item of stack) {
    const el = item.el
    if (!el || !el.isConnected) continue
    setOffset(item, top)
    top += el.offsetHeight + 16
  }
}

function open(type, text, { duration } = {}) {
  openQueue = openQueue.then(async () => {
    await nextTick()
    const item = { id: ++notifySeq }
    const handle = ElNotification({
      title: TYPE_TITLES[type] || TYPE_TITLES.info,
      type,
      position: 'top-right',
      duration,
      customClass: `dq-notify-${item.id} dq-notify-${type}`,
      message: h(NotifyBody, {
        text,
        // 原地展开/收起,高度变化后重算堆叠,下方通知跟随上移/下移(top 有过渡动画)
        onExpand: () => nextTick(reflow),
        onCollapse: () => nextTick(reflow)
      }),
      onClose: () => {
        const i = stack.indexOf(item)
        if (i >= 0) stack.splice(i, 1)
        // EP 的 close() 在 userOnClose 之后还会按「创建顺序堆叠」自行上移剩余通知
        // (与我们的置顶布局冲突),推迟到其调整完、渲染前再重排,后写的正确值生效
        nextTick(reflow)
      }
    })
    item.close = handle.close
    item.el = document.querySelector(`.dq-notify-${item.id}`)
    item.inst = findNotifyInstance(item.el)
    // 新通知置顶:创建后立刻把它的 offset 钉在顶部(赶在入场过渡首帧前,动画即顶部纯水平滑入);
    // 此时新通知高度尚不可测(v-show 隐藏态),等入场渲染完成后再重排,其余通知按实测高度下移腾位
    setOffset(item, 16)
    stack.unshift(item)
    await nextTick()
    reflow()
  })
}

function show(type, message, options = {}) {
  const text = String(message ?? '').trim()
  if (!text) return
  const now = Date.now()
  const key = `${type}\n${text}`
  if (now - (recent.get(key) || 0) < DEDUP_WINDOW_MS) return
  recent.set(key, now)
  // 顺手清理过期记录,避免 Map 无限增长
  for (const [k, t] of recent) if (now - t > DEDUP_WINDOW_MS) recent.delete(k)
  let duration = options.duration ?? TYPE_DURATIONS[type] ?? 4500
  if (text.length > LONG_TEXT_LEN) duration = Math.max(duration, LONG_DURATION)
  // 留痕到通知中心(头部铃铛抽屉),被去重跳过的重复通知不重复记录
  addNotifyRecord({ type, title: TYPE_TITLES[type] || TYPE_TITLES.info, text })
  open(type, text, { duration })
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
