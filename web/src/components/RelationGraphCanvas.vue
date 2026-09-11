<template>
  <!-- G6 ER 图画布:通用画布底座 BaseGraphCanvas 承载交互(滚轮平移/Ctrl+滚轮与触摸板捏合缩放/Alt+滚轮水平平移/
       html 节点滚轮转发与双击补发)、顶部工具栏(默认五工具:重绘/1:1/适应画布/线形/档位 + toolbar 插槽业务工具,
       右侧缩放控制条)、右下角鸟瞰图、左下角图例折叠面板、视口定位与生命周期;本组件只负责
       ER 专属部分:html 表节点渲染、field-cubic 字段对齐边、er-dagre-grid 布局、表名单击/双击口径、
       节点选中/套索多选/批量否决、边样式图例(legend 插槽)。容器需显式高度(由父级布局保证) -->
  <div class="rg-wrap">
    <BaseGraphCanvas
      ref="baseRef"
      :data="graphData"
      :options="graphOptions"
      :fit="defaultZoom > 0 ? 'center' : 'view'"
      :default-zoom="defaultZoom > 0 ? defaultZoom : 1"
      :options-key="edgeType"
      :minimap="minimapOptions"
      :fullscreen="false"
      :tools="['refresh', 'zoom100', 'fit', 'edge-type', 'export-drawio', 'level']"
      :level="level"
      :edge-type="edgeType"
      @update:level="emit('update:level', $event)"
      @update:edge-type="emit('update:edgeType', $event)"
      @export-drawio="emit('export-drawio')"
      @node-click="onNodeClick"
      @edge-click="onEdgeClick"
      @canvas-click="onCanvasClick"
    >
      <!-- 业务工具透传:字段数/导出等由调用方按需给 -->
      <template #toolbar><slot name="toolbar" /></template>
      <template #legend>
        <div class="rg-legend">
          <span class="rg-legend-item"><i class="rg-line rg-line-confirmed" />确认</span>
          <span class="rg-legend-item"><i class="rg-line rg-line-candidate" />候选</span>
          <span class="rg-legend-item"><i class="rg-line rg-line-suspect" />疑似多对多</span>
        </div>
      </template>
    </BaseGraphCanvas>
    <!-- 选中操作条(底部居中悬浮):点节点/Shift 点选/Shift 拖动套索后出现;
         「删除」= 否决选中表的所有关系(与「候选管理-批量否决」同口径:候选/确认均转否决),成功后 emit changed 由父级刷新图 -->
    <div v-if="selectedTables.size" class="rg-selbar">
      <span class="rg-selbar-text">已选 {{ selectedTables.size }} 张表</span>
      <el-tooltip content="否决选中表的所有关系(候选与已确认均转为否决),与「候选管理-批量否决」同口径" placement="top">
        <el-button size="small" type="warning" :disabled="!affectedEdges.length" :loading="rejecting" @click="rejectSelected">
          删除{{ affectedEdges.length ? `(${affectedEdges.length} 条关系)` : '' }}
        </el-button>
      </el-tooltip>
      <el-button size="small" @click="clearSelection">取消</el-button>
    </div>
    <!-- 画布操作提示:常驻弱提示(浅色、不抢视觉),悬浮在鸟瞰图左侧;不参与鼠标事件 -->
    <div class="rg-hint">Shift+拖动框选 · Shift+点击多选</div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { BaseLayout, CubicHorizontal, ExtensionCategory, Polyline, register } from '@antv/g6'
import dagre from 'dagre'
import BaseGraphCanvas from './canvas/BaseGraphCanvas.vue'
import { createHtmlMinimapShape } from './canvas/htmlMinimapShape'
import { themeState } from '../stores/theme'
import { batchRejectRelations } from '../api'
import { ElMessage } from '../utils/notify'

// ER 图画布(G6 v5 配置式 API,通用能力见底座 BaseGraphCanvas):
//  节点 = 表(HTML 矩形节点,三档显示:仅表名 / 表名+关联字段 / 表名+全部字段,锚点表高亮;
//    全部字段档字段行 = 字段名+注释(注释更淡、超长随行截断「…」,行 title 带完整注释 hover 可看全文)+类型);
//  边 = 关系(候选虚线灰色 / 确认实线主题色 / 疑似多对多红色),自定义 field-cubic 曲线边:
//    端点精确对齐到两端字段行(按节点几何常量计算行高坐标,按对端节点方位决定从左侧还是右侧出边),
//    字段行未展示时(仅表名档位/字段被折叠进「+N 个字段」,该行可点击就地展开/收起)退化为节点边框交点;
//  边标签只标基数(1:1 / 1:N / M:N),不堆字段文字(字段名看连线两端的行高亮即可);
//  乌鸦脚语义用自定义边端标记近似:「一」侧画竖杠(1:1 双竖杠),「多」侧画三叉鸦脚;
//  交互:点节点选中(深描边+主题色光环,锚点表恒为主题色底纹)、Shift+点击增减选中、Shift+拖动套索多选、
//  点画布空白清空;底部操作条「删除」= 批量否决选中表的所有关系(与「候选管理-批量否决」同口径)
const props = defineProps({
  // 图节点:[{ name, comment }]
  nodes: { type: Array, default: () => [] },
  // 图边:TableRelation 列表(id/oneTable/oneColumn/manyTable/manyColumn/cardinality/status/...)
  edges: { type: Array, default: () => [] },
  // 锚点表名(星型图):节点高亮;空串=全库总图
  anchorTable: { type: String, default: '' },
  // 显示档位:name 仅表名 / related 表名+关联字段 / all 表名+全部字段
  level: { type: String, default: 'name' },
  // level=all 时的各表字段清单:{ 表名: [{ name, type, comment }] }
  columnsMap: { type: Object, default: () => ({}) },
  // 单表最多展示的字段行数(超出折叠为「+N 个字段」,可点击就地展开):工具栏可设置,默认 10
  maxFieldRows: { type: Number, default: 10 },
  // 初始/重建后的默认比例(1=100%):>0 时内容置中并锁定该比例(表详情 ER 页签传 1);
  // 0=fitView 自适应视口(独立 ER 图页,大图先看全貌)
  defaultZoom: { type: Number, default: 0 },
  // 连线线型:curve 曲线(字段对齐贝塞尔)/ orth 直角折线 / orth-round 直角折线(带倒角);
  // 线型是建图期配置(边类型不同),切换经 optionsKey 销毁重建
  edgeType: { type: String, default: 'curve' },
  // 名字口径三档(表名与字段同规则,默认 chinese):
  //   chinese=仅中文注释(表节点标题中文名为主、英文表名降为副标题;字段行只显示中文注释,无注释回退英文名);
  //   english=仅英文名(标题英文表名为主、注释降为副标题;字段行只显示英文名,完整注释仍在行 tooltip);
  //   both=中英文同时显示(中文在前:标题同 chinese 档主/副标题双行,字段行中文注释在前、英文名淡色尾随)
  fieldNameMode: { type: String, default: 'chinese' }
})

