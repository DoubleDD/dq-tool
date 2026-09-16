<template>
  <!-- G6 ER 图画布:通用画布底座 BaseGraphCanvas 承载交互(滚轮平移/Ctrl+滚轮与触摸板捏合缩放/Alt+滚轮水平平移/
       html 节点滚轮转发与双击补发/Shift 框选与多选[selectable]),顶部工具栏(默认五工具:重绘/1:1/适应画布/线形/档位
       + toolbar 插槽业务工具,右侧缩放控制条)、右下角鸟瞰图、左下角图例折叠面板、视口定位与生命周期;本组件只负责
       ER 专属部分:html 表节点渲染、field-cubic 字段对齐边、er-dagre-grid 布局、表名单击/双击口径、
       选中集合承接(普通点击单选经底座 setSelection 接入)/批量否决、边样式图例(legend 插槽)。容器需显式高度(由父级布局保证) -->
  <div class="rg-wrap">
    <!-- 全屏按钮:mapping 模式(字段映射)显示——比对画布大、需要全屏铺开操作;ER 关系图保持不显示 -->
    <BaseGraphCanvas
      ref="baseRef"
      :data="graphData"
      :options="graphOptions"
      :fit="defaultZoom > 0 ? 'center' : 'view'"
      :default-zoom="defaultZoom > 0 ? defaultZoom : 1"
      :options-key="edgeType"
      :minimap="minimapOptions"
      :fullscreen="mode === 'mapping'"
      :tools="tools"
      :level="level"
      :edge-type="edgeType"
      :custom-redraw="mode === 'mapping' ? relayoutMapping : null"
      custom-redraw-tip="重新排列(还原自动布局,并缩放平移以容纳全部节点)"
      image-name="ER图"
      @update:level="emit('update:level', $event)"
      @update:edge-type="emit('update:edgeType', $event)"
      @export-drawio="emit('export-drawio')"
      @node-click="onNodeClick"
      @edge-click="onEdgeClick"
      @canvas-click="onCanvasClick"
      @selection-change="onSelectionChange"
      @rendered="onRendered"
      selectable
    >
      <!-- 业务工具透传:字段数/导出等由调用方按需给 -->
      <template #toolbar><slot name="toolbar" /></template>
      <template v-if="mode !== 'mapping'" #legend>
        <span class="rg-legend-item"><i class="rg-line rg-line-confirmed" />确认</span>
        <span class="rg-legend-item"><i class="rg-line rg-line-candidate" />候选</span>
        <span class="rg-legend-item"><i class="rg-line rg-line-suspect" />疑似多对多</span>
      </template>
      <!-- 全屏内浮层必须进底座根节点(.bgc-wrap,全屏只渲染全屏元素及其后代):
           悬停连线浮层(mapping)+ 调用方 overlay 插槽(字段映射的连线删除确认条)走默认插槽落进底座根内 -->
      <div v-show="edgeTip.show" class="rg-edgetip" :style="{ left: edgeTip.x + 'px', top: edgeTip.y + 'px' }">{{ edgeTip.text }}</div>
      <slot name="overlay" />
    </BaseGraphCanvas>
    <!-- 选中操作条(底部居中悬浮):点节点/Shift 点选/Shift 拖动框选后出现;
         「删除」= 否决选中表的所有关系(与「候选管理-批量否决」同口径:候选/确认均转否决),成功后 emit changed 由父级刷新图;
         mapping 模式不展示(字段映射没有「否决关系」语义) -->
    <div v-if="mode !== 'mapping' && selectedTables.size" class="rg-selbar">
      <span class="rg-selbar-text">已选 {{ selectedTables.size }} 张表</span>
      <el-tooltip content="否决选中表的所有关系(候选与已确认均转为否决),与「候选管理-批量否决」同口径" placement="top">
        <el-button size="small" type="warning" :disabled="!affectedEdges.length" :loading="rejecting" @click="rejectSelected">
          删除{{ affectedEdges.length ? `(${affectedEdges.length} 条关系)` : '' }}
        </el-button>
      </el-tooltip>
      <el-button size="small" @click="clearSelection">取消</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
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
//  交互:点节点选中(深描边+主题色光环,锚点表恒为主题色底纹)、Shift+点击增减选中、Shift+拖动框选、
//  点画布空白清空(框选/多选/清空为底座 selectable 基础能力,选中集合经 selection-change 同步;
//  普通点击单选由容器委托经底座 setSelection 接入同一选中状态);
//  底部操作条「删除」= 批量否决选中表的所有关系(与「候选管理-批量否决」同口径)
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
  // 单表最多展示的字段行数(超出折叠为「+N 个字段」,可点击就地展开):工具栏可设置,默认 10;mapping 模式全量展示不折叠
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
  fieldNameMode: { type: String, default: 'chinese' },
  // 画布用途:relation=ER 关系图(默认,行为与改动前完全一致);
  //   mapping=数据比对「字段映射」(节点标题取 label、副标题取 comment;字段行可点连线;隐藏线型/档位/导出 drawio 与批量否决条,
  //   「另存为图片」为底座常驻工具仍显示;图例换成操作提示;点对比表标题区聚焦该表 ↔ 基准表的连线(其余表与连线变暗),
  //   Shift+点击/Shift+拖动框选可多表聚焦(聚焦集合 = 选中集合,与单点标题同一逻辑),点基准表标题/空白取消;
  //   工具栏「重绘」经底座 customRedraw 接管为「重新排列」= 恢复自动布局,拖动/增删连线/聚焦均不重排)
  mode: { type: String, default: 'relation' },
  // mapping 模式:当前待连线的基准字段名(该行高亮,提示「点它再点对侧字段」)
  activeColumn: { type: String, default: '' },
  // mapping 模式:锚点表(基准表)里**参与比对的基准字段**——文字常亮高亮(主题色+加粗,不改背景),
  // 不管有没有连线都亮;对比表字段不受此参数影响(连了线才亮)
  highlightColumns: { type: Array, default: () => [] },
  // mapping 模式:选中态连线 id(父级在删除确认期间传入,该线加粗变红;空串 = 无选中)
  selectedEdgeId: { type: String, default: '' }
})

// edge-click:点边(传 TableRelation);node-click:点节点(传表名);node-open:双击节点表名(传表名,跳字段明细);
// field-click:mapping 模式点字段行(传 { table, column });mapping-connect:mapping 模式 create-edge 新建边完成(传两端表+字段);
// canvas-click:点画布空白(mapping 模式父级据此收起连线删除确认条);
// update:level / update:edgeType:工具栏档位/线形切换(配 v-model 用);
// export-drawio:工具栏「导出 drawio」(第 5 个图标位;调用方经 exportData() 取数据并下载);
// changed:批量否决成功 { action: 'reject', ids },父级据此剔除这些边并刷新图(与关系抽屉单条操作同口径)
const emit = defineEmits(['edge-click', 'node-click', 'node-open', 'field-click', 'mapping-connect', 'canvas-click', 'update:level', 'update:edgeType', 'export-drawio', 'changed'])

const baseRef = ref(null)
// 边 id -> TableRelation(点边时回查原始数据)
let relById = new Map()
// 单击表名与点节点都会触发 node-click:单击表名只打时间戳不开面板(跳转改双击),node-click 据此忽略
let lastTitleClick = 0
// 已展开全部字段的表(点「+N 个字段」展开、点「收起字段」还原;不持久化,画布重建后保留)
const expandedTables = new Set()
// 指针按下坐标:拖拽节点结束时也会冒出一个 click,用它过滤「拖拽误触」的展开/收起
let downPos = null

// ---------- 节点选中(底座 selectable 框选/多选 + 普通点击单选) ----------
// 选中表集合:Shift+拖动框选/Shift+点击增减/点空白清空走底座 selectable(选中集合经 selection-change 全量同步),
// 普通点击单选由容器层 click 委托经底座 setSelection 接入(见 onContainerClick);
// 纯渲染口径(描边/底纹),变化经 repaint 就地刷新,不动布局与视口
const selectedTables = ref(new Set())
// 批量否决请求进行中(防重复提交)
const rejecting = ref(false)
// 选中表涉及的关系(底部操作条「删除」= 否决这些关系,与「候选管理-批量否决」同口径)
const affectedEdges = computed(() => {
  const sel = selectedTables.value
  if (!sel.size) return []
  return props.edges.filter((e) => sel.has(e.oneTable) || sel.has(e.manyTable))
})

