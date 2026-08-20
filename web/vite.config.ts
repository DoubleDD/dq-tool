import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // 按需引入(启动优化):模板里的 el-* 组件及其样式自动注入,
    // 主包不再全量引入 Element Plus,首屏解析/执行更快(函数式组件 ElMessage/ElLoading 的手动用法见 main.js)
    Components({ resolvers: [ElementPlusResolver()], dts: false })
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:10000',
        changeOrigin: true
      }
    }
  }
})
