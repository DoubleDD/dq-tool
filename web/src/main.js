import { createApp } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 按需引入(启动优化):模板里的 el-* 组件及样式由 unplugin-vue-components 自动注入,
// 这里只补全局基底样式与函数式组件样式(message/message-box/loading 指令)
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import { ElLoading } from 'element-plus'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(router)
// v-loading 指令:按需模式下不再由全量注册提供,手动挂载(样式已在上方引入)
app.directive('loading', ElLoading.directive)

/**
 * 等待后端就绪再挂载应用:后端启动时先绑定端口提供静态页面(首页/外壳秒出,见 index.html 占位),
 * 共享内核(H2 建表/迁移 + 中断恢复)随后才完成——期间 /api/health 返回 503 + 启动阶段(stage),
 * 业务接口返回「服务启动中」。轮询到 200 才 mount,避免业务视图在就绪前挂载导致的接口报错刷屏与白屏;
 * 占位页同步点亮当前阶段(1 加载配置 / 2 启动服务 / 3 初始化数据),等待有动效反馈;
 * 超时兜底放行,由各页面自身的接口错误提示兜底(与 router 里 fetchLicenseStatus 失败放行的语义一致)。
 */
async function waitBackendReady(timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch('/api/health', { cache: 'no-store' })
      if (res.ok) return
      const stage = (await res.json().catch(() => ({}))).stage
      renderBootStage(stage)
    } catch {
      // 端口尚未监听(连接拒绝),继续等;占位保持第 1 步(加载配置)
      renderBootStage(1)
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
}

/** 按 /api/health 的阶段号点亮占位页步骤(已过=done,进行中=active) */
function renderBootStage(stage) {
  if (!Number.isInteger(stage)) return
  document.querySelectorAll('.boot-step').forEach((el) => {
    const step = Number(el.dataset.step)
    el.classList.toggle('done', step < stage)
    el.classList.toggle('active', step === stage)
  })
}

waitBackendReady().finally(() => app.mount('#app'))