// edge-click:点边(传 TableRelation);node-click:点节点(传表名);node-open:双击节点表名(传表名,跳字段明细);
// update:level / update:edgeType:工具栏档位/线形切换(配 v-model 用);
// export-drawio:工具栏「导出 drawio」(第 5 个图标位;调用方经 exportData() 取数据并下载);
// changed:批量否决成功 { action: 'reject', ids },父级据此剔除这些边并刷新图(与关系抽屉单条操作同口径)
const emit = defineEmits(['edge-click', 'node-click', 'node-open', 'update:level', 'update:edgeType', 'export-drawio', 'changed'])

const baseRef = ref(null)
// 边 id -> TableRelation(点边时回查原始数据)
let relById = new Map()
// 单击表名与点节点都会触发 node-click:单击表名只打时间戳不开面板(跳转改双击),node-click 据此忽略
let lastTitleClick = 0
// 已展开全部字段的表(点「+N 个字段」展开、点「收起字段」还原;不持久化,画布重建后保留)
const expandedTables = new Set()
// 指针按下坐标:拖拽节点结束时也会冒出一个 click,用它过滤「拖拽误触」的展开/收起
let downPos = null

// ---------- 节点选中(点选/套索多选) ----------
// 选中表集合:普通点击单选、Shift+点击增减、Shift+拖动套索整体替换(框空=清空);
// 纯渲染口径(描边/底纹),变化经 repaint 就地刷新,不动布局与视口
const selectedTables = ref(new Set())
// 套索框选刚结束的时间戳:框选松手后浏览器补发的 canvas click 据此忽略,否则刚框中的选中会被「点空白清空」立刻清掉
let lassoJustFinished = 0
// 批量否决请求进行中(防重复提交)
const rejecting = ref(false)
// 选中表涉及的关系(底部操作条「删除」= 否决这些关系,与「候选管理-批量否决」同口径)
const affectedEdges = computed(() => {
  const sel = selectedTables.value
  if (!sel.size) return []
  return props.edges.filter((e) => sel.has(e.oneTable) || sel.has(e.manyTable))
})

/** 选中一个表:additive(Shift)= 在现有集合上增减,否则单选替换 */
function selectTable(table, additive = false) {
  if (!table) return
  const next = additive ? new Set(selectedTables.value) : new Set()
  if (additive && next.has(table)) next.delete(table)
  else next.add(table)
  selectedTables.value = next
}

function clearSelection() {
  if (selectedTables.value.size) selectedTables.value = new Set()
}

// 选中集合变化 → 就地重绘节点 HTML 刷选中描边/底纹(不跑布局,拖动后的节点位置不丢)
watch(selectedTables, () => baseRef.value?.repaint(buildData()))
// 图数据重建(刷新/批量否决后):选中集合裁掉已不在图里的表,操作条计数随之为准
watch(
  () => props.nodes,
  (nodes) => {
    const names = new Set((nodes || []).map((n) => n.name))
    const next = new Set([...selectedTables.value].filter((t) => names.has(t)))
    if (next.size !== selectedTables.value.size) selectedTables.value = next
  }
)

/** 主题色:跟随 Element Plus CSS 变量(亮/暗主题自适应),取不到用兜底值 */
function themeColors() {
  const cs = getComputedStyle(document.documentElement)
  const get = (k, fb) => cs.getPropertyValue(k).trim() || fb
  return {
    primary: get('--el-color-primary', '#409eff'),
    danger: get('--el-color-danger', '#f56c6c'),
    border: get('--el-border-color', '#dcdfe6'),
    borderDarker: get('--el-border-color-darker', '#cdd0d6'),
    bg: get('--el-bg-color', '#ffffff'),
    text: get('--el-text-color-primary', '#303133'),
    textSecondary: get('--el-text-color-secondary', '#909399'),
    fill: get('--el-fill-color-light', '#f5f7fa')
  }
}

