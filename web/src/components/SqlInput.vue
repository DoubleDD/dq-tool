<template>
  <div class="sql-input-wrap">
    <span v-if="label" class="sql-input-label">{{ label }}</span>
    <div ref="host" class="sql-input-editor" />
  </div>
</template>

<script setup>
/**
 * SQL 单行输入(CodeMirror 6 精简版):语法高亮 + 字段/关键字智能提示 + 回车提交。
 * 用于数据预览的 WHERE / ORDER BY 过滤栏;颜色全部走 --el-* 变量,明暗主题自动适配。
 */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorView, keymap, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState, Prec } from '@codemirror/state'
import { sql } from '@codemirror/lang-sql'
import { autocompletion, acceptCompletion } from '@codemirror/autocomplete'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: '' },        // 前缀标签(WHERE / ORDER BY)
  placeholder: { type: String, default: '' },
  columns: { type: Array, default: () => [] }, // 补全字段清单 [{name, type}]
})
const emit = defineEmits(['update:modelValue', 'enter'])

const host = ref(null)
let view = null

// 常用过滤/排序关键字,混入字段名一起提示
const KEYWORDS = [
  'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN', 'IS NULL', 'IS NOT NULL', 'ASC', 'DESC',
]

// 补全源:取光标前的标识符 token,前缀匹配字段名(带类型 detail)与关键字;读取最新 props
function completionSource(context) {
  const word = context.matchBefore(/[\w一-龥]*$/)
  if (!word || (word.from === word.to && !context.explicit)) return null
  const options = [
    ...props.columns.map((c) => ({ label: c.name, type: 'property', detail: c.type })),
    ...KEYWORDS.map((k) => ({ label: k, type: 'keyword' })),
  ]
  return { from: word.from, options, validFor: /^[\w一-龥]*$/ }
}

// 高亮配色:全部引用 CSS 变量,跟随明暗主题(标识符用独立的青色系变量,定义在组件样式里)
// 注意 lang-sql 的 tag 口径:普通标识符=tags.name,引号标识符=special(string),内置函数=standard(name)
const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--el-color-primary)', fontWeight: '600' },
  { tag: tags.string, color: 'var(--el-color-success)' },
  { tag: tags.special(tags.string), color: 'var(--dq-sql-ident)' },
  { tag: tags.number, color: 'var(--el-color-warning)' },
  { tag: tags.bool, color: 'var(--el-color-warning)' },
  { tag: tags.null, color: 'var(--el-color-warning)', fontWeight: '600' },
  { tag: tags.typeName, color: 'var(--el-color-danger)' },
  { tag: [tags.operator, tags.punctuation], color: 'var(--el-text-color-regular)' },
  { tag: tags.name, color: 'var(--dq-sql-ident)' },
  { tag: [tags.lineComment, tags.blockComment], color: 'var(--el-text-color-placeholder)', fontStyle: 'italic' },
])

const theme = EditorView.theme({
  '&': { backgroundColor: 'transparent', fontSize: '12px' },
  '.cm-content': {
    padding: '4px 8px 4px 4px',
    fontFamily: "'SF Mono', Menlo, Consolas, 'Courier New', monospace",
    caretColor: 'var(--el-text-color-primary)',
  },
  '.cm-line': { padding: '0' },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': { overflowX: 'auto', lineHeight: '1.6' },
  '.cm-placeholder': { color: 'var(--el-text-color-placeholder)' },
  // 补全下拉:跟随 Element 弹层配色
  '.cm-tooltip': {
    backgroundColor: 'var(--el-bg-color-overlay)',
    border: '1px solid var(--el-border-color-light)',
    borderRadius: '4px',
    color: 'var(--el-text-color-primary)',
  },
  '.cm-tooltip.cm-tooltip-autocomplete > ul > li[aria-selected]': {
    backgroundColor: 'var(--el-color-primary)',
    color: 'var(--el-color-white)',
  },
  '.cm-completionDetail': { color: 'var(--el-text-color-placeholder)', fontStyle: 'normal' },
})

onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        // 单行输入按键:补全菜单打开时 Enter/Tab 先接受候选(acceptCompletion 返回 true 即消费),
        // 菜单未打开时 Enter 提交(回车应用)、Tab 不消费(焦点正常转移到下一个输入框)
        Prec.highest(keymap.of([
          { key: 'Enter', run: (v) => acceptCompletion(v) || (emit('enter'), true) },
          { key: 'Tab', run: (v) => acceptCompletion(v) },
        ])),
        sql(),
        syntaxHighlighting(highlight),
        autocompletion({ override: [completionSource], activateOnTyping: true }),
        cmPlaceholder(props.placeholder),
        theme,
        EditorView.updateListener.of((v) => {
          if (v.docChanged) emit('update:modelValue', v.state.doc.toString())
        }),
      ],
    }),
  })
})

// 外部清空/重置(如点清除按钮)时同步回编辑器
watch(() => props.modelValue, (val) => {
  if (view && val !== view.state.doc.toString()) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: val } })
  }
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})
</script>

<style scoped>
/* 外观对齐 el-input small:边框/圆角/聚焦色,标签在左、编辑器在右 */
.sql-input-wrap {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-blank);
  transition: border-color 0.2s;
  /* 标识符(字段名)高亮色:浅色主题用深青,深色主题用亮青 */
  --dq-sql-ident: #0e7490;
}
:global(html.dark) .sql-input-wrap {
  --dq-sql-ident: #67e8f9;
}
.sql-input-wrap:hover {
  border-color: var(--el-border-color-hover);
}
.sql-input-wrap:focus-within {
  border-color: var(--el-color-primary);
}
.sql-input-label {
  padding-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  user-select: none;
}
.sql-input-editor {
  flex: 1;
  min-width: 0;
}
.sql-input-editor :deep(.cm-editor) {
  background: transparent;
}
</style>