/** 工具栏工具集:mapping 模式只留 重绘/1:1/适应画布(线型、档位、导出 drawio 都是 ER 关系图专属),
 *  其中「重绘」经底座 customRedraw 接管为「重新排列」(恢复自动布局,见 relayoutMapping);
 *  「另存为图片」为底座常驻工具、不经 tools 裁剪,mapping 模式同样显示 */
const tools = computed(() => props.mode === 'mapping'
  ? ['refresh', 'zoom100', 'fit']
  : ['refresh', 'zoom100', 'fit', 'edge-type', 'export-drawio', 'level'])

/** 底座选中集合变化(框选/点选/清空/setSelection):全量同步给本地渲染口径;
 *  内容没变不触动——空白点击会无条件上报一次,换个同内容新 Set 会引发无谓的就地重绘;
 *  mapping 模式:聚焦集合 = 选中集合(基准表除外)——单点标题、Shift+点击、Shift+框选同一逻辑,
 *  都是「凸显选中表 ↔ 基准表的连线,其余表与连线压暗禁用」 */
function onSelectionChange(ids) {
  const next = new Set(ids || [])
  const cur = selectedTables.value
  if (next.size === cur.size && [...next].every((t) => cur.has(t))) return
  selectedTables.value = next
  if (props.mode === 'mapping') {
    focusTables.value = new Set([...next].filter((t) => t !== props.anchorTable))
    // 连线橡皮筋进行中重绘会被跳过(createEdgePending),延一拍补刷,避免压暗态滞留不生效
    if (createEdgePending()) setTimeout(() => repaintInPlace(), 0)
  }
}

// ---------- mapping 模式「聚焦表」 ----------
// 聚焦集合(可多表):点对比表标题区(不含字段行)单选聚焦,Shift+点击增减、Shift+拖动框选多选,
// 口径与底座选中集合一致(见 onSelectionChange);点基准表标题 / 点画布空白清空。
// 纯渲染口径:节点 HTML(renderNodeHtml)与边样式(mappingEdgeStyle)都读 focusTables,就地重绘生效,不动布局与位置
const focusTables = ref(new Set())

/** 单表聚焦(点标题):经底座选中态接入统一选中集合(selection-change 里同步聚焦集合);
 *  基准表标题 = 清空(基准表不参与聚焦) */
function applyFocus(table) {
  if (!table || table === props.anchorTable) baseRef.value?.clearSelection()
  else baseRef.value?.setSelection([table])
}

/** 聚焦压暗判定(仅 mapping):聚焦集合非空时,不在集合内、非基准的表压暗,且整体禁用交互
 *  (节点 pointerEvents:none——字段行/标题点击、悬停、拖动全部穿透到画布,点它等于点空白取消聚焦) */
function isDimmedTable(table) {
  return props.mode === 'mapping' && !!focusTables.value.size && !focusTables.value.has(table) && table !== props.anchorTable
}

/** 底座 rendered(初始化/重建/重绘):重建会清掉 G6 模型里的选中态,把本地选中集回灌底座保持一致
 *  (setSelection 只在有变化时发事件:回灌那次发一次,之后状态一致不再发,不会循环) */
function onRendered() {
  if (selectedTables.value.size) baseRef.value?.setSelection([...selectedTables.value])
  // 重建后边元素是全新的:删除确认中的选中线样(加粗变红)按 selectedEdgeId 重上一遍
  if (props.selectedEdgeId) applyEdgeSelected(props.selectedEdgeId)
  // mapping 模式:边不进 graphData,首次挂载/结构重建后在这里把连线就地补挂
  syncEdgesInPlace()
}

/** 清空选中:本地集合与底座选中态一起清(底座 clearSelection 会再发一次 selection-change,幂等) */
function clearSelection() {
  baseRef.value?.clearSelection()
  if (selectedTables.value.size) selectedTables.value = new Set()
}