/** HTML 转义(表名/注释可能含 <>& 等字符) */
function esc(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// 节点宽度;字段行最多展示条数由 props.maxFieldRows 决定(超出折叠为「+N 个字段」)
const NODE_W = 240
// 孤儿表网格排布常量(er-dagre-grid 布局一次性排好:孤儿表放到主体下方并分成多排)
const ORPHAN_GAP_X = 24 // 同排节点水平间距(与 dagre nodesep 一致)
const ORPHAN_GAP_Y = 52 // 排与排之间的垂直间距
const ORPHAN_MARGIN_Y = 40 // 无主体时孤儿网格距画布顶部留白
// 节点几何常量(与 renderNodeHtml 的 HTML 严格一致,边端点对齐字段行依赖它):
// 标题区=主标题行 34 + 上下 padding 6+6(有中文名时内含 14px 英文表名小字行);字段容器上 padding 4、行高 18、折叠行 16,底部留白 4
const TITLE_H_COMMENT = 46
const TITLE_H_PLAIN = 32
const FIELD_PAD_TOP = 4
const FIELD_PAD_BOTTOM = 4
const ROW_H = 18
const MORE_ROW_H = 16

/** 表的关联字段(去重,保持出现顺序):该表作为 one 侧的 one_column + 作为 many 侧的 many_column */
function relatedColumns(table) {
  const set = []
  for (const e of props.edges) {
    if (e.oneTable === table && !set.includes(e.oneColumn)) set.push(e.oneColumn)
    if (e.manyTable === table && !set.includes(e.manyColumn)) set.push(e.manyColumn)
  }
  return set
}

/** 节点按档位应展示的字段行:[{ name, type, comment, related }];comment 仅全部字段档有(来自 columnsMap) */
function nodeFields(table) {
  if (props.level === 'name') return []
  const related = relatedColumns(table)
  if (props.level === 'related') return related.map((name) => ({ name, type: '', comment: '', related: true }))
  const relatedSet = new Set(related)
  return (props.columnsMap[table] || []).map((c) => ({ name: c.name, type: c.type || '', comment: c.comment || '', related: relatedSet.has(c.name) }))
}

/** 节点当前可见字段行:已展开的表全量返回,否则截断到 maxFieldRows(超出部分折叠为「+N 个字段」行) */
function visibleFields(table) {
  const fields = nodeFields(table)
  return expandedTables.has(table) ? fields : fields.slice(0, props.maxFieldRows)
}

/** 节点尺寸:标题区(表名+注释) + 可见字段行 + 折叠/收起行;尺寸与 renderNodeHtml 实际渲染严格一致(边端点按此几何对齐字段行) */
function nodeSize(table, comment) {
  const titleH = comment ? TITLE_H_COMMENT : TITLE_H_PLAIN
  const fields = nodeFields(table)
  if (!fields.length) return [NODE_W, titleH]
  // 超 maxFieldRows 时恒有一行操作行(折叠态=「+N 个字段」,展开态=「收起字段」)
  const moreH = fields.length > props.maxFieldRows ? MORE_ROW_H : 0
  return [NODE_W, titleH + FIELD_PAD_TOP + visibleFields(table).length * ROW_H + moreH + FIELD_PAD_BOTTOM]
}

/** 节点 HTML:标题(有中文名时中文名为主、英文表名小字在下;标题可点跳字段明细) + 字段行(关联字段高亮);
 *  超 maxFieldRows 折叠为「+N 个字段」操作行(点击就地展开全量,展开后该行变「收起字段」);
 *  选中态 = 正文色深描边 + 主题色光环;锚点表(本表/星型中心)恒为主题色底纹,任何状态下都一眼可辨;
 *  根 div 带 data-rg-node 标记,容器层点击委托按它判定「点节点选中」 */
function renderNodeHtml(d) {
  const c = themeColors()
  const { table, comment } = d.data
  const isAnchor = table === props.anchorTable
  const isSelected = selectedTables.value.has(table)
  const fields = nodeFields(table)
  const expanded = expandedTables.has(table)
  const rows = visibleFields(table)
  const fieldHtml = rows.map((f) => {
    const style = f.related ? `color:${c.primary};font-weight:600` : `color:${c.textSecondary}`
    // 字段名口径三档:chinese=仅中文注释(无注释回退英文名);english=仅英文名;both=中文在前、英文名更淡尾随;
    // 超长随行截断「…」;整行 title 恒带 英文名+完整注释,hover 即可查看全文
    const main = props.fieldNameMode === 'english' ? f.name : (f.comment || f.name)
    const tailHtml = props.fieldNameMode === 'both' && f.comment
      ? `<span style="margin-left:4px;opacity:.7;font-weight:400">${esc(f.name)}</span>` : ''
    const type = f.type ? `<span style="flex:none;padding-left:8px;opacity:.75;font-weight:400">${esc(f.type)}</span>` : ''
    const tipText = [f.name, f.comment].filter(Boolean).join(' ')
    const tip = tipText ? ` title="${esc(tipText)}"` : ''
    return `<div${tip} style="display:flex;align-items:center;height:18px;line-height:18px;padding:0 8px;font-size:11px;overflow:hidden;${style}"><span style="flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(main.replace(/\s+/g, ' '))}${tailHtml}</span>${type}</div>`
  }).join('')
  const more = fields.length > props.maxFieldRows
    ? `<div class="rg-more-row" data-rg-table="${esc(table)}" title="${expanded ? '收起字段' : '点击展开全部字段'}" style="height:16px;line-height:16px;padding:0 8px;font-size:11px;color:${c.primary};cursor:pointer;white-space:nowrap">${expanded ? '收起字段' : `+${fields.length - props.maxFieldRows} 个字段`}</div>` : ''
  // 名字口径与字段一致:中文档(含 both)中文注释为主标题、英文表名小字副标题;
  // 仅英文名档则反过来(同对象管理图;副标题行有无只看 comment,节点几何不变)
  const englishOnly = props.fieldNameMode === 'english'
  const titleText = englishOnly ? table : (comment || table)
  const subText = comment ? (englishOnly ? comment : table) : ''
  const subHtml = subText
    ? `<div style="height:14px;line-height:14px;padding:0 8px;font-size:11px;color:${c.textSecondary};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(subText)}</div>` : ''
  const border = isSelected ? `2px solid ${c.text}` : isAnchor ? `2px solid ${c.primary}` : `1px solid ${c.borderDarker}`
  // 光环:选中与锚点同语言(主题色 20% 淡环);锚点另有主题色底纹 + 主题色描边/标题,始终一眼可辨
  const ring = isSelected || isAnchor ? `box-shadow:0 0 0 3px ${c.primary}33;` : ''
  // 锚点底纹:根底 = 主题色 8% 淡 tint;有字段行时标题行叠 15% 更深一档(无字段行时标题透明,直接透出根底)
  const titleBg = fields.length ? `background:${isAnchor ? `${c.primary}26` : c.fill};` : ''
  return `<div data-rg-node="${esc(table)}" style="width:${NODE_W}px;height:100%;box-sizing:border-box;background:${isAnchor ? `${c.primary}14` : c.bg};border:${border};border-radius:6px;overflow:hidden;${ring}">
  <div class="rg-node-title" data-rg-table="${esc(table)}" title="双击打开字段明细" style="height:${comment ? 34 : 32}px;line-height:${comment ? 20 : 32}px;padding:${comment ? '6px' : '0'} 8px;font-size:13px;font-weight:600;color:${isAnchor ? c.primary : c.text};white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer;${fields.length ? `border-bottom:1px solid ${c.border};${titleBg}` : ''}">${esc(titleText)}${subHtml}</div>
  <div style="padding-top:4px">${fieldHtml}${more}</div>
</div>`
}

// ---------- 乌鸦脚近似 ----------
// G6 v5 箭头 type 支持自定义函数 (width, height) => PathArray;
// 坐标系约定:箭头以端点为中心,局部 +x 沿边方向指向节点(与内置 triangle/vee 一致)

/** 「一」侧端标记:竖杠;isOneToOne 时双竖杠(crow's foot 的 one and only one) */
const oneTick = (isOneToOne) => (w, h) => isOneToOne
  ? [['M', -w / 4, -h / 2], ['L', -w / 4, h / 2], ['M', w / 4, -h / 2], ['L', w / 4, h / 2]]
  : [['M', 0, -h / 2], ['L', 0, h / 2]]

/** 「多」侧端标记:三叉鸦脚(从边侧一点向节点侧扇出三叉) */
const crowFoot = (w, h) => [
  ['M', -w / 2, 0], ['L', w / 2, -h / 2],
  ['M', -w / 2, 0], ['L', w / 2, 0],
  ['M', -w / 2, 0], ['L', w / 2, h / 2]
]

/** 基数中文标签(边标签只标基数,不堆字段文字——字段名看连线两端对齐的行高亮) */
const CARD_TEXT = { ONE_TO_ONE: '1:1', ONE_TO_MANY: '1:N', SUSPECT_MANY_TO_MANY: 'M:N(疑似)' }

// ---------- 字段对齐曲线边 ----------
// 自定义边 field-cubic:继承水平三次贝塞尔,重写 getEndpoints 把端点精确对齐到两端字段行:
// 行中心 y 由节点几何常量推出,出边侧(左/右)按对端节点方位决定;字段行未展示时退化为节点边框交点。
// 控制点重写成「先水平引出一小段再拐弯」(stub):对端正落在节点正上/下方时,
// 原生 cubic-horizontal 的控制点会缩进节点体内,曲线穿框而过,stub 保证曲线始终从出边侧绕出去。
// 每次重绘都会重算(拖动节点/切档位后端点跟随),左右侧切换无需监听事件

/** 节点边框与「中心 → 对端中心」射线的交点(字段行不可见时的兜底端点) */
function borderPoint(center, w, h, opposite) {
  const dx = opposite[0] - center[0]
  const dy = opposite[1] - center[1]
  if (!dx && !dy) return [center[0] + w / 2, center[1], 0]
  const t = Math.min(dx ? (w / 2) / Math.abs(dx) : Infinity, dy ? (h / 2) / Math.abs(dy) : Infinity)
  return [center[0] + dx * t, center[1] + dy * t, 0]
}

/** 端口行内 y 偏移(相对节点中心):字段行可见时对齐行中心,否则为 0(与 fieldEndpoint 同口径,布局交叉削减用) */
function portOffsetY(table, comment, column, h) {
  if (column == null) return 0
  const idx = visibleFields(table).findIndex((f) => f.name === column)
  if (idx < 0) return 0
  return -h / 2 + (comment ? TITLE_H_COMMENT : TITLE_H_PLAIN) + FIELD_PAD_TOP + idx * ROW_H + ROW_H / 2
}

/** 字段行中心点(节点左/右缘)+ 出边侧(+1 右 / -1 左);行不可见时退化为边框交点 */
function fieldEndpoint(self, nodeEl, column, oppositeEl) {
  const center = nodeEl.getCenter()
  const opposite = oppositeEl.getCenter()
  const table = nodeEl.id
  const comment = self.context.graph.getNodeData(table)?.data?.comment || ''
  const [w, h] = nodeSize(table, comment)
  const idx = column == null
    ? -1
    : visibleFields(table).findIndex((f) => f.name === column)
  if (idx < 0) {
    return { point: borderPoint(center, w, h, opposite), side: opposite[0] >= center[0] ? 1 : -1 }
  }
  const side = opposite[0] >= center[0] ? 1 : -1
  const x = center[0] + side * (w / 2)
  const y = center[1] - h / 2 + (comment ? TITLE_H_COMMENT : TITLE_H_PLAIN) + FIELD_PAD_TOP + idx * ROW_H + ROW_H / 2
  return { point: [x, y, 0], side }
}

/** 两端字段行对齐端点 + 出边侧(field-cubic / field-polyline 两种线型共用) */
function erEdgeEndpoints(self) {
  const rel = self.context.graph.getEdgeData(self.id)?.data || {}
  const source = fieldEndpoint(self, self.sourceNode, rel.oneColumn, self.targetNode)
  const target = fieldEndpoint(self, self.targetNode, rel.manyColumn, self.sourceNode)
  self.sides = [source.side, target.side]
  return { source, target }
}

/** 两端水平引出 stub 长(两端切线恒水平,乌鸦脚标记方向随之保持正确) */
function edgeStub(sourcePoint, targetPoint) {
  return Math.max(40, Math.min(120, Math.abs(targetPoint[0] - sourcePoint[0]) / 2))
}

class FieldCubicEdge extends CubicHorizontal {
  getEndpoints() {
    const { source, target } = erEdgeEndpoints(this)
    return [source.point, target.point]
  }

  /** 控制点:端点沿各自出边侧水平引出 stub 长(两端切线恒水平,乌鸦脚标记方向随之保持正确) */
  getControlPoints(sourcePoint, targetPoint) {
    const [srcSide, tgtSide] = this.sides || [1, -1]
    const stub = edgeStub(sourcePoint, targetPoint)
    return [
      [sourcePoint[0] + srcSide * stub, sourcePoint[1]],
      [targetPoint[0] + tgtSide * stub, targetPoint[1]]
    ]
  }
}
register(ExtensionCategory.EDGE, 'field-cubic', FieldCubicEdge)

// 自定义边 field-polyline:与 field-cubic 相同的字段行对齐端点,路径走正交折线——
// 两端沿出边侧水平引出 stub,中段经竖直段衔接(H-V-H-V-H);弯角由边样式 radius 倒圆
// (radius=0 为纯直角线,>0 为带倒角;见 edgeStyle 按 edgeType 注入)
class FieldPolylineEdge extends Polyline {
  getEndpoints() {
    const { source, target } = erEdgeEndpoints(this)
    return [source.point, target.point]
  }

  getControlPoints() {
    const { source, target } = erEdgeEndpoints(this)
    const s = source.point
    const t = target.point
    const stub = edgeStub(s, t)
    const a = [s[0] + source.side * stub, s[1]] // 源侧 stub 末端
    const b = [t[0] + target.side * stub, t[1]] // 目标侧 stub 末端
    const pts = [a]
    // 两端不同行也不同列时,经 midX 竖直段衔接(H-V-H);同一竖直通道(a.x≈b.x)直接竖连
    if (Math.abs(a[1] - b[1]) > 1e-6 && Math.abs(a[0] - b[0]) > 1e-6) {
      const midX = (a[0] + b[0]) / 2
      pts.push([midX, a[1]], [midX, b[1]])
    }
    pts.push(b)
    // 去掉相邻重复点:零长段会让倒角的共线判定失效(倒角只圆真正的拐角)
    return pts.filter((p, i) => i === 0 || Math.abs(p[0] - pts[i - 1][0]) > 1e-6 || Math.abs(p[1] - pts[i - 1][1]) > 1e-6)
  }
}
register(ExtensionCategory.EDGE, 'field-polyline', FieldPolylineEdge)

// ---------- 组合布局 er-dagre-grid(一次性算好最终坐标,避免「先竖排再重排」的中间态) ----------
// 有关系的表 = 出现在任意边两端的表,用经典 dagre(LR 固定层次)排在上方;
// 孤儿表 = 完全没有边的表,按画布宽度 + 节点数预先分列,排成底部多行网格并水平居中于主体包络之下。
// 一次布局返回全部节点最终坐标,G6 只绘制一遍;尺寸来自 G6 注入的 nodeSize(即节点实测 size),
// 与 nodeSize()/renderNodeHtml 的几何口径一致(dagre 需要真实宽高来算间距,防节点重叠)。
class ErDagreGridLayout extends BaseLayout {
  id = 'er-dagre-grid'
  async execute(model) {
    const { nodes = [], edges = [] } = model
    const o = this.options || {}
    const sizeOf = (n) => {
      const raw = o.nodeSize ? o.nodeSize(n) : n.style?.size
      const num = (v, fb) => { const x = Number(v); return Number.isFinite(x) && x > 0 ? x : fb }
      if (Array.isArray(raw)) return [num(raw[0], NODE_W), num(raw[1], 40)]
      return [num(raw, NODE_W), 40]
    }
    // 端点表集合:在任一边出现即「有关系」;孤儿 = 完全无可见边的表(含候选边也算有连线)
    const linked = new Set()
    for (const e of edges) {
      if (e.source == null || e.target == null) continue
      linked.add(String(e.source))
      linked.add(String(e.target))
    }
    const linkedNodes = nodes.filter((n) => linked.has(String(n.id)))
    const orphanNodes = nodes.filter((n) => !linked.has(String(n.id)))
    const result = []
    let box = null // 主体包络 {minX,maxX,maxY},孤儿网格放在其下
    if (linkedNodes.length) {
      const g = new dagre.graphlib.Graph()
      g.setGraph({
        rankdir: o.rankdir || 'LR',
        nodesep: o.nodesep ?? 24,
        ranksep: o.ranksep ?? 120,
        marginx: 0,
        marginy: 0
      })
      g.setDefaultEdgeLabel(() => ({}))
      linkedNodes.forEach((n) => {
        const [w, h] = sizeOf(n)
        g.setNode(String(n.id), { width: w, height: h })
      })
      const linkedIds = new Set(linkedNodes.map((n) => String(n.id)))
      edges.forEach((e) => {
        const s = String(e.source)
        const t = String(e.target)
        if (s === t) return // 自环不进 dagre(布局不考虑),连线仍按原数据渲染
        if (linkedIds.has(s) && linkedIds.has(t)) g.setEdge(s, t)
      })
      dagre.layout(g)
      // 端口感知的交叉削减:dagre 的 order 优化只到节点粒度,而边钉在字段行上,
      // 同列节点顺序与对端字段行序不一致时仍交叉;按列(rank)分组做重心扫描——
      // 节点重心 = 邻接边对端「节点中心 y + 端口行内偏移」均值,同列按重心重排纵向顺序
      {
        const pos = new Map() // id -> { x, y, h, comment }
        for (const n of linkedNodes) {
          const id = String(n.id)
          const p = g.node(id)
          if (p) pos.set(id, { x: p.x, y: p.y, h: sizeOf(n)[1], comment: n.data?.comment || '' })
        }
        const adj = new Map() // id -> [{ other, off }] off=对端端口行内偏移
        const pushAdj = (id, other, off) => {
          if (!adj.has(id)) adj.set(id, [])
          adj.get(id).push({ other, off })
        }
        for (const e of edges) {
          const s = String(e.source)
          const t = String(e.target)
          if (s === t || !pos.has(s) || !pos.has(t)) continue
          const sp = pos.get(s)
          const tp = pos.get(t)
          pushAdj(s, t, portOffsetY(t, tp.comment, e.data?.manyColumn, tp.h))
          pushAdj(t, s, portOffsetY(s, sp.comment, e.data?.oneColumn, sp.h))
        }
        // 按列分组(dagre LR 同 rank 节点的 x 相同)
        const ranks = new Map()
        for (const [id, p] of pos) {
          if (!ranks.has(p.x)) ranks.set(p.x, [])
          ranks.get(p.x).push(id)
        }
        const gap = o.nodesep ?? 24
        const reorder = (ids) => {
          if (ids.length < 2) return
          const bc = new Map(ids.map((id) => {
            const list = adj.get(id) || []
            if (!list.length) return [id, pos.get(id).y] // 无边节点保持原位
            return [id, list.reduce((acc, { other, off }) => acc + pos.get(other).y + off, 0) / list.length]
          }))
          const sorted = [...ids].sort((a, b) => bc.get(a) - bc.get(b) || pos.get(a).y - pos.get(b).y)
          // 纵向重排:保持列纵向中心与间距,只换顺序
          const totalH = sorted.reduce((acc, id) => acc + pos.get(id).h, 0) + gap * (ids.length - 1)
          const top = Math.min(...ids.map((id) => pos.get(id).y - pos.get(id).h / 2))
          const bottom = Math.max(...ids.map((id) => pos.get(id).y + pos.get(id).h / 2))
          let y = (top + bottom) / 2 - totalH / 2
          for (const id of sorted) {
            const p = pos.get(id)
            p.y = y + p.h / 2
            y += p.h + gap
          }
        }
        // 前向/后向各扫两轮(双向扫描收敛单向的局部最优)
        const rankList = [...ranks.values()]
        for (let r = 0; r < 2; r++) {
          rankList.forEach(reorder)
          ;[...rankList].reverse().forEach(reorder)
        }
        for (const [id, p] of pos) {
          const gn = g.node(id)
          if (gn) gn.y = p.y // 写回 dagre 结果,下游 result/包络统一从 g.node 取
        }
      }
      let minX = Infinity, maxX = -Infinity, maxY = -Infinity
      for (const n of linkedNodes) {
        const id = String(n.id)
        const p = g.node(id)
        if (!p) continue
        result.push({ id, style: { x: p.x, y: p.y } })
        const [w, h] = sizeOf(n)
        if (p.x - w / 2 < minX) minX = p.x - w / 2
        if (p.x + w / 2 > maxX) maxX = p.x + w / 2
        if (p.y + h / 2 > maxY) maxY = p.y + h / 2
      }
      box = { minX, maxX, maxY }
    }
    if (orphanNodes.length) {
      const sizes = orphanNodes.map(sizeOf)
      const maxW = Math.max(...sizes.map((s) => s[0]))
      const canvasW = o.width || 1200
      const cellW = maxW + ORPHAN_GAP_X
      // 列数:按画布宽度铺开(至少 1),画布越宽排数越少;保证多排而非单列
      const cols = Math.max(1, Math.min(orphanNodes.length, Math.floor(canvasW / cellW)))
      const centerX = box ? (box.minX + box.maxX) / 2 : canvasW / 2
      const gridLeft = centerX - (Math.min(cols, orphanNodes.length) * cellW) / 2
      let rowStartY = box ? box.maxY + ORPHAN_GAP_Y : ORPHAN_MARGIN_Y
      let col = 0
      let rowMaxH = 0
      for (let i = 0; i < orphanNodes.length; i++) {
        const [w, h] = sizes[i]
        result.push({
          id: String(orphanNodes[i].id),
          style: { x: gridLeft + col * cellW + w / 2, y: rowStartY + h / 2 }
        })
        rowMaxH = Math.max(rowMaxH, h)
        col++
        if (col >= cols) {
          rowStartY += rowMaxH + ORPHAN_GAP_Y
          rowMaxH = 0
          col = 0
        }
      }
    }
    return { nodes: result, edges: [], combos: [] }
  }
}
register(ExtensionCategory.LAYOUT, 'er-dagre-grid', ErDagreGridLayout)

/** 边样式:候选虚线灰色 / 确认实线主题色 / 疑似多对多红色;两端按基数画竖杠/鸦脚 */
function edgeStyle(r) {
  const c = themeColors()
  const suspect = r.cardinality === 'SUSPECT_MANY_TO_MANY'
  const color = suspect ? c.danger : (r.status === 'CONFIRMED' ? c.primary : c.borderDarker)
  const is11 = r.cardinality === 'ONE_TO_ONE'
  // one 侧恒为「一」端;many 侧在 1:1 时仍是「一」端,疑似多对多时视为「多」端
  const startType = oneTick(is11)
  const endType = is11 ? oneTick(true) : crowFoot
  return {
    stroke: color,
    lineWidth: r.status === 'CONFIRMED' ? 1.8 : 1.4,
    lineDash: r.status === 'CANDIDATE' ? [6, 4] : 0,
    startArrow: true,
    startArrowType: startType,
    startArrowStroke: color,
    startArrowFill: 'transparent',
    startArrowLineWidth: 1.8,
    startArrowSize: [12, 12],
    endArrow: true,
    endArrowType: endType,
    endArrowStroke: color,
    endArrowFill: 'transparent',
    endArrowLineWidth: 1.8,
    endArrowSize: [12, 12],
    labelText: CARD_TEXT[r.cardinality] || r.cardinality,
    labelFontSize: 10,
    labelFill: c.textSecondary,
    labelBackground: true,
    labelBackgroundFill: c.bg,
    labelBackgroundOpacity: 0.85,
    labelPadding: [1, 4]
  }
}

/** props -> G6 数据;边 data 带两端字段名(field-cubic 边据此对齐字段行) */
function buildData() {
  relById = new Map()
  const nodes = props.nodes.map((n) => ({
    id: n.name,
    data: { table: n.name, comment: n.comment || '' },
    style: { size: nodeSize(n.name, n.comment) }
  }))
  const edges = props.edges.map((r) => {
    relById.set(String(r.id), r)
    return {
      id: String(r.id),
      source: r.oneTable,
      target: r.manyTable,
      data: { oneColumn: r.oneColumn, manyColumn: r.manyColumn },
      style: edgeStyle(r)
    }
  })
  return { nodes, edges }
}

/** 布局配置:组合布局 er-dagre-grid(有关系的表 dagre 层次 + 孤儿表底部多行网格),一次性算好不重绘 */
function layoutOptions() {
  // animation:false——G6 v5 布局动画是在 render() resolve 之后才推移节点的,
  // 开着动画时 fitView 采到的是未收敛位置(zoom 异常偏大、节点散出视口)
  return { type: 'er-dagre-grid', rankdir: 'LR', nodesep: 24, ranksep: 120, animation: false }
}

/** 平行边(同一对表多条关系)处理:仅「仅表名」档位需要按曲率分开——
 *  有字段行的档位下边端点已按字段行天然分开,且 bundle 会把自定义边改写成 quadratic 丢掉字段对齐 */
function parallelEdgeTransforms() {
  return props.level === 'name' ? [{ type: 'process-parallel-edges', mode: 'bundle', distance: 24 }] : []
}

// 底座输入:数据/配置均为 computed,props(含 level)变化 → data 变化触发底座整体重建
// (底座 refresh 每次重建重放 transforms,level 切换后平行边处理随之更新;重建后按 fit 口径重新定位);
// 触碰 themeState.dark:亮/暗主题切换时重算(节点/边颜色取自主题变量),底座整体重建换色
const graphData = computed(() => {
  void themeState.dark
  return buildData()
})
const graphOptions = computed(() => ({
  node: {
    type: 'html',
    style: {
      size: (d) => d.style?.size || [NODE_W, 40],
      innerHTML: (d) => renderNodeHtml(d)
    }
  },
  // 线型:curve=字段对齐贝塞尔;orth/orth-round=正交折线(倒角半径挂在图级边样式上,仅 field-polyline 消费)
  edge: {
    type: props.edgeType === 'curve' ? 'field-cubic' : 'field-polyline',
    style: { radius: props.edgeType === 'orth-round' ? 10 : 0 }
  },
  layout: layoutOptions(),
  transforms: parallelEdgeTransforms(),
  behaviors: [
    // 节点可拖拽(底座内置 drag-canvas 拖画布);Shift+拖 让位给套索框选(否则按住 Shift 拖节点会一边拖节点一边画套索)
    { type: 'drag-element', enable: (e) => !e.shiftKey },
    // 套索框选(Shift+拖动):圈中的节点即选中,html 节点同样生效(G6 转发节点上的指针事件,
    // 且坐标换算对 html 节点有专门处理)。只圈节点(enableElements 不含 edge);mode=default 每次框选重置选中集合,
    // 框空即清空;选中呈现不走 G6 state 样式(默认 selected 样式对本组件的数据样式无效),完全由 selectedTables 驱动重绘
    {
      type: 'lasso-select',
      trigger: ['shift'],
      enableElements: ['node'],
      mode: 'default',
      state: 'selected',
      animation: false,
      style: {
        width: 0,
        height: 0,
        lineWidth: 2,
        lineDash: [4, 4],
        stroke: themeColors().primary,
        fill: themeColors().primary,
        fillOpacity: 0.12,
        zIndex: 2,
        pointerEvents: 'none'
      },
      onSelect: (states) => {
        selectedTables.value = new Set(Object.keys(states || {}))
        lassoJustFinished = Date.now()
      }
    }
  ]
}))

// 鸟瞰图:只画节点缩略块(边在小图里只是噪音);html 节点的 key 形状是 DOM 画不进小画布,
// 用底座的 createHtmlMinimapShape 工厂克隆等大小容器矩形充当缩略块;锚点表主题色描边突出
const minimapOptions = computed(() => ({
  size: [160, 100],
  filter: (id, type) => type === 'node',
  shape: createHtmlMinimapShape((id) => id === props.anchorTable)
}))

// 名字口径开关:只影响节点 HTML 渲染(renderNodeHtml 读 props.fieldNameMode),
// 不进图数据、不改节点尺寸(副标题行有无只看 comment) → 走底座 repaint(只重绘不重排,同对象管理图口径)
watch(() => props.fieldNameMode, () => baseRef.value?.repaint(buildData()))

// ---------- 事件口径:单击节点选中(单击表名除外,见 onContainerClick)/ 点边回查原始关系 / 双击表名跳字段明细 ----------
function onNodeClick(id) {
  // 单击表名不开面板(双击表名才跳字段明细):表名单击已打时间戳,这里忽略
  if (Date.now() - lastTitleClick < 250) return
  emit('node-click', id)
}

function onEdgeClick(id) {
  const rel = relById.get(id)
  if (rel) emit('edge-click', rel)
}

/** 点画布空白(底座 canvas-click):清空选中;刚套索框选结束时补发的 click 忽略,否则框选结果会被立刻清空 */
function onCanvasClick() {
  if (Date.now() - lassoJustFinished < 300) return
  clearSelection()
}

/** 底部操作条「删除」:否决选中表的所有关系——与「候选管理-批量否决」同一接口与口径
 *  (候选/确认均转否决,再次推导命中会回炉为候选);成功后清空选中并 emit changed,
 *  父级按 ids 剔除这些边(星型图同步摘除失去全部连线的邻表节点),与关系抽屉单条否决同口径 */
async function rejectSelected() {
  const edges = affectedEdges.value
  if (!edges.length || rejecting.value) return
  try {
    await ElMessageBox.confirm(
      `将否决与选中 ${selectedTables.value.size} 张表相关的 ${edges.length} 条关系(候选与已确认关系都会转为否决,与「关系管理-批量否决」同口径;否决后重新推导命中会回炉为候选),确定删除?`,
      '删除选中表的关系',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  rejecting.value = true
  try {
    const ids = edges.map((e) => e.id)
    const res = await batchRejectRelations(ids)
    ElMessage.success(`已否决 ${res?.updated ?? 0} 条关系`)
    clearSelection()
    emit('changed', { action: 'reject', ids })
  } finally {
    rejecting.value = false
  }
}

/** 容器层 click 委托(捕获阶段):「+N 个字段」展开收起 / 表名单击抑制时间戳 / 点节点本体选中。
 *  选中口径与图谱页一致:普通点击单选、Shift+点击增减;拖拽节点后的残留 click(位移>4px)不响应 */
function onContainerClick(ev) {
  // 「+N 个字段」/「收起字段」操作行:切换展开态(属于节点内操作,不触发选中)
  const moreRow = ev.target?.closest?.('.rg-more-row')
  if (moreRow) {
    lastTitleClick = Date.now() // 同单击表名:阻止 node-click 开面板
    const moved = downPos && (Math.abs(ev.clientX - downPos[0]) + Math.abs(ev.clientY - downPos[1]) > 4)
    const table = moreRow.getAttribute('data-rg-table')
    if (!moved && table) toggleExpand(table)
    return
  }
  const title = ev.target?.closest?.('.rg-node-title')
  if (title) lastTitleClick = Date.now() // 仍抑制「单击表名开面板」;但选中照常(双击跳转前也会先选中,无副作用)
  // 点节点本体选中(标题区也算;与图谱页同口径:普通点击单选、Shift+点击增减);拖拽节点后的残留 click(位移>4px)不响应
  const nodeEl = ev.target?.closest?.('[data-rg-node]')
  if (!nodeEl) return
  const moved = downPos && (Math.abs(ev.clientX - downPos[0]) + Math.abs(ev.clientY - downPos[1]) > 4)
  if (moved) return
  const table = nodeEl.getAttribute('data-rg-node')
  if (table) selectTable(table, ev.shiftKey)
}

/** 记录指针按下坐标(拖拽误触过滤,见 onContainerClick);
 *  同时打表名/「+N 个字段」行的单击抑制时间戳——G6 html 节点的 node:click 由 pointerup 合成派发,
 *  早于 DOM click(实测同帧但先到),时间戳在 click 阶段打就来不及了 */
function onContainerPointerdown(ev) {
  downPos = [ev.clientX, ev.clientY]
  if (ev.target?.closest?.('.rg-node-title, .rg-more-row')) lastTitleClick = Date.now()
}

/** 「+N 个字段」/「收起字段」点击:切换节点展开态并就地重渲染该节点(不重布局、不动视口;边端点随可见行重对齐) */
async function toggleExpand(table) {
  const graph = baseRef.value?.getGraph()
  if (!graph) return
  if (expandedTables.has(table)) expandedTables.delete(table)
  else expandedTables.add(table)
  const comment = graph.getNodeData(table)?.data?.comment || ''
  graph.updateNodeData([{ id: table, style: { size: nodeSize(table, comment) } }])
  await graph.render()
}

/** 双击表名跳字段明细:HTML 节点内 .rg-node-title 的捕获阶段委托 */
function onContainerDblclick(ev) {
  const title = ev.target?.closest?.('.rg-node-title')
  if (!title) return
  const table = title.getAttribute('data-rg-table')
  if (table) emit('node-open', table)
}

onMounted(() => {
  // 容器层事件委托(捕获阶段,先于 G6 转发):表名单击时间戳/「+N 个字段」展开收起/双击表名跳转
  const el = baseRef.value.getContainer()
  el.addEventListener('click', onContainerClick, true)
  el.addEventListener('dblclick', onContainerDblclick, true)
  el.addEventListener('pointerdown', onContainerPointerdown, true)
})

/**
 * 导出数据(供「导出 drawio」使用):
 *  节点 = 表名/注释 + G6 实测布局中心(拖动后的实时位置)+ 当前可见字段行(复用 visibleFields,
 *        与画布渲染严格一致:折叠态 maxFieldRows 行+「+N 个字段」计数,展开态全量);坐标取不到(未布局)时置 null,由导出端网格兜底;
 *  边 = 原始关系数据(props.edges,不受平行边 bundle 改写影响)
 */
function exportData() {
  const graph = baseRef.value?.getGraph()
  if (!graph) return null
  const nodes = props.nodes.map((n) => {
    let cx = null
    let cy = null
    try {
      const p = graph.getElementPosition(n.name)
      cx = p[0]
      cy = p[1]
    } catch (e) { /* 未布局节点:导出端网格兜底 */ }
    const fields = nodeFields(n.name)
    const rows = visibleFields(n.name)
    return {
      name: n.name,
      comment: n.comment || '',
      cx,
      cy,
      rows: rows.map((f) => ({ name: f.name, type: f.type || '', related: !!f.related })),
      more: Math.max(0, fields.length - rows.length)
    }
  })
  return { nodes, edges: props.edges }
}

// 工具栏「1:1」/「适应画布」:直接走底座
function zoomTo100() { baseRef.value?.zoomTo100() }
async function fitView() { await baseRef.value?.fitView() }

defineExpose({ exportData, zoomTo100, fitView })
onUnmounted(() => {
  const el = baseRef.value?.getContainer()
  el?.removeEventListener('click', onContainerClick, true)
  el?.removeEventListener('dblclick', onContainerDblclick, true)
  el?.removeEventListener('pointerdown', onContainerPointerdown, true)
})
</script>

<style scoped>
/* 画布外包:底座 .bgc-wrap 是 100% 高,这里给个相对定位容器挂底部操作条 */
.rg-wrap {
  position: relative;
  width: 100%;
  height: 100%;
}
/* 选中操作条:底部居中悬浮(左下角图例/右下角鸟瞰图让开),毛玻璃底与底座工具栏控件区同口径;
   边框用 darker 一档(lighter 在毛玻璃底下几乎看不见,操作条会融进背景) */
.rg-selbar {
  position: absolute;
  bottom: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--el-bg-color) 72%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--el-border-color-darker);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
  z-index: 10;
}
.rg-selbar :deep(.el-button) {
  margin-left: 0;
}
.rg-selbar-text {
  font-size: 12px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}
/* 画布操作提示:常驻,贴鸟瞰图左侧(鸟瞰图 160 宽 + 12 右边距,再留 8 间距),底边与鸟瞰图对齐;
   占位符色(比次级色更浅)弱提示,不抢图内容视觉;pointer-events 穿透,不挡画布拖拽 */
.rg-hint {
  position: absolute;
  right: 180px;
  bottom: 12px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
  pointer-events: none;
  z-index: 5;
}
/* 图例内容样式(定位/面板外壳/折叠由底座 legend 折叠面板负责):与边样式口径一致(实线主题色=确认 / 虚线灰=候选 / 红=疑似多对多) */
.rg-legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rg-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.rg-line {
  display: inline-block;
  width: 26px;
  height: 0;
  border-top: 2px solid;
}
.rg-line-confirmed {
  border-top-color: var(--el-color-primary);
}
.rg-line-candidate {
  border-top: 2px dashed var(--el-border-color-darker);
}
.rg-line-suspect {
  border-top-color: var(--el-color-danger);
}
</style>
