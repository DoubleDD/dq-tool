import { defineConfig } from 'vite'
import { readdirSync } from 'node:fs'
import { join } from 'node:path'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Element Plus 各组件 style/css 子模块清单(dev 预打包用)。
 * 按需引入(ElementPlusResolver)会在每个 .vue 模板编译时按组件生成
 * `element-plus/es/components/<组件>/style/css` 的样式导入——这些导入对 vite 的
 * 启动依赖扫描不可见,首次进入用到新组件的页面时才会被发现,触发 vite 重新预打包并
 * 整页刷新(页面会闪 index.html 启动占位)。这里在启动时把全部组件样式子模块纳入
 * optimizeDeps.include 预打包,从源头杜绝运行期新依赖发现与整页刷新。
 * 枚举失败(如 element-plus 未安装)时静默降级为空清单,不影响开发服务器启动。
 */
function elementPlusComponentStyleCss() {
  try {
    const componentsDir = join(process.cwd(), 'node_modules/element-plus/es/components')
    return readdirSync(componentsDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => `element-plus/es/components/${entry.name}/style/css`)
  } catch {
    return []
  }
}

export default defineConfig({
  plugins: [
    vue(),
    // 按需引入(启动优化):模板里的 el-* 组件及其样式自动注入,
    // 主包不再全量引入 Element Plus,首屏解析/执行更快(函数式组件 ElMessage/ElLoading 的手动用法见 main.js)
    Components({ resolvers: [ElementPlusResolver()], dts: false })
  ],
  optimizeDeps: {
    // 启动时全量预打包应用依赖,避免 dev 运行期新依赖发现导致的整页刷新(详见上方注释)
    include: [
      'vue',
      'vue-router',
      'axios',
      'element-plus',
      '@element-plus/icons-vue',
      'codemirror',
      '@codemirror/autocomplete',
      '@codemirror/lang-sql',
      '@codemirror/language',
      '@codemirror/state',
      '@codemirror/view',
      '@lezer/highlight',
      ...elementPlusComponentStyleCss()
    ]
  },
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