// 选中集合变化 → 就地重绘节点 HTML 刷选中描边/底纹(不跑布局,拖动后的节点位置不丢)
watch(selectedTables, () => repaintInPlace())
// 图数据重建(刷新/批量否决后):选中集合与聚焦表裁掉已不在图里的表,操作条计数随之为准
watch(
  () => props.nodes,
  (nodes) => {
    const names = new Set((nodes || []).map((n) => n.name))
    const next = new Set([...selectedTables.value].filter((t) => names.has(t)))
    if (next.size !== selectedTables.value.size) selectedTables.value = next
    if (focusTables.value.size) {
      const pruned = new Set([...focusTables.value].filter((t) => names.has(t)))
      if (pruned.size !== focusTables.value.size) focusTables.value = pruned
    }
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
    borderLighter: get('--el-border-color-lighter', '#ebeef5'),
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
// 节点几何常量(配合 firstRowTop 使用):标题区=主标题行 34 + 上下 padding 6+6(有中文名时内含 14px 英文表名小字行);
// 字段容器上 padding 4、行高 18、折叠行 16,底部留白 4;根节点边框与标题下边框的 2~3px 收敛在 firstRowTop
const TITLE_H_COMMENT = 46
const TITLE_H_PLAIN = 32
const FIELD_PAD_TOP = 4
const FIELD_PAD_BOTTOM = 4
const ROW_H = 18
const MORE_ROW_H = 16

/** 首个字段行顶部到节点顶边的距离(端点几何与 renderNodeHtml 严格对齐):
 *  标题区(46/32) + 字段容器上 padding 4 + 两处容易被漏算的渲染细节——
 *  根节点边框(border-box:普通表 1px、基准表/选中表 2px)把内容整体下推、
 *  标题区在有字段行时还有 1px border-bottom;漏算会让端点相对行中线偏上 2~3px(圆点骑框后肉眼可辨);
 *  仅在「行存在」的分支使用(标题下边框只在有字段行时渲染) */
function firstRowTop(table, comment) {
  const borderTop = table === props.anchorTable || selectedTables.value.has(table) ? 2 : 1
  return borderTop + (comment ? TITLE_H_COMMENT : TITLE_H_PLAIN) + 1 + FIELD_PAD_TOP
}

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

/** 节点当前可见字段行:ER 图按 maxFieldRows 折叠(展开表全量);mapping 模式全量展开——
 * 字段映射要对照两侧全表字段人工连线,「+N 个字段」折叠会让目标行点不到 */
function visibleFields(table) {
  const fields = nodeFields(table)
  if (props.mode !== 'mapping') {
    return expandedTables.has(table) ? fields : fields.slice(0, props.maxFieldRows)
  }
  return fields
}

/** 是否出现「+N 个字段」折叠行(mapping 模式:可见行少于总行数时出现) */
function hasFoldRow(table) {
  return nodeFields(table).length > visibleFields(table).length
}

/** 节点尺寸:标题区(表名+注释) + 可见字段行 + 折叠/收起行;尺寸与 renderNodeHtml 实际渲染严格一致(边端点按此几何对齐字段行) */
function nodeSize(table, comment) {
  const titleH = comment ? TITLE_H_COMMENT : TITLE_H_PLAIN
  const fields = nodeFields(table)
  if (!fields.length) return [NODE_W, titleH]
  // 超 maxFieldRows 时恒有一行操作行(折叠态=「+N 个字段」,展开态=「收起字段」)
  const moreH = hasFoldRow(table) ? MORE_ROW_H : 0
  return [NODE_W, titleH + FIELD_PAD_TOP + visibleFields(table).length * ROW_H + moreH + FIELD_PAD_BOTTOM]
}

/** 节点 HTML:标题(有中文名时中文名为主、英文表名小字在下;标题可点跳字段明细) + 字段行(关联字段高亮);
 *  超 maxFieldRows 折叠为「+N 个字段」操作行(点击就地展开全量,展开后该行变「收起字段」);
 *  选中态 = 正文色深描边 + 主题色光环;ER 图锚点表(本表/星型中心)恒为主题色底纹;
 *  mapping 模式基准表去底纹只留主题色描边(靠边框 + 基准字段文字高亮辨认);
 *  根 div 带 data-rg-node 标记,容器层点击委托按它判定「点节点选中」 */
function renderNodeHtml(d) {
  const c = themeColors()
  const { table, comment, label, tableComment } = d.data
  const mapping = props.mode === 'mapping'
  const isAnchor = table === props.anchorTable
  const isSelected = selectedTables.value.has(table)
  // 聚焦(仅 mapping):点对比表标题后,非聚焦表(基准表除外)整体变暗并禁用交互,凸显聚焦表 ↔ 基准表的连线
  const dimmed = isDimmedTable(table)
  const fields = nodeFields(table)
  const expanded = expandedTables.has(table)
  const rows = visibleFields(table)
  const fieldHtml = rows.map((f) => {
    const isActive = mapping && isAnchor && props.activeColumn && f.name === props.activeColumn
    // 字段高亮一律走文字(主题色+加粗)、不改背景:基准表里参与比对的字段(highlightColumns)不管有没有
    // 连线都常亮;对比表只有连了线的字段才亮,没连线的保持次要灰——一眼区分「要比的」与「已映射的」
    const isBaseField = mapping && isAnchor && props.highlightColumns.includes(f.name)
    const style = isActive
      ? `color:${c.primary};font-weight:600;background:${c.primary}26;border-radius:3px`
      : (f.related || isBaseField) ? `color:${c.primary};font-weight:600` : `color:${c.textSecondary}`
    // 字段名口径三档:chinese=仅中文注释(无注释回退英文名);english=仅英文名;both=中文在前、英文名更淡尾随;
    // 超长随行截断「…」;整行 title 恒带 英文名+完整注释,hover 即可查看全文
    const main = props.fieldNameMode === 'english' ? f.name : (f.comment || f.name)
    const tailHtml = props.fieldNameMode === 'both' && f.comment
      ? `<span style="margin-left:4px;opacity:.7;font-weight:400">${esc(f.name)}</span>` : ''
    const type = f.type ? `<span style="flex:none;padding-left:8px;opacity:.75;font-weight:400">${esc(f.type)}</span>` : ''
    const tipText = [f.name, f.comment].filter(Boolean).join(' ')
    const tip = tipText ? ` title="${esc(tipText)}"` : ''
    // data-rg-column:容器层点击委托据此在 mapping 模式下派发 field-click(点字段行连线)
    const colAttr = ` data-rg-column="${esc(f.name)}" data-rg-column-table="${esc(table)}"`
    // mapping 模式字段行悬停给十字光标(正在连线的语义);ER 图字段行不可点,保持默认
    const cursor = mapping ? 'cursor:crosshair;' : ''
    // mapping 模式给每个字段行加上下 0.5px 发丝分隔线(表格感;1px 近距离视觉比节点边框还抢;
    // 颜色用 --el-border-color 常规边框灰——lighter 灰叠在 active 行的淡色底上几乎不可见)。
    // 用 background-image 渐变实现:Chrome 会把 border-width:0.5px 计算值取整成 1px,边框做不到真半像素;
    // 行高不加边框仍是 18px(边端点/节点高度按 ROW_H=18 推算,撑高会端点对不齐)。
    // 放在 ${style} 之后:基准字段的 background 简写会清掉 background-image,后者补上两者兼得;
    // hover 高亮改 background 时分隔线暂隐、还原后恢复,视觉无碍
    const sepBg = mapping
      ? `background-image:linear-gradient(to bottom, ${c.border}, ${c.border} 0.5px, rgba(0,0,0,0) 0.5px), linear-gradient(to top, ${c.border}, ${c.border} 0.5px, rgba(0,0,0,0) 0.5px);background-size:100% 0.5px, 100% 0.5px;background-position:0 0, 0 100%;background-repeat:no-repeat;`
      : ''
    return `<div${tip}${colAttr} style="display:flex;align-items:center;height:18px;line-height:18px;padding:0 8px;font-size:11px;overflow:hidden;${style};${sepBg}${cursor}"><span style="flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(main.replace(/\s+/g, ' '))}${tailHtml}</span>${type}</div>`
  }).join('')
  const more = hasFoldRow(table)
    ? `<div class="rg-more-row" data-rg-table="${esc(table)}" title="${expanded ? '收起字段' : '点击展开全部字段'}" style="height:16px;line-height:16px;padding:0 8px;font-size:11px;color:${c.primary};cursor:pointer;white-space:nowrap">${expanded ? '收起字段' : `+${fields.length - props.maxFieldRows} 个字段`}</div>` : ''
  // 名字口径与字段一致:中文档(含 both)中文注释为主标题、英文表名小字副标题;
  // 仅英文名档则反过来(同对象管理图;副标题行有无只看 comment,节点几何不变);
  // mapping 模式统一「中文名加粗第一行、英文名第二行」(与映射管理弹窗同口径):
  //   有表注释(中文表名)时主标题=中文表名、副标题=数据源·库.模式.表(定位串,含英文名);
  //   无注释时主标题=定位串、副标题=英文表名。
  //   两种情形都有副标题行——节点几何(TITLE_H_COMMENT=46)与 fieldEndpoint 按 comment 恒非空推算,
  //   这里必须保持「恒有副标题」,否则标题矮 14px、边端点对不齐行
  const englishOnly = props.fieldNameMode === 'english'
  const titleText = mapping ? (tableComment || label || table) : (englishOnly ? table : (comment || table))
  const subText = mapping ? (tableComment ? (label || comment) : (comment || '')) : (comment ? (englishOnly ? comment : table) : '')
  const subHtml = subText
    ? `<div style="height:14px;line-height:14px;padding:0 8px;font-size:11px;color:${c.textSecondary};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(subText)}</div>` : ''
  const border = isSelected ? `2px solid ${c.text}` : isAnchor ? `2px solid ${c.primary}` : `1px solid ${c.borderDarker}`
  // 光环:选中与锚点同语言(主题色 20% 淡环);ER 图锚点另有主题色底纹 + 主题色描边/标题,始终一眼可辨;
  // mapping 模式基准表按用户要求只留主题色描边(去底纹/光环),靠边框与加粗字段文字辨认
  const ring = isSelected || (isAnchor && !mapping) ? `box-shadow:0 0 0 3px ${c.primary}33;` : ''
  // 锚点底纹(仅 ER 图):根底 = 主题色 8% 淡 tint;有字段行时标题行叠 15% 更深一档(无字段行时标题透明,直接透出根底)
  const titleBg = fields.length ? `background:${isAnchor && !mapping ? `${c.primary}26` : c.fill};` : ''
  const titleTip = mapping ? '点击聚焦本表连线(再点基准表或空白取消);点字段行连线;拖动移动表' : '双击打开字段明细'
  return `<div data-rg-node="${esc(table)}" style="width:${NODE_W}px;height:100%;box-sizing:border-box;background:${isAnchor && !mapping ? `${c.primary}14` : c.bg};border:${border};border-radius:6px;overflow:hidden;${ring}transition:opacity .15s;${dimmed ? 'opacity:0.2;' : ''}">
  <div class="rg-node-title" data-rg-table="${esc(table)}" title="${titleTip}" style="height:${subText ? 34 : 32}px;line-height:${subText ? 20 : 32}px;padding:${subText ? '6px' : '0'} 8px;font-size:13px;font-weight:600;color:${isAnchor ? c.primary : c.text};white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer;${fields.length ? `border-bottom:1px solid ${c.border};${titleBg}` : ''}">${esc(titleText)}${subHtml}</div>
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
  return -h / 2 + firstRowTop(table, comment) + idx * ROW_H + ROW_H / 2
}

/** 字段行中心点(节点左/右缘)+ 出边侧(+1 右 / -1 左);行不可见时退化为边框交点 */
function fieldEndpoint(self, nodeEl, column, oppositeEl) {
  const table = nodeEl.id
  let comment = ''
  try {
    comment = self.context.graph.getNodeData(table)?.data?.comment || ''
  } catch {
    comment = '' // 节点刚被移除(重建/删除)时同样退化为无注释口径
  }
  const [w, h] = nodeSize(table, comment)
  // getCenter() 恒为元素几何中心(实测:数据级 style.x/y 的 html 节点,DOM 左上角=model 点、
  // 元素局部几何以 model 点为原点,中心即 model+半宽半高;布局坐标亦同),两种模式同一口径,直接按中心算
  const center = nodeEl.getCenter()
  const opposite = oppositeEl.getCenter()
  // create-edge 橡皮筋辅助边:起点(source 端)钉死在「待连线字段行」的出边侧缘中点——
  // 兜底 borderPoint 会随鼠标位置绕节点边框滑动,视觉上起点不稳;起点出边侧按表角色固定
  // (基准表在左恒出右边、对比表在右恒出左边)。起点字段在 pointerdown 且橡皮筋未激活时
  // 单独记录(pendingStartField,见 onContainerPointerdown),不受后续点击日志影响
  if (props.mode === 'mapping' && self.id === 'g6-create-edge-assist-edge-id' && nodeEl === self.sourceNode) {
    const pIdx = pendingStartField && pendingStartField.table === table
      ? visibleFields(table).findIndex((f) => f.name === pendingStartField.column)
      : -1
    if (pIdx >= 0) {
      const side = table === props.anchorTable ? 1 : -1
      const x = center[0] + side * (w / 2)
      const y = center[1] - h / 2 + firstRowTop(table, comment) + pIdx * ROW_H + ROW_H / 2
      return { point: [x, y, 0], side }
    }
  }
  // 辅助边 target 端 = 跟随鼠标的隐藏辅助圆点:端点即圆心(鼠标位置),别拿本组件的几何常量去算边框交点
  if (table === 'g6-create-edge-assist-node-id') {
    return { point: [center[0], center[1], 0], side: opposite[0] >= center[0] ? 1 : -1 }
  }
  const idx = column == null
    ? -1
    : visibleFields(table).findIndex((f) => f.name === column)
  if (idx < 0) {
    return { point: borderPoint(center, w, h, opposite), side: opposite[0] >= center[0] ? 1 : -1 }
  }
  const side = opposite[0] >= center[0] ? 1 : -1
  const x = center[0] + side * (w / 2)
  const y = center[1] - h / 2 + firstRowTop(table, comment) + idx * ROW_H + ROW_H / 2
  return { point: [x, y, 0], side }
}

/** 两端字段行对齐端点 + 出边侧(field-cubic / field-polyline 两种线型共用) */
function erEdgeEndpoints(self) {
  // create-edge 的橡皮筋辅助边(g6-create-edge-assist-edge-id)在「建边/取消」瞬间会被移除,
  // 而重绘(getKeyPath)可能晚一拍才拿到它——G6 的 getEdgeData 对不存在的边直接抛错,
  // 不兜住会打断整次 repaint。取不到就按「无字段」处理:端点退化到节点边框交点,视觉无碍。
  let rel = {}
  try {
    rel = self.context.graph.getEdgeData(self.id)?.data || {}
  } catch {
    rel = {}
  }
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

// ---------- mapping 模式布局 mapping-row ----------
// 字段映射不需要层次关系图:第 1 个节点(基准表,锚点)固定在左,其余节点(各对比表)在右侧
// **一行等距水平排开**,且所有卡片顶边落在同一条水平线上(顶部对齐——卡片高度差很大时,
// 中心对齐会让矮卡片悬浮在半空,连线上下跨度也大;顶对齐从第一行字段就开始水平对照)。
// 表多时靠画布缩放/平移(适应画布/鸟瞰图)容纳,而不是折行——折行会破坏「同一条顶线」。
//
// 坐标是**纯函数算好写进节点样式**的(buildData 里调用),布局类只把同一份坐标回吐:
// G6 对 html 节点只认数据级 style.x/y(布局结果异步回填,不会同步到 DOM),且该口径下
// 坐标按「左上角」对齐 DOM —— 故这里写左上角坐标;连线端点经 getCenter() 取元素几何中心统一计算(见 fieldEndpoint)。
function mappingRowPositions(nodes, opts = {}) {
  const sizeOf = (n) => {
    if (n.data?.table) return nodeSize(n.data.table, n.data.comment || '')
    const sz = n.style?.size
    const num = (v, fb) => { const x = Number(v); return Number.isFinite(x) && x > 0 ? x : fb }
    if (Array.isArray(sz)) return [num(sz[0], NODE_W), num(sz[1], 40)]
    return [num(sz, NODE_W), 40]
  }
  const out = new Map()
  if (!nodes.length) return out
  const gapX = opts.ranksep ?? 160
  const anchor = nodes[0]
  const [aw, ah] = sizeOf(anchor)
  const top = -ah / 2 // 顶对齐:所有卡片顶边与锚点顶边同线
  out.set(String(anchor.id), { x: -aw / 2, y: top })
  let x = aw / 2 + gapX
  for (const n of nodes.slice(1)) {
    const [w, h] = sizeOf(n)
    out.set(String(n.id), { x, y: top })
    x += w + gapX
  }
  return out
}

class MappingRowLayout extends BaseLayout {
  id = 'mapping-row'
  // 坐标已写进节点数据,这里返回不带坐标的空结果:G6 会把结果与现有节点数据合并,不返回即保留
  async execute(model) {
    const { nodes = [] } = model
    return { nodes: nodes.map((n) => ({ id: String(n.id) })), edges: [], combos: [] }
  }
}
register(ExtensionCategory.LAYOUT, 'mapping-row', MappingRowLayout)

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
  const rawNodes = props.nodes.map((n) => ({
    id: n.name,
    data: { table: n.name, comment: n.comment || '', label: n.label || '', tableComment: n.tableComment || '' },
    style: { size: nodeSize(n.name, n.comment) }
  }))
  // mapping 模式:坐标在数据里一次算好并写入(G6 对 html 节点的定位只认数据级 style.x/y,
  // 布局结果是异步回填的、不会同步到 html DOM——实测两个节点会叠在同一处)
  const columnsPos = props.mode === 'mapping' ? mappingRowPositions(rawNodes) : null
  const nodes = columnsPos
    ? rawNodes.map((n) => ({ ...n, style: { ...n.style, ...(columnsPos.get(String(n.id)) || {}) } }))
    : rawNodes
  const edges = props.edges.map((r) => {
    relById.set(String(r.id), r)
    return {
      id: String(r.id),
      source: r.oneTable,
      target: r.manyTable,
      data: { oneColumn: r.oneColumn, manyColumn: r.manyColumn },
      // mapping 模式走普通实线(不表达 ER 基数语义,按目标表着色);ER 关系图保持候选/确认/疑似三态样式
      style: props.mode === 'mapping' ? mappingEdgeStyle(r.manyTable) : edgeStyle(r)
    }
  })
  return { nodes, edges }
}

/** 布局配置:关系图=er-dagre-grid(有关系的表 dagre 层次 + 孤儿表底部多行网格);字段映射=mapping-columns(锚点在左、对比表右侧分列等距) */
function layoutOptions() {
  // animation:false——G6 v5 布局动画是在 render() resolve 之后才推移节点的,
  // 开着动画时 fitView 采到的是未收敛位置(zoom 异常偏大、节点散出视口)
  if (props.mode === 'mapping') {
    return { type: 'mapping-row', ranksep: 160, animation: false }
  }
  return { type: 'er-dagre-grid', rankdir: 'LR', nodesep: 24, ranksep: 120, animation: false }
}

// 各对比表连线配色:多目标时按颜色一眼分清线属于哪张对比表(基准表锚点仍是主题蓝)。
// 按 manyTable 首次出现顺序从调色板取色,同表恒同色;橡皮筋阶段已知目标表也直接着色
const TARGET_EDGE_PALETTE = ['#e6a23c', '#67c23a', '#f56c6c', '#8e44ad', '#16a085', '#d35400', '#2d8cf0', '#eb2f96']
const targetEdgeColorMap = new Map()
function targetEdgeColor(manyTable) {
  if (!targetEdgeColorMap.has(manyTable)) {
    targetEdgeColorMap.set(manyTable, TARGET_EDGE_PALETTE[targetEdgeColorMap.size % TARGET_EDGE_PALETTE.length])
  }
  return targetEdgeColorMap.get(manyTable)
}

/** 映射边样式(create-edge 新建边与状态重建边共用同一套):普通实线——字段映射只表达
 *  「左边基准字段 → 右边对比字段」的指向,不带 ER 基数语义,故不要两端竖杠/鸦脚与 1:1 标签;
 *  两端画实心小圆点(G6 内置 circle 箭头,填充色随线色)——线从其他表身上跨过还是真正连到该表,
 *  看端点有没有圆点一眼可辨;
 *  stroke 按目标表着色(manyTable 为空时取主题色);cursor:pointer 透传到边 key 形状,悬停连线鼠标变手指;
 *  increasedLineWidthForHitTesting:1.8px 细线 hover/点击容差太小(偏离 2~3px 就拾不到),
 *  命中宽度放宽到 10px——光标与「点线删除」都好中,视觉线宽不受影响;
 *  聚焦表存在时,不属于该表的连线压暗(strokeOpacity/fillOpacity 双写——小圆点的填充透明度随线一起暗),
 *  凸显 聚焦表 ↔ 基准表 之间的连线 */
function mappingEdgeStyle(manyTable) {
  const c = themeColors()
  const focused = focusTables.value.has(manyTable)
  const dimmed = !!focusTables.value.size && manyTable && !focused
  const opacity = dimmed ? 0.15 : 1
  return {
    stroke: manyTable ? targetEdgeColor(manyTable) : c.primary,
    lineWidth: 1.8,
    strokeOpacity: opacity,
    fillOpacity: opacity,
    // 聚焦表的连线置顶(zIndex 2),压暗线(zIndex 1)垫在下面,交叉处不再混淆
    zIndex: focused ? 2 : 1,
    startArrow: true,
    startArrowType: 'circle',
    startArrowSize: [8, 8],
    endArrow: true,
    endArrowType: 'circle',
    endArrowSize: [8, 8],
    // 圆点锚定偏移:G6 默认 宽/2+线宽(圆心被推出卡片、与边框留缝),显式压到亚像素≈0——
    // 圆心正好落在 fieldEndpoint 算出的字段行边框点上(圆点骑跨边框,一半在内一半在外);
    // 不能写 0:attributes[arrowOffset] || 默认值 对 falsy 会走回默认(G6 base-edge 源码如此)
    startArrowOffset: 0.01,
    endArrowOffset: 0.01,
    // 压暗线禁用交互:光标不变手指(点击/悬停响应已在 onEdgeClick/pickEdgeAt 屏蔽)
    cursor: dimmed ? 'default' : 'pointer',
    increasedLineWidthForHitTesting: 10
  }
}

// hover 的字段行(mapping 模式):直接改行 DOM,绝不走 G6 重绘——整图 setData+draw 代价大,
// hover 高频触发(跨行即刷)会明显卡顿。记录当前高亮行元素与其原始内联样式,换行/离开时还原
let hoverRowEl = null
let hoverRowStyle = ''

/** 行高亮口径(字段行 hover 与连线悬停的两端行高亮共用):主题色淡底 + 圆角,次要色文字提亮 */
function applyRowHighlight(el) {
  const c = themeColors()
  el.style.background = `${c.primary}1f`
  el.style.borderRadius = '3px'
  // 次要色(未连线行)提亮为正文色;已连线行是主题色加粗,保持不动
  if (el.style.color === c.textSecondary) el.style.color = c.text
}

function clearRowHover() {
  if (hoverRowEl?.isConnected) hoverRowEl.setAttribute('style', hoverRowStyle)
  hoverRowEl = null
  hoverRowStyle = ''
}

// ---------- mapping 悬停连线:加粗该线 + 高亮两端字段行,浮层显示「基准字段 → 目标字段」 ----------
// 密集并行线光靠点选分不清哪条;悬停反馈让点选目标与两端去向一目了然(人工审核连线关系)
const edgeTip = ref({ show: false, x: 0, y: 0, text: '' })
let hoverEdgeId = ''
let hoverEdgeRel = null
let hoverEdgeRows = []

/** 字段行出边点 + 出边侧(model 坐标):与 fieldEndpoint 同一套几何——出边侧按对端节点方位定
 *  (节点可拖动,基准表也可能在右侧;写死「基准出右边、对比表出左边」会在拖动后与真实曲线错位,悬停判定全落空),
 *  行不可见返回 null */
function rowPortPoint(table, column, oppositeTable) {
  const g = baseRef.value?.getGraph()
  if (!g) return null
  let pos = null
  let oppPos = null
  try {
    pos = g.getElementPosition(table)
    oppPos = oppositeTable ? g.getElementPosition(oppositeTable) : null
  } catch {
    return null // 节点刚被移除
  }
  let comment = ''
  try {
    comment = g.getNodeData(table)?.data?.comment || ''
  } catch {
    comment = ''
  }
  const [w] = nodeSize(table, comment)
  const idx = visibleFields(table).findIndex((f) => f.name === column)
  if (!pos || idx < 0) return null
  // html 节点 model 点 = DOM 左上角;对端中心在本节点中心左侧 → 出左边,与 fieldEndpoint 同口径(节点同宽 NODE_W)
  const side = oppPos && oppPos[0] + NODE_W / 2 < pos[0] + w / 2 ? -1 : 1
  const x = pos[0] + (side === 1 ? w : 0)
  const y = pos[1] + firstRowTop(table, comment) + idx * ROW_H + ROW_H / 2
  return { point: [x, y], side }
}

/** 边曲线采样点(model 坐标,与 field-cubic 同公式:两端 + 各自出边侧水平 stub 控制点),供悬停距离计算 */
function edgeBezierSamples(oneColumn, manyTable, manyColumn, n = 24) {
  const s = rowPortPoint(props.anchorTable, oneColumn, manyTable)
  const t = rowPortPoint(manyTable, manyColumn, props.anchorTable)
  if (!s || !t) return null
  const stub = edgeStub(s.point, t.point)
  const c0 = [s.point[0] + s.side * stub, s.point[1]]
  const c1 = [t.point[0] + t.side * stub, t.point[1]]
  const pts = []
  for (let i = 0; i <= n; i++) {
    const u = i / n
    const v = 1 - u
    pts.push([
      v * v * v * s.point[0] + 3 * v * v * u * c0[0] + 3 * v * u * u * c1[0] + u * u * u * t.point[0],
      v * v * v * s.point[1] + 3 * v * v * u * c0[1] + 3 * v * u * u * c1[1] + u * u * u * t.point[1]
    ])
  }
  return pts
}

function edgeTipText(rel) {
  const find = (t, c) => (props.columnsMap[t] || []).find((x) => x.name === c)
  const b = find(props.anchorTable, rel.oneColumn)
  const m = find(rel.manyTable, rel.manyColumn)
  return `${b?.comment || rel.oneColumn} → ${m?.comment || rel.manyColumn}`
}

/** 悬停连线:只改边 key 形状的 lineWidth(不动模型,不触发整图重建),两端字段行走高亮口径 */
function applyEdgeHover(rel) {
  const g = baseRef.value?.getGraph()
  if (!g) return
  try {
    // 选中态(删除确认中)的线保持选中样式,悬停不覆盖
    if (String(rel.id) !== props.selectedEdgeId) {
      const key = g.context.element.getElement(String(rel.id))?.getShape('key')
      if (key) key.style.lineWidth = 2.8
    }
  } catch {
    // 边刚被删除/重建,忽略
  }
  const rows = []
  for (const [t, col] of [[props.anchorTable, rel.oneColumn], [rel.manyTable, rel.manyColumn]]) {
    const el = document.querySelector(`[data-rg-node="${CSS.escape(t)}"] [data-rg-column="${CSS.escape(col)}"]`)
    if (el) {
      rows.push({ el, style: el.getAttribute('style') || '' })
      applyRowHighlight(el)
    }
  }
  hoverEdgeRows = rows
}

function clearEdgeHover() {
  const g = baseRef.value?.getGraph()
  if (g && hoverEdgeRel) {
    try {
      // 选中态(删除确认中)的线不还原成普通线宽
      if (String(hoverEdgeRel.id) !== props.selectedEdgeId) {
        const key = g.context.element.getElement(String(hoverEdgeRel.id))?.getShape('key')
        if (key) key.style.lineWidth = 1.8
      }
    } catch {
      // 边已不存在
    }
  }
  for (const r of hoverEdgeRows) {
    if (r.el.isConnected) r.el.setAttribute('style', r.style)
  }
  hoverEdgeRows = []
  hoverEdgeRel = null
  hoverEdgeId = ''
  if (edgeTip.value.show) edgeTip.value = { ...edgeTip.value, show: false }
}

// ---------- mapping 选中连线:点线不直接删,父级弹删除确认期间该线加粗变红(选中态) ----------
// 走模型样式更新(updateEdgeData,style 浅合并不丢箭头配置):端点小圆点(箭头)颜色在 draw 时随线色重推导,
// 直接改 key 形状会只染线不染点;边随 v-model 重建/聚焦重绘后样式被重置,由 onRendered 按 selectedEdgeId 重上
let selectedEdgeIdNow = ''

/** 无动画瞬时绘制:G6 v5 公开 API graph.draw() **忽略入参**(固定 animation:true,见 runtime/graph.js),
 *  要关动画必须走元素控制器 context.element.draw({ animation: false })——
 *  连线增删/变色全部走这里,否则 enter/exit 各播 1s 淡入淡出(主题默认),先闪现再归位 */
async function drawNow(g) {
  await g.context.element.draw({ animation: false })?.finished
}

async function applyEdgeSelected(id) {
  await clearEdgeSelected()
  const g = baseRef.value?.getGraph()
  const rel = id ? relById.get(String(id)) : null
  if (!g || !rel) return
  selectedEdgeIdNow = String(id)
  try {
    // 满浓度 + 置顶:聚焦压暗中的线被选中(删除确认)也要一眼看清;关动画瞬时到位(连线操作一律不播动画)
    g.updateEdgeData([{ id: selectedEdgeIdNow, style: { stroke: themeColors().danger, lineWidth: 3.2, strokeOpacity: 1, fillOpacity: 1, zIndex: 3 } }])
    await drawNow(g)
  } catch {
    selectedEdgeIdNow = '' // 边已删除/重建
  }
}

async function clearEdgeSelected() {
  const g = baseRef.value?.getGraph()
  const id = selectedEdgeIdNow
  selectedEdgeIdNow = ''
  if (!g || !id) return
  const rel = relById.get(id)
  if (!rel) return
  try {
    g.updateEdgeData([{ id, style: mappingEdgeStyle(rel.manyTable) }])
    await drawNow(g)
  } catch {
    // 边已删除/重建,无需还原
  }
}

watch(() => props.selectedEdgeId, (id) => {
  if (id) applyEdgeSelected(id)
  else clearEdgeSelected()
})

/** 点到线段距离(悬停判定用:采样点之间的弧段不能漏——长曲线 24 个采样点相邻间距可达数十 px,
 *  只算到点的距离会在点与点之间出现判定盲区) */
function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax
  const dy = by - ay
  const len2 = dx * dx + dy * dy
  let t = len2 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0
  t = Math.max(0, Math.min(1, t))
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** 聚焦压暗的连线(与聚焦集合无关)是否禁用交互:悬停浮层/加粗、点击删除确认、手指光标一律屏蔽,
 *  直到取消聚焦(与压暗表节点 pointerEvents:none 同口径) */
function isDimmedEdge(rel) {
  return props.mode === 'mapping' && !!focusTables.value.size && !focusTables.value.has(rel?.manyTable)
}

/** 指针下最近的连线(按曲线采样折线的点到线段距离,阈值与命中宽度同量级,除以 zoom);
 *  聚焦态只从聚焦表的连线里挑——压暗线禁用交互,悬停不响应 */
function pickEdgeAt(clientX, clientY) {
  const g = baseRef.value?.getGraph()
  if (!g) return null
  const [mx, my] = g.getCanvasByClient([clientX, clientY])
  let best = null
  let bestDist = 6 / g.getZoom()
  for (const e of props.edges) {
    if (isDimmedEdge(e)) continue
    const pts = edgeBezierSamples(e.oneColumn, e.manyTable, e.manyColumn)
    if (!pts) continue
    let d = Infinity
    for (let i = 1; i < pts.length; i++) {
      const dd = distToSegment(mx, my, pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
      if (dd < d) d = dd
    }
    if (d < bestDist) {
      bestDist = d
      best = e
    }
  }
  return best
}

/** 容器层 pointermove/mousemove 委托(捕获):只有「换到另一个行元素」才动一次手,O(1) 不影响滑动流畅度 */
function onContainerPointerMove(ev) {
  if (props.mode !== 'mapping') return
  const colEl = ev.target?.closest?.('[data-rg-column]') || null
  if (colEl !== hoverRowEl) {
    clearRowHover()
    if (colEl) {
      hoverRowEl = colEl
      hoverRowStyle = colEl.getAttribute('style') || ''
      applyRowHighlight(colEl)
    }
  }
  if (colEl || createEdgePending()) {
    // 悬停字段行(连线语义)或橡皮筋进行中:不抢连线悬停
    if (hoverEdgeId || edgeTip.value.show) clearEdgeHover()
    return
  }
  const best = pickEdgeAt(ev.clientX, ev.clientY)
  const id = best ? String(best.id) : ''
  if (id !== hoverEdgeId) {
    clearEdgeHover()
    hoverEdgeId = id
    hoverEdgeRel = best
    if (best) applyEdgeHover(best)
  }
  if (best) {
    edgeTip.value = { show: true, x: ev.clientX + 12, y: ev.clientY + 14, text: edgeTipText(best) }
  } else if (edgeTip.value.show) {
    edgeTip.value = { ...edgeTip.value, show: false }
  }
}

/** create-edge 交互进行中(橡皮筋辅助节点还在模型里):此时不能再 setData 重绘,否则辅助节点被清掉、行为下次 pointermove 抛错 */
function createEdgePending() {
  const g = baseRef.value?.getGraph()
  if (!g) return false
  try {
    return !!g.getNodeData('g6-create-edge-assist-node-id')
  } catch {
    return false
  }
}

/** mapping 模式就地重绘数据:与 buildData 同构,但剥离节点 style.x/y——
 *  setData 对 style 浅合并,新数据不带 x/y 即保留模型当前位置,拖动后的手动位置不被自动布局坐标盖回;
 *  自动布局坐标只在整体重建(首次挂载的 graphData / 工具栏「重新排列」relayoutMapping)时下发 */
function buildDataKeepPositions() {
  const d = buildData()
  d.nodes = d.nodes.map((n) => {
    if (!n.style) return n
    const { x, y, ...rest } = n.style
    return { ...n, style: rest }
  })
  return d
}

/** 原地重绘(不跑布局、不动视口):create-edge 交互进行中则跳过,避免打断连线;
 *  mapping 模式走 buildDataKeepPositions——重绘只刷新渲染口径,不把拖动的节点拉回自动布局位 */
function repaintInPlace() {
  if (createEdgePending()) return
  baseRef.value?.repaint(props.mode === 'mapping' ? buildDataKeepPositions() : buildData())
}

/** mapping 模式工具栏「重绘」接管为「重新排列」(底座 customRedraw):整体重建
 *  (setData 带回自动布局坐标 + render),拖动后的手动位置全部还原;
 *  视口定位走 fitView 全景而非「居中+100%」——字段全展开的卡片常比视口高,居中到 100% 只能看见
 *  所有卡片的中段一截(顶对齐的卡片顶部全在视口外),看起来像没重排;
 *  连线橡皮筋进行中不响应(setData 会清掉辅助元素、打断连线) */
async function relayoutMapping() {
  if (createEdgePending()) return
  await baseRef.value?.refresh(buildData(), false) // 视口定位由下面的 fitView 接管,不走默认 settle
  await baseRef.value?.fitView()
}

/**
 * mapping 模式连线就地同步:按 id 差集增删边 + draw,不走底座整体重建(重建会闪屏、视口跳动)。
 * 边不进 graphData(见 graphData 注释),初次由 onRendered 补挂,之后 props.edges 一变就在这里落地;
 * create-edge 的橡皮筋临时边(map-tmp:*)不在 props.edges 里,差集时自然被收敛移除(与旧重建口径一致)
 */
async function syncEdgesInPlace() {
  if (props.mode !== 'mapping') return
  const g = baseRef.value?.getGraph()
  if (!g) return
  if (createEdgePending()) return // 橡皮筋进行中不动模型,onFinish 后 props.edges 更新会再来一次
  // 点边回查表随连线状态重建(即使本次无边差集,relById 也要与最新 edges 对齐)
  relById = new Map()
  const want = new Map()
  for (const r of props.edges) {
    const id = String(r.id)
    relById.set(id, r)
    want.set(id, {
      id,
      source: r.oneTable,
      target: r.manyTable,
      data: { oneColumn: r.oneColumn, manyColumn: r.manyColumn },
      style: mappingEdgeStyle(r.manyTable)
    })
  }
  let cur = []
  try {
    cur = g.getEdgeData().map((e) => String(e.id))
  } catch {
    return
  }
  const curSet = new Set(cur)
  const removeIds = cur.filter((id) => !want.has(id))
  const addList = [...want.values()].filter((e) => !curSet.has(e.id))
  if (!removeIds.length && !addList.length) return
  if (removeIds.length) g.removeEdgeData(removeIds)
  if (addList.length) g.addEdgeData(addList)
  // 连线增删一帧到位,不播进入/退出动画
  await drawNow(g)
}

// mapping 模式:连线增删就地应用(整图不重建,视口/节点位置不动、不闪屏)
watch(() => props.edges, () => syncEdgesInPlace(), { deep: true })

// 字段行点击日志(最近几次):create-edge 行为只给节点 id,字段名要从容器点击委托里带上来的记录取;
// 只认「最后两次点击」,避免点到节点标题/空白后拿旧记录错连
let fieldClickLog = []
// 本次连线的起始字段(橡皮筋起点锚定用):pointerdown 落在字段行且橡皮筋未激活时记录——
// 不能用「最后一次字段点击」充当:第二次点下(pointerdown)那一刻日志尾部已变成对侧表,
// 锚点判定落空会退化成边框交点跟随鼠标滑动(起点跟着鼠标跑)
let pendingStartField = null
// create-edge 临时边序号:临时边 id 必须唯一——重连「已映射过的基准字段」时,同语义的正式边
// (map:表:字段)还留在模型里,同 id 会让 G6 addEdgeData 抛 Edge already exists,create-edge 流程
// 中断(辅助边残留、之后点击全被吞),故临时边用独立 id,正式边由父级按映射重建时落
let tmpEdgeSeq = 0
function noteFieldClick(table, column) {
  fieldClickLog.push({ table, column })
  if (fieldClickLog.length > 4) fieldClickLog.shift()
}

/**
 * create-edge(trigger=click)新建边回调:两端都必须是「刚点过的字段行」,且必须一侧是基准表、另一侧是对比表;
 * 返回带 oneColumn/manyColumn 的边数据(field-cubic 据此把端点对齐到字段行),不合法返回 undefined 取消创建。
 * 两个点击方向都合法(基准→对比 / 对比→基准),返回的边必须归一化为 source=基准表、target=对比表——
 * 否则渲染拿基准字段(oneColumn)去对比表里找行,找不到退化为边框交点,在两个节点中间画一条线
 */
function onCreateEdge(edge) {
  const last = fieldClickLog.slice(-2)
  const source = last.find((x) => x.table === edge.source)
  const target = last.find((x) => x.table === edge.target)
  if (!source || !target) return undefined
  const srcIsBase = edge.source === props.anchorTable
  const dstIsBase = edge.target === props.anchorTable
  if (srcIsBase === dstIsBase) return undefined // 基准↔基准 / 对比表↔对比表 不连
  const oneColumn = srcIsBase ? source.column : target.column
  const manyColumn = srcIsBase ? target.column : source.column
  const manyTable = srcIsBase ? edge.target : edge.source
  return {
    ...edge,
    id: `map-tmp:${manyTable}:${oneColumn}:${++tmpEdgeSeq}`,
    // 方向归一化:one 侧恒为基准表,与正式边(map:表:字段)同口径,端点对齐字段行才找得到行
    source: srcIsBase ? edge.source : edge.target,
    target: srcIsBase ? edge.target : edge.source,
    data: { oneColumn, manyColumn },
    style: mappingEdgeStyle(manyTable)
  }
}

/** 新建边完成:交给调用方落状态(父级更新映射后由 watch 就地同步:正式边以 map:表:字段 落模型,临时边被收敛移除);
 *  manyTable 必须取非基准那一侧(onCreateEdge 已把 source 归一化为基准表)——直接拿 edge.target 充当,
 *  先点对比表时会得到基准表 id,父级查不到目标索引把这次连线静默丢弃(线没显示) */
function onFinishEdge(edge) {
  emit('mapping-connect', {
    oneTable: edge.source,
    manyTable: edge.source === props.anchorTable ? edge.target : edge.source,
    oneColumn: edge.data?.oneColumn,
    manyColumn: edge.data?.manyColumn
  })
  // 兜底:若 watch 触发时橡皮筋辅助节点还没收掉(syncEdgesInPlace 被跳过),下一拍再补一次(差集幂等)
  nextTick(() => syncEdgesInPlace())
}

/** 平行边(同一对表多条关系)处理:仅「仅表名」档位需要按曲率分开——
 *  有字段行的档位下边端点已按字段行天然分开,且 bundle 会把自定义边改写成 quadratic 丢掉字段对齐 */
function parallelEdgeTransforms() {
  if (props.mode === 'mapping') return []
  return props.level === 'name' ? [{ type: 'process-parallel-edges', mode: 'bundle', distance: 24 }] : []
}

// 底座输入:数据/配置均为 computed,props(含 level)变化 → data 变化触发底座整体重建
// (底座 refresh 每次重建重放 transforms,level 切换后平行边处理随之更新;重建后按 fit 口径重新定位);
// 触碰 themeState.dark:亮/暗主题切换时重算(节点/边颜色取自主题变量),底座整体重建换色
//
// mapping 模式例外:连线增删是高频操作,整体重建会闪屏且视口跳动——边不进 graphData,
// 结构输入(节点集合/尺寸/主题)没变时沿用上次对象引用(底座 watch 不触发),
// 连线由 syncEdgesInPlace 按 id 差集就地增删(addEdgeData/removeEdgeData + draw)
let mappingDataCache = null
const graphData = computed(() => {
  const d = buildData()
  if (props.mode !== 'mapping') {
    void themeState.dark // ER 模式:亮/暗主题切换时重算(节点/边颜色取自主题变量),底座整体重建换色
    return d
  }
  const key = `${themeState.dark}|${JSON.stringify(d.nodes)}`
  if (mappingDataCache?.key === key) return mappingDataCache.data
  mappingDataCache = { key, data: { nodes: d.nodes, edges: [] } }
  return mappingDataCache.data
})
const graphOptions = computed(() => ({
  node: {
    type: 'html',
    style: {
      size: (d) => d.style?.size || [NODE_W, 40],
      innerHTML: (d) => renderNodeHtml(d),
      // 聚焦压暗的表整体禁用交互(G6 html 节点级 pointerEvents,作用于节点 DOM 包装元素):
      // 字段行/标题点击、悬停、拖动全部穿透到画布——点压暗表等于点空白(取消聚焦)
      pointerEvents: (d) => (isDimmedTable(d.data?.table) ? 'none' : 'auto')
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
    // 节点可拖拽(底座内置 drag-canvas 拖画布);Shift+拖 让位给底座框选(selectable 内置 brush-select,
    // 否则按住 Shift 拖节点会一边拖节点一边画框选)
    { type: 'drag-element', enable: (e) => !e.shiftKey },
    // mapping 模式:用 G6 内置 create-edge(trigger=click)点两端字段行连线,自带橡皮筋辅助边;
    // 字段名由容器点击委托记录(见 onCreateEdge),两端不合法(同侧表/未点字段行)的新建请求直接取消;
    // enable 只放行字段行上的点击(经 nativeEvent 命中 [data-rg-column])——标题区点击是「聚焦连线」语义,
    // 不起橡皮筋;橡皮筋的取消(canvas/edge click)在 create-edge 内部不经 enable 判定,不受影响
    ...(props.mode === 'mapping'
      ? [{
          type: 'create-edge',
          trigger: 'click',
          enable: (e) => !!e?.nativeEvent?.target?.closest?.('[data-rg-column]'),
          style: mappingEdgeStyle(),
          onCreate: onCreateEdge,
          onFinish: onFinishEdge
        }]
      : [])
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
watch(() => props.fieldNameMode, () => repaintInPlace())
// 基准字段高亮集合变化(向导第二步改勾选后回到本步):原地重绘即可
watch(() => props.highlightColumns, () => repaintInPlace())

// ---------- 事件口径:单击节点选中(单击表名除外,见 onContainerClick)/ 点边回查原始关系 / 双击表名跳字段明细 ----------
function onNodeClick(id) {
  // 单击表名不开面板(双击表名才跳字段明细):表名单击已打时间戳,这里忽略
  if (Date.now() - lastTitleClick < 250) return
  emit('node-click', id)
}

function onEdgeClick(id) {
  const rel = relById.get(id)
  // 聚焦态压暗线(与聚焦表无关)禁用交互:不进删除确认选中态
  if (!rel || isDimmedEdge(rel)) return
  emit('edge-click', rel)
}

/** 点画布空白(底座 canvas-click):清空选中(框选松手后补发的那次 click 已由底座吞掉,不会误清,见 BaseGraphCanvas);
 *  mapping 模式同时取消「聚焦表」——本次点击若正在收掉连线橡皮筋,辅助元素要等事件派发完才被 create-edge 清掉,
 *  故延一拍再重绘(不在连线中也无妨,重绘本就异步);同时转发给父级——mapping 模式用来收起连线删除确认条 */
function onCanvasClick() {
  clearSelection()
  if (focusTables.value.size) {
    focusTables.value = new Set()
    // 本次点击若正在收掉连线橡皮筋,辅助元素要等事件派发完才被 create-edge 清掉,延一拍再重绘
    setTimeout(() => repaintInPlace(), 0)
  }
  emit('canvas-click')
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

/** 容器层 click 委托(捕获阶段):「+N 个字段」展开收起 / 表名单击抑制时间戳 / 点节点本体单选。
 *  单选经底座 setSelection 接入统一选中状态;Shift+点击增减由底座 click-select 处理,这里不重复;
 *  拖拽节点后的残留 click(位移>4px)不响应 */
function onContainerClick(ev) {
  // mapping 模式:点字段行 = 派发 field-click(连线本身交给 create-edge 行为);字段名在 pointerdown 记录;
  // 点标题区(表名块) = 聚焦该表 ↔ 基准表的连线(其余表与连线变暗;基准表不聚焦,点击 = 取消聚焦)
  if (props.mode === 'mapping') {
    const moved = downPos && (Math.abs(ev.clientX - downPos[0]) + Math.abs(ev.clientY - downPos[1]) > 4)
    const colEl = ev.target?.closest?.('[data-rg-column]')
    if (colEl) {
      if (!moved) {
        const table = colEl.getAttribute('data-rg-column-table')
        const column = colEl.getAttribute('data-rg-column')
        if (table && column) emit('field-click', { table, column })
      }
      return
    }
    const titleEl = ev.target?.closest?.('.rg-node-title')
    if (!titleEl || moved) return
    // 连线橡皮筋进行中不响应:聚焦重绘会把辅助边清掉、打断连线
    if (createEdgePending()) return
    // Shift+点击交给底座 click-select 增减选中(聚焦集合随 selection-change 同步),这里不做单选替换
    if (ev.shiftKey) return
    const t = titleEl.getAttribute('data-rg-table')
    applyFocus(!t || t === props.anchorTable ? '' : t)
    return
  }
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
  // 点节点本体单选(标题区也算):经底座 setSelection 接入统一选中状态;Shift+点击由底座 click-select 增减,这里跳过;
  // 拖拽节点后的残留 click(位移>4px)不响应
  const nodeEl = ev.target?.closest?.('[data-rg-node]')
  if (!nodeEl) return
  const moved = downPos && (Math.abs(ev.clientX - downPos[0]) + Math.abs(ev.clientY - downPos[1]) > 4)
  if (moved) return
  const table = nodeEl.getAttribute('data-rg-node')
  if (table && !ev.shiftKey) baseRef.value?.setSelection([table])
}

/** 记录指针按下坐标(拖拽误触过滤,见 onContainerClick);
 *  同时打表名/「+N 个字段」行的单击抑制时间戳——G6 html 节点的 node:click 由 pointerup 合成派发,
 *  早于 DOM click(实测同帧但先到),时间戳在 click 阶段打就来不及了 */
function onContainerPointerdown(ev) {
  downPos = [ev.clientX, ev.clientY]
  if (ev.target?.closest?.('.rg-node-title, .rg-more-row')) lastTitleClick = Date.now()
  // mapping 模式:字段名在 pointerdown 就记下来——G6 的 node:click 由 pointerup 合成派发(早于 DOM click),
  // create-edge 的 onCreate 回调发生在那一刻,晚于 click 再记就来不及了;
  // 橡皮筋未激活时的这次按下同时记为「本次连线起点」(pendingStartField,锚定辅助边 source 端用;
  // 激活中的按下是终点,不能覆盖起点)
  if (props.mode === 'mapping') {
    const colEl = ev.target?.closest?.('[data-rg-column]')
    if (colEl) {
      const table = colEl.getAttribute('data-rg-column-table')
      const column = colEl.getAttribute('data-rg-column')
      if (table && column) {
        if (!createEdgePending()) pendingStartField = { table, column }
        noteFieldClick(table, column)
      }
    }
  }
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

/** 双击表名跳字段明细:HTML 节点内 .rg-node-title 的捕获阶段委托(mapping 模式不跳转) */
function onContainerDblclick(ev) {
  if (props.mode === 'mapping') return
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
  // pointermove + mousemove 都挂:真实鼠标两者都会来(handler 内部按行元素去重,重复调用无副作用),
  // 某些环境/自动化只投递 mousemove,挂一个会漏掉 hover 高亮;pointerleave 在鼠标划出画布时清掉行高亮
  el.addEventListener('pointermove', onContainerPointerMove, true)
  el.addEventListener('mousemove', onContainerPointerMove, true)
  el.addEventListener('pointerleave', clearRowHover)
  el.addEventListener('mouseleave', clearRowHover)
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
  clearEdgeHover()
  clearRowHover()
  const el = baseRef.value?.getContainer()
  el?.removeEventListener('click', onContainerClick, true)
  el?.removeEventListener('dblclick', onContainerDblclick, true)
  el?.removeEventListener('pointerdown', onContainerPointerdown, true)
  el?.removeEventListener('pointermove', onContainerPointerMove, true)
  el?.removeEventListener('mousemove', onContainerPointerMove, true)
  el?.removeEventListener('pointerleave', clearRowHover)
  el?.removeEventListener('mouseleave', clearRowHover)
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
/* 悬停连线浮层:两端字段名(注释优先);pointer-events:none 不挡点击,跟随鼠标偏移 12/14 */
.rg-edgetip {
  position: fixed;
  z-index: 20;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--el-text-color-primary);
  background: color-mix(in srgb, var(--el-bg-color) 92%, transparent);
  border: 1px solid var(--el-border-color-darker);
  border-radius: 4px;
  box-shadow: var(--el-box-shadow-light);
  pointer-events: none;
  white-space: nowrap;
}
.rg-selbar-text {
  font-size: 12px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
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
