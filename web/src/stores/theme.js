import { reactive } from 'vue'

/**
 * 明暗主题状态(轻量实现,与 stores/tabs.js 同风格)
 * 通过给 <html> 加/删 dark 类触发 Element Plus 官方暗色变量与 style.css 的深色令牌;
 * 三档模式:auto(默认,跟随系统 prefers-color-scheme 并监听变化)/ light / dark,
 * 选择持久化在 localStorage('dq-theme')。
 */
const media = window.matchMedia('(prefers-color-scheme: dark)')

export const themeState = reactive({
  mode: 'auto',
  dark: false
})

function applyEffective() {
  themeState.dark = themeState.mode === 'dark' || (themeState.mode === 'auto' && media.matches)
  document.documentElement.classList.toggle('dark', themeState.dark)
}

export function setThemeMode(mode) {
  themeState.mode = mode
  localStorage.setItem('dq-theme', mode)
  applyEffective()
}

/** 三档循环:自动 → 浅色 → 深色 */
export function cycleTheme() {
  const order = ['auto', 'light', 'dark']
  setThemeMode(order[(order.indexOf(themeState.mode) + 1) % order.length])
}

/** 应用启动时恢复上次主题,需在挂载早期调用避免闪烁;旧版存的 light/dark 值直接兼容 */
export function initTheme() {
  const saved = localStorage.getItem('dq-theme')
  themeState.mode = ['auto', 'light', 'dark'].includes(saved) ? saved : 'auto'
  applyEffective()
  media.addEventListener('change', applyEffective)
}
