import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 官方暗色变量包(html.dark 触发),需在自定义令牌 style.css 之前引入以保证覆盖优先级
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

/**
 * 等待后端就绪再挂载应用:后端启动时先绑定端口提供静态页面(首页/外壳秒出,见 index.html 占位),
 * 共享内核(H2 建表/迁移 + 中断恢复)随后才完成——期间 /api/health 返回 503、业务接口返回「服务启动中」。
 * 轮询到 200 才 mount,避免业务视图在就绪前挂载导致的接口报错刷屏与白屏;超时兜底放行,
 * 由各页面自身的接口错误提示兜底(与 router 里 fetchLicenseStatus 失败放行的语义一致)。
 */
async function waitBackendReady(timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch('/api/health', { cache: 'no-store' })
      if (res.ok) return
    } catch {
      /* 端口尚未监听(连接拒绝),继续等 */
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
}

waitBackendReady().finally(() => app.mount('#app'))
