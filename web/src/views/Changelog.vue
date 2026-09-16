<template>
  <!-- 更新日志:单组件双模式(latest=本次更新 / all=更新记录),由路由 props 传入。
       后端把 CHANGELOG.md 切成版本段落并原样返回段内 Markdown,渲染交给 marked(见下方 renderMarkdown) -->
  <div v-loading="loading" class="page-card changelog-page">
    <template v-if="view">
      <div v-for="e in shownEntries" :key="e.version" class="cl-entry">
        <div class="cl-heading">
          <span class="cl-version">v{{ e.version }}</span>
          <span v-if="e.date" class="cl-date">{{ e.date }}</span>
        </div>
        <!-- 内容来自构建期打包进 jar 的 CHANGELOG.md,属可信内容(渲染口径见 renderMarkdown 注释) -->
        <div class="cl-md" v-html="e.html"></div>
      </div>
      <el-empty
        v-if="!shownEntries.length"
        :description="mode === 'latest' ? '本次版本暂无更新说明' : '暂无更新记录'"
        :image-size="80"
      />
      <div v-if="mode === 'latest'" class="history-link">
        <el-button link type="primary" @click="goHistory">查看历史版本更新记录</el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import request from '../api'

const props = defineProps({
  /** latest:只显示当前版本条目(「本次更新」页签);all:显示全部条目(「更新记录」页签) */
  mode: { type: String, default: 'latest' }
})

const router = useRouter()
const loading = ref(false)
const view = ref(null)

/** latest 模式下当前版本对应条目(查不到时页面显示「本次版本暂无更新说明」) */
const currentEntry = computed(() => {
  if (!view.value) return null
  return view.value.entries.find((e) => e.version === view.value.currentVersion) || null
})

/**
 * 待渲染条目(latest 只留当前版本,all 为全部历史,文件顺序即最新在前);
 * Markdown 在这里一次性解析成 html 缓存进条目,避免每次重渲染都重复解析全部历史。
 */
const shownEntries = computed(() => {
  if (!view.value) return []
  const list = props.mode !== 'latest' ? view.value.entries : currentEntry.value ? [currentEntry.value] : []
  return list.map((e) => ({ ...e, html: renderMarkdown(e.markdown) }))
})

onMounted(load)

/**
 * 段落 Markdown → HTML。
 * 内容来自构建期随 CHANGELOG.md 打包进 jar 的静态资源(人维护,非用户输入、非上传文件、运行期不可改),
 * 属可信内容,故直接 v-html 输出,不再引入 DOMPurify 之类的清洗依赖。
 * `async: false` 让 parse 同步返回字符串(模板取用方便);GFM 默认开启(表格/删除线/任务列表)。
 */
function renderMarkdown(md) {
  if (!md) return ''
  return marked.parse(md, { async: false })
}

async function load() {
  loading.value = true
  try {
    view.value = await request.get('/changelog')
  } finally {
    loading.value = false
  }
}

function goHistory() {
  router.push('/changelog')
}
</script>

<!-- 样式不带 scoped:Markdown 正文由 v-html 注入(拿不到 SFC 的 scope id,[data-v-x] 选择器会全部失配),
     故正文样式必须全局写;cl-* 类名仅本页使用,无全局冲突 -->
<style>
.cl-entry {
  margin-bottom: 24px;
}
.cl-heading {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-bottom: 6px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.cl-version {
  font-size: 16px;
  font-weight: 600;
}
.cl-date {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* Markdown 正文:行内代码里常有 `_copy`/`_bak`/`_backup` 这类超长不可断串,
   统一允许断词,避免把页面撑出横向滚动条 */
.cl-md {
  line-height: 1.8;
  overflow-wrap: break-word;
}
.cl-md > :first-child {
  margin-top: 0;
}
.cl-md > :last-child {
  margin-bottom: 0;
}
.cl-md h1,
.cl-md h2,
.cl-md h3,
.cl-md h4,
.cl-md h5,
.cl-md h6 {
  font-size: 14px;
  font-weight: 600;
  margin: 12px 0 4px;
}
.cl-md p {
  margin: 4px 0;
}
.cl-md ul,
.cl-md ol {
  margin: 4px 0;
  padding-left: 20px;
}
.cl-md li {
  margin: 2px 0;
}
.cl-md li > ul,
.cl-md li > ol {
  margin: 2px 0;
}
.cl-md code {
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  font-family: 'SF Mono', Menlo, Consolas, 'Courier New', monospace;
  font-size: 12px;
}
.cl-md pre {
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  overflow-x: auto;
}
.cl-md pre code {
  padding: 0;
  background: none;
}
.cl-md a {
  color: var(--el-color-primary);
  text-decoration: none;
}
.cl-md a:hover {
  text-decoration: underline;
}
.cl-md blockquote {
  margin: 8px 0;
  padding: 2px 12px;
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}
.cl-md hr {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--el-border-color-lighter);
}
.cl-md img {
  max-width: 100%;
}
/* 表格可能比容器宽:自身横向滚动,不撑破页面 */
.cl-md table {
  display: block;
  max-width: 100%;
  margin: 8px 0;
  border-collapse: collapse;
  overflow-x: auto;
}
.cl-md th,
.cl-md td {
  padding: 4px 8px;
  border: 1px solid var(--el-border-color-lighter);
  text-align: left;
}

.history-link {
  text-align: center;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
