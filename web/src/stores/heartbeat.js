import { reactive } from 'vue'
import request from '../api'

/**
 * 页面心跳间隔(秒):App.vue 按此间隔上报 /api/heartbeat,桌面安装版看门狗按 3 个间隔判窗口已关闭。
 * 间隔在「系统设置 → 页面心跳」可视化维护(单位秒/分/时),保存后写回本状态,
 * App.vue watch 到变化即重建定时器,无需刷新页面;读取失败沿用默认 5 秒。
 */
export const heartbeatState = reactive({
  intervalSeconds: 5
})

/** 从后端拉取心跳间隔(应用挂载后调用一次;内核未就绪/接口失败时静默沿用当前值) */
export async function loadHeartbeatInterval() {
  try {
    const v = await request.get('/system-settings/heartbeat')
    if (v.intervalSeconds > 0) heartbeatState.intervalSeconds = v.intervalSeconds
  } catch { /* 静默失败:默认 5 秒兜底,不影响心跳上报 */ }
}
