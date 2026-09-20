<script setup>
/**
 * 多表选择器:左侧级联挑表(复用 TableCascadePicker 的 toggleable 模式,表行 +/− 逐张加入/移出),
 * 右侧「已选清单」面板逐条可删。比对任务「选择比对系统」与元数据同步表级/库级选择共用。
 *
 * - v-model:已选清单,`level='table'`(默认)为 `[{ datasourceId, db, schema, table }]`;
 *   `level='schema'` 为 `[{ datasourceId, db, schema }]`,均可跨数据源/库/schema 累积;
 * - 终态栏点 + 时先过 `validateAdd`(返回字符串即当警告文案拦截,如比对任务拦「基准表本身」),放行即入列;
 * - 移出(栏内 − 或清单「删除」)组件内直接完成,父级经 v-model 感知;
 * - `lane-change` 回抛级联当前所在 数据源/库/schema,父级据此算 `disabledTables` 等联动数据。
 */
import { computed, reactive, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { EditPen } from '@element-plus/icons-vue'
import { ElMessage } from '../utils/notify'
import TableCascadePicker from './TableCascadePicker.vue'

const props = defineProps({
  // 已选清单:table 粒度为 [{ datasourceId, db, schema, table }];schema 粒度为 [{ datasourceId, db, schema }]
  modelValue: { type: Array, default: () => [] },
  // 可选数据源清单(由父级拉取,口径与页面工具栏一致)
  datasources: { type: Array, default: () => [] },
  // 当前级联所在栏位下禁止选择的表名/schema 名(比对任务:基准表本身),命中项在级联里置灰
  disabledTables: { type: Array, default: () => [] },
  // 加入前的校验钩子:返回 true/undefined 放行;返回字符串则当作警告文案弹出并拦截
  validateAdd: { type: Function, default: null },
  // 选择粒度:table=选到表(默认);schema=选到库/schema(级联终态栏为库/schema 栏)
  level: { type: String, default: 'table' },
  // 终态栏 +/− 悬浮提示里的对象名(「加入比对系统」/「移出比对系统」)
  label: { type: String, default: '表' },
  // 右侧清单面板标题与空态文案
  panelTitle: { type: String, default: '已选表' },
  emptyText: { type: String, default: '还没有已选表,请在左侧选好库/schema 后,点表名右侧的 + 加入' },
  // 条目第一行名称可编辑(比对任务:自定义显示名,写到条目 name 字段;清空 = 恢复默认名)
  nameEditable: { type: Boolean, default: false },
  // 库描述(schema_doc)缓存:键 `${数据源id}|${多库方言?db:''}|${schema||db}` → 描述(由父级按需加载);
  // 默认显示名回落链:条目 name(自定义)> 库描述 > 数据源名
  schemaDescs: { type: Object, default: () => ({}) },
  // 右侧面板宽度;清单默认撑满与左侧级联同高,panelMaxHeight 传值时作为上限(数字按 px 处理,字符串原样使用)
  panelWidth: { type: [Number, String], default: 420 },
  panelMaxHeight: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue', 'lane-change'])

// 级联面板当前所在的 数据源/库/schema(schema 粒度只用前两级;具体对象在终态栏用 +/− 逐条切换)
const pick = reactive({ datasourceId: '', db: '', schema: '' })
watch(pick, (v) => emit('lane-change', { ...v }))

const schemaLevel = computed(() => props.level === 'schema')

/** 与当前级联所在栏位同栏:schema 粒度按 数据源+库;table 粒度按 数据源+库+schema */
function sameLane(t) {
  if (String(t.datasourceId) !== String(pick.datasourceId)) return false
  if ((t.db || '') !== (pick.db || '')) return false
  return schemaLevel.value ? true : t.schema === pick.schema
}

// 当前栏位下已入列的名字(schema 粒度是 schema 名,table 粒度是表名):交给级联组件渲染成绿色「−」态
const addedTables = computed(() => props.modelValue.filter(sameLane)
  .map((t) => (schemaLevel.value ? t.schema : t.table)))

function dsOf(t) {
  return props.datasources.find((d) => String(d.id) === String(t.datasourceId))
}

/** 定位串:库/schema(.表)。清单里与数据源名分两行展示,避免单行截断 */
function locOf(t) {
  const schemaPart = t.db ? `${t.db}.${t.schema}` : t.schema
  return schemaLevel.value ? schemaPart : `${schemaPart ? schemaPart + '.' : ''}${t.table}`
}

/** 终态栏「+/−」:已加过就移出;没加过先过 validateAdd,放行即入列 */
function onToggle(t) {
  const idx = props.modelValue.findIndex((x) =>
    sameLane(x) && (schemaLevel.value ? x.schema === t.name : x.table === t.name))
  if (idx >= 0) {
    const next = [...props.modelValue]
    next.splice(idx, 1)
    emit('update:modelValue', next)
    return
  }
  const target = schemaLevel.value
    ? { datasourceId: pick.datasourceId, db: pick.db, schema: t.name }
    : { datasourceId: pick.datasourceId, db: pick.db, schema: pick.schema, table: t.name }
  const verdict = props.validateAdd?.(target)
  if (typeof verdict === 'string' && verdict) return ElMessage.warning(verdict)
  emit('update:modelValue', [...props.modelValue, target])
}

function removeAt(i) {
  const next = [...props.modelValue]
  next.splice(i, 1)
  emit('update:modelValue', next)
}

/** 库描述缓存键(与父级加载方同一口径):多库方言(SQL Server/Kingbase)带 db,单库方言 db 归空、schema 空时以 db(库名)兜底 */
function descKeyOf(t) {
  const multi = ['SQLSERVER', 'KINGBASE'].includes(dsOf(t)?.dbType)
  return `${t.datasourceId}|${multi ? (t.db || '') : ''}|${t.schema || t.db || ''}`
}

/** 条目默认显示名:库描述 > 数据源名(自定义名 t.name 由调用处另判) */
function defaultNameOf(t) {
  return props.schemaDescs[descKeyOf(t)] || dsOf(t)?.name || ''
}

/** 编辑条目自定义显示名(仅 nameEditable):清空 = 清除自定义名,第一行恢复默认名(库描述/数据源名) */
async function editName(t, i) {
  const defName = defaultNameOf(t)
  const source = props.schemaDescs[descKeyOf(t)] ? '库描述' : '数据源'
  const { value } = await ElMessageBox.prompt('自定义名称', `当前名称来源:${source}(${defName || '未知'})`, {
    confirmButtonText: '保存',
    cancelButtonText: '取消',
    inputValue: t.name || defName,
    inputPlaceholder: '留空则恢复默认名称',
    // 允许清空(清空 = 存 null,展示回落默认名);其余一律放行
    inputValidator: () => true
  }).catch(() => ({ value: null }))
  if (value === null || value === undefined) return
  const name = (value || '').trim()
  if (name === (t.name || defName)) return
  const next = [...props.modelValue]
  // 清空时删除 name 字段(提交载荷按 falsy 归 null),避免残留空串
  next[i] = { ...t, ...(name ? { name } : {}) }
  if (!name) delete next[i].name
  emit('update:modelValue', next)
}

const px = (v) => (typeof v === 'number' ? `${v}px` : v)
const sideStyle = computed(() => ({ width: px(props.panelWidth) }))
// panelMaxHeight 为空时清单撑满面板(与左侧级联同高);传值时作为上限、内容贴合
const listStyle = computed(() => (props.panelMaxHeight ? { maxHeight: px(props.panelMaxHeight) } : {}))
</script>

<template>
  <div class="multi-picker">
    <!-- 左侧:级联挑表(toggleable,表行 +/− 逐张加入/移出) -->
    <div class="multi-picker-main">
      <TableCascadePicker
        v-model:datasource-id="pick.datasourceId"
        v-model:db="pick.db"
        v-model:schema="pick.schema"
        :datasources="datasources"
        :disabled-tables="disabledTables"
        :added-tables="addedTables"
        :label="label"
        :level="level"
        :show-selected="false"
        toggleable
        @toggle="onToggle"
      />
    </div>
    <!-- 右侧:已选清单 -->
    <div class="multi-picker-side" :style="sideStyle">
      <div class="picked-panel">
        <div class="picked-panel-head">
          <span>{{ panelTitle }}</span>
          <span class="picked-panel-count">{{ modelValue.length }}</span>
        </div>
        <div class="picked-list" :style="listStyle">
          <div v-for="(t, i) in modelValue" :key="i" class="picked-item">
            <span class="picked-index">{{ i + 1 }}</span>
            <span class="picked-label" :title="`${t.name || defaultNameOf(t)} · ${locOf(t)}`">
              <span class="picked-ds">{{ t.name || defaultNameOf(t) }}</span>
              <span class="picked-loc">{{ locOf(t) }}</span>
            </span>
            <el-button v-if="nameEditable" link type="primary" title="自定义名称" @click="editName(t, i)">
              <el-icon><EditPen /></el-icon>
            </el-button>
            <el-button link type="danger" @click="removeAt(i)">删除</el-button>
          </div>
          <div v-if="!modelValue.length" class="picked-empty">{{ emptyText }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 左级联 + 右清单:横向撑满父级(高度由父级 flex 布局给定) */
.multi-picker {
  display: flex;
  gap: 24px;
  align-items: stretch;
  flex: 1 1 auto;
  min-height: 0;
}
.multi-picker-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.multi-picker-side {
  flex: none;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
/* 已选清单卡:默认撑满侧栏高度(与左侧级联同高),清单内部滚动;panelMaxHeight 传值时退化为内容贴合 */
.picked-panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: hidden;
  background: var(--el-fill-color-blank);
}
.picked-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.picked-panel-count {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.picked-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 4px;
}
.picked-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 13px;
}
.picked-item:hover {
  background: var(--el-fill-color-light);
}
.picked-item .picked-label {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
/* 两行各自单行省略(整卡 title 有完整串),第二行库.表弱化显示 */
.picked-item .picked-label > span {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.picked-item .picked-loc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.picked-empty {
  padding: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.picked-index {
  flex: none;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
  font-size: 12px;
}
</style>
