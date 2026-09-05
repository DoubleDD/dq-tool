<template>
  <!-- 更新日志:单组件双模式(latest=本次更新 / all=更新记录),由路由 props 传入;
       后端把 CHANGELOG.md 解析为结构化条目,此处做轻渲染(不引入 Markdown 依赖):
       `- `/`* ` 开头→列表项,`### `→小标题,其余→段落(空行已由后端剔除) -->
  <div v-loading="loading" class="page-card changelog-page">
    <template v-if="view">
      <template v-if="mode === 'latest'">
        <changelog-entry-card v-if="currentEntry" :entry="currentEntry" />
        <el-empty v-else description="本次版本暂无更新说明" :image-size="80" />
        <div class="history-link">
          <el-button link type="primary" @click="goHistory">查看历史版本更新记录</el-button>
        </div>
      </template>
      <template v-else>
        <template v-if="view.entries.length">
          <changelog-entry-card v-for="e in view.entries" :key="e.version" :entry="e" />
        </template>
        <el-empty v-else description="暂无更新记录" :image-size="80" />
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api'

defineProps({
  /** latest:只显示当前版本条目(「本次更新」页签);all:显示全部条目(「更新记录」页签) */
  mode: { type: String, default: 'latest' }
})

/** 单个版本条目卡片:版本号+日期标题 + 行列表轻渲染(局部函数组件,避免拆文件) */
const ChangelogEntryCard = defineComponent({
  name: 'ChangelogEntryCard',
  props: { entry: { type: Object, required: true } },
  setup(props) {
    return () => {
      const children = []
      let listItems = []
      const flushList = () => {
        if (listItems.length) {
          children.push(h('ul', { class: 'cl-list' }, listItems))
          listItems = []
        }
      }
      props.entry.lines.forEach((line, i) => {
        if (line.startsWith('- ') || line.startsWith('* ')) {
          listItems.push(h('li', { key: i }, line.slice(2)))
        } else if (line.startsWith('### ')) {
          flushList()
          children.push(h('div', { key: i, class: 'cl-subtitle' }, line.slice(4)))
        } else {
          flushList()
          children.push(h('p', { key: i, class: 'cl-para' }, line))
        }
      })
      flushList()
      return h('div', { class: 'cl-entry' }, [
        h('div', { class: 'cl-heading' }, [
          h('span', { class: 'cl-version' }, `v${props.entry.version}`),
          props.entry.date ? h('span', { class: 'cl-date' }, props.entry.date) : null
        ]),
        ...children
      ])
    }
  }
})

const router = useRouter()
const loading = ref(false)
const view = ref(null)

/** latest 模式下当前版本对应条目(查不到时页面显示「本次版本暂无更新说明」) */
const currentEntry = computed(() => {
  if (!view.value) return null
  return view.value.entries.find((e) => e.version === view.value.currentVersion) || null
})

onMounted(load)

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

<style scoped>
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
.cl-subtitle {
  font-size: 14px;
  font-weight: 600;
  margin: 10px 0 4px;
}
.cl-list {
  margin: 4px 0;
  padding-left: 20px;
  line-height: 1.8;
}
.cl-para {
  margin: 4px 0;
  line-height: 1.8;
}
.history-link {
  text-align: center;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
