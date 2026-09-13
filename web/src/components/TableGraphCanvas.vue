<template>
  <!-- 图谱画布(G6 圆形节点星型图):通用画布底座 BaseGraphCanvas 承载交互(滚轮平移/Ctrl+滚轮与触摸板捏合缩放/
       Alt+滚轮水平平移/双击坐标反查补发)、顶部工具栏(默认工具:重绘/1:1/适应画布 + toolbar 插槽业务工具[标记/颜色筛选],
       右侧缩放控制条[−/+、比例输入、全屏])、右下角鸟瞰图、视口定位(内容居中+100%)与首帧隐藏;
       本组件只负责星型图专属部分:静态坐标、圆形节点样式(颜色/光晕/选中态)、边样式。
       容器需显式高度(由父级布局保证) -->
  <BaseGraphCanvas ref="baseRef" :data="graphData" :options="graphOptions" fit="center" :default-zoom="1"
    :refit-on-data-change="false" :animated="layout === 'force'" @node-click="onNodeClick"
    @node-dblclick="(id) => emit('node-open', id)" @canvas-click="emit('canvas-click')" @rendered="onRendered"
    selectable @selection-change="(ids) => emit('lasso-select', ids)">
    <!-- 业务工具透传:标记/颜色筛选等由调用方按需给 -->
    <template #toolbar>
      <slot name="toolbar" />
    </template>
  </BaseGraphCanvas>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import BaseGraphCanvas from './canvas/BaseGraphCanvas.vue'
import { themeState } from '../stores/theme'

// 表关系图谱画布(G6 v5 圆形节点星型图,与 RelationGraphCanvas 的乌鸦脚 ER 图并列的另一种可视化;
// 通用能力见底座 BaseGraphCanvas):
//  节点 = 表(圆形,锚点表居中、邻表按表名排序环绕排布(或按 levels 分层;levels+parentOf 同传时走径向分层,
//    一圈一个层级、子节点聚在父节点外侧同侧扇区;layout='force' 时不预设坐标,由 d3-force 力导向自动编排),
//    节点多时分多个同心圈;颜色由父级按「自定义色 > 首个标记色 > 主题色」解析后经
//    colors prop 传入;只显示表名——中文注释优先、无注释回退英文表名,超长截断);
//  边 = 关系(候选虚线灰色 / 确认实线主题色 / 疑似多对多红色,连线上不标基数,关系详情去 ER 页签看);
//  选中态:选中节点 = 正文色深描边 + 一圈与节点同色的浅色光晕(halo;haloZIndex=-1 画在节点下方,
//    加粗只向外溢出、不遮挡描边),颜色随节点颜色走;筛选高亮:highlight 非空时命中的节点保持原色
//    并加同类光晕(略细),未命中的节点/关联边大幅降低透明度;
//  高亮/选中态与渲染口径样式(连线粗细/文本透明度)不进结构依赖——变化时走 repaint 原地刷样式
//  (setData+draw,不跑布局、不动力导仿真);节点填充色同走 repaint 刷,但结构数据里会带上当前值快照
//  (非响应式的 structColors,不登记依赖),保证筛选裁剪重建的首帧就是最终色、不出现"先主题色后过渡";
//  结构重建(节点/边集合、尺寸、力导参数)才经底座整体 render,重建后借 rendered 事件补刷覆盖层;
//  静态模式下坐标算完统一过一遍碰撞消解(resolveOverlaps):重叠节点先在原圈向两侧错开角度,
//  整圈放不下再逐档外扩半径(延长连接线)直到不重叠;力导模式由 collide 力承担防重叠
// 渲染口径样式默认值:连线粗细基准(px)/文本透明度;props 缺省与结构数据(见 graphData 的 STRUCT_STYLE)共用
const EDGE_WIDTH_DEFAULT = 1.4
const LABEL_OPACITY_DEFAULT = 1

const props = defineProps({
  // 图节点:[{ name, comment }]
  nodes: { type: Array, default: () => [] },
  // 图边:TableRelation 列表(id/oneTable/oneColumn/manyTable/manyColumn/cardinality/status/...);
  // 力导模式按边 id 回查 distance 字段做边长分档(语义 = 可见连线长度/两圆边缘间距,缺省 60;
  // 注意 distance 不能靠 G6 图数据传进布局回调——模型转换会剥掉自定义字段)
  edges: { type: Array, default: () => [] },
  // 锚点表名(星型图中心节点)
  anchorTable: { type: String, default: '' },
  // 各节点填充色:{ 表名: '#hex' }(父级已按 自定义 > 标记 > 默认 解析)。
  // 改色属纯渲染口径:变化走 applyOverlay 的 repaint 原地刷填充色,不重建不重跑仿真;
  // 结构数据(重建)里会带上当前值快照,保证重建首帧即最终色、不出现"先主题色后过渡"
  colors: { type: Object, default: () => ({}) },
  // 节点直径分档:{ 节点名: px };缺省回退 锚点=ANCHOR_SIZE / 其他=NODE_SIZE。
  // 知识图谱口径(目录大节点下挂小节点表)由父级按层级给:锚点目录 > 子目录 > 挂载表 > 关系表
  sizes: { type: Object, default: null },
  // 节点层级:{ 节点名: 圈号(1 起,锚点为 0 居中) };非空时同层节点占同一圈、层数即圈序
  // (某层超单圈容量自动顺延占圈,后续层整体后移);缺层级的节点排到最后;不传则回退全部按名排序混排
  levels: { type: Object, default: null },
  // 节点父子关系:{ 节点名: 父节点名 };与 levels 同传时启用径向分层布局:
  // 叶子节点等分整圈角度,内部节点取子树扇区中心角——子节点聚在父节点外侧同侧,而非本层各自均布整圈
  parentOf: { type: Object, default: null },
  // 高亮表名数组;null=无筛选(全部正常显示),非 null 时未命中节点/边降透明度
  // (对象管理图谱用它做选中态高亮;表详情「图谱」页签的标记/颜色筛选改为父级按命中结果裁剪节点集合后重建,不走本 prop)
  highlight: { type: Array, default: null },
  // 当前选中节点表名(父级点击面板打开的节点),画布上加深描边呈现选中态
  selected: { type: String, default: '' },
  // 多选命中的表名数组(Shift 框选/点选):与 selected 单选并存,任一命中即呈现同一选中态(深描边 + 同色光晕);
  // 属渲染口径(不进结构数据),变化走 applyOverlay 的 repaint 原地刷
  selectedList: { type: Array, default: () => [] },
  // 连线粗细基准(px,候选/未确认边的线宽;确认边在此基础上 +0.4 加粗)。
  // 纯渲染口径(不进布局输入):实时调节走 applyOverlay 的 repaint 原地刷,不重建不重跑仿真
  edgeWidth: { type: Number, default: EDGE_WIDTH_DEFAULT },
  // 节点标签文本透明度(0~1;筛选未命中态在此基础上再打 0.25 折)。纯渲染口径,同 edgeWidth 走 repaint
  labelOpacity: { type: Number, default: LABEL_OPACITY_DEFAULT },
  // 向心力强度(d3 forceX/forceY 的 strength,0~1,把节点向画布中心拉;0.1 与 d3 默认一致)。布局输入,调节即重排
  centerStrength: { type: Number, default: 0.1 },
  // 节点排斥力倍率(乘在 manyBody 分档[叶子 -220 / 内部 -80]上)。布局输入,调节即重排
  chargeStrength: { type: Number, default: 1 },
  // 相连节点吸引力倍率(乘在 link strength 分档[短边 0.8 / 长边 0.15]上)。布局输入,调节即重排
  linkStrength: { type: Number, default: 1 },
  // 布局方式:static=静态星型/径向(坐标算死,关动画一次渲染到位);force=d3-force 力导向
  // (不预设坐标自动编排,边长按边上的 distance 分档、长边弱短边强、叶子斥力更大、collide 防重叠)
  layout: { type: String, default: 'static' }
})

// node-click:单击节点圆形本体(传表名,父级开颜色设置面板);
// node-label-click:单击节点标签文本(与节点点击拆开捕捉——G6 节点是 DisplayObject 组,文本是组内
//   label 子图形,事件 e.target 恒为节点元素,实际命中图形在 e.originalTarget,沿其祖先链判 className 即可区分);
// node-open:双击节点(传表名,跳字段明细);canvas-click:点击画布空白(父级可用来取消选中/关面板);
// lasso-select:框选/多选集合变化(传选中的表名数组全量,来自底座 selectable 的 selection-change;事件名保持 lasso-select 不变,父级不用改)
const emit = defineEmits(['node-click', 'node-label-click', 'node-open', 'canvas-click', 'lasso-select'])

/** 事件命中的子图形是否属于节点内指定 className(key/label/halo...)的图形:
 *  从 e.originalTarget(实际命中的叶子图形,如 label 组内的 text)沿祖先链找到 e.target(节点元素)为止 */
function hitShape(e, className) {
  let s = e?.originalTarget
  const stop = e?.target
  while (s && s !== stop) {
    if (s.className === className) return true
    s = s.parentElement
  }
  return false
}

/** 单击分发:命中标签文本 → 独立的 node-label-click(不触发节点点击);命中圆形本体/光晕 → node-click;
 *  Shift+点击只进底座框选/多选(selectable),不分发——否则父级会开单表面板并把刚多选的集合清空 */
function onNodeClick(id, _data, e) {
  if (e?.shiftKey) return
  if (hitShape(e, 'label')) {
    emit('node-label-click', id)
    return
  }
  emit('node-click', id)
}

const baseRef = ref(null)

/** 主题色:跟随 Element Plus CSS 变量(亮/暗主题自适应),取不到用兜底值 */
function themeColors() {
  const cs = getComputedStyle(document.documentElement)
  const get = (k, fb) => cs.getPropertyValue(k).trim() || fb
  return {
    primary: get('--el-color-primary', '#409eff'),
    danger: get('--el-color-danger', '#f56c6c'),
    borderDarker: get('--el-border-color-darker', '#cdd0d6'),
    text: get('--el-text-color-primary', '#303133'),
    textSecondary: get('--el-text-color-secondary', '#909399')
  }
}

// 节点直径:锚点(中心节点)明显最大,普通节点次之;sizes prop 可按节点名单独分档(优先级最高)
const ANCHOR_SIZE = 72
const NODE_SIZE = 42
/** 节点直径解析:sizes 分档 > 锚点/普通默认 */
const sizeOf = (name) => props.sizes?.[name] ?? (name === props.anchorTable ? ANCHOR_SIZE : NODE_SIZE)

/** 节点显示名:中文注释优先,无注释回退英文表名;显示全名(超长自动换行,labelWordWrap 控制) */
function displayName(n) {
  return n.comment || n.name
}

// 星型布局常量:首圈半径/圈间距/每节点最小弧距(标签在节点下方且会自动换行,弧距过小相邻标签会轻微叠字;
// 取值偏紧凑,优先把连线缩短——节点多了宁可多开一圈也不把单圈撑大)
const RING_BASE_R = 160
const RING_GAP = 110
const ARC_SPACING = 95

/** 静态星型布局:锚点表居中(0,0)。三种口径:
 *   1. levels+parentOf 同传 → 径向分层(一圈一个层级,子节点聚在父节点外侧同侧扇区);
 *   2. 仅 levels → 同层节点占同一圈,层内按名排序均布(超单圈容量顺延占圈,后续层后移);
 *   3. 都不传 → 全部邻表按名排序,由内向外逐圈填满。
 *  每圈弧距不小于 ARC_SPACING 的约束只在 2/3 口径生效;径向分层靠叶子等分角度天然摊开 */
function nodePositions() {
  const pos = new Map()
  const neighbors = props.nodes.filter((n) => n.name !== props.anchorTable)
  const anchor = props.nodes.find((n) => n.name === props.anchorTable)
  if (anchor) pos.set(anchor.name, [0, 0])
  if (!neighbors.length) return pos
  if (props.levels && props.parentOf) {
    placeRadial(pos, neighbors)
    return pos
  }
  /** 把 list 从第 ring 圈起逐圈排布,返回排完后的下一圈号 */
  const placeRings = (list, ring) => {
    let idx = 0
    while (idx < list.length) {
      const r = RING_BASE_R + ring * RING_GAP
      // 本圈容量:周长按最小弧距切分;剩得少时全放本圈(半径兜底 RING_BASE_R 防小圈子挤作一团)
      const capacity = Math.max(1, Math.floor((2 * Math.PI * r) / ARC_SPACING))
      const count = Math.min(capacity, list.length - idx)
      const offset = ring % 2 === 1 ? Math.PI / count : 0 // 奇数圈错开半格,相邻圈节点不叠在同一条半径上
      for (let i = 0; i < count; i++) {
        const angle = -Math.PI / 2 + offset + (i * 2 * Math.PI) / count // 从正上方起顺时针排布
        pos.set(list[idx + i].name, [r * Math.cos(angle), r * Math.sin(angle)])
      }
      idx += count
      ring++
    }
    return ring
  }
  if (props.levels) {
    const groups = new Map()
    for (const nb of neighbors) {
      const lv = props.levels[nb.name] ?? Number.MAX_SAFE_INTEGER // 缺层级的排最后
      if (!groups.has(lv)) groups.set(lv, [])
      groups.get(lv).push(nb)
    }
    let ring = 0
    for (const lv of [...groups.keys()].sort((a, b) => a - b)) {
      ring = placeRings(groups.get(lv).sort((a, b) => a.name.localeCompare(b.name)), ring)
    }
    return pos
  }
  const sorted = [...neighbors].sort((a, b) => a.name.localeCompare(b.name))
  placeRings(sorted, 0)
  return pos
}

/** 径向分层:parentOf 建父子树(挂不到有效父级的挂锚点下),叶子等分整圈角度(从正上方起顺时针),
 *  父节点扇区大小 = 其子树叶子数占比,父节点取扇区中心角——子节点排在父节点角度正外侧;
 *  半径按层级(圈号 = levels,缺省沿父链 +1) */
function placeRadial(pos, neighbors) {
  const names = new Set(neighbors.map((nb) => nb.name))
  const childrenOf = new Map()
  for (const nb of neighbors) {
    let p = props.parentOf[nb.name]
    if (p == null || (p !== props.anchorTable && !names.has(p))) p = props.anchorTable
    if (!childrenOf.has(p)) childrenOf.set(p, [])
    childrenOf.get(p).push(nb.name)
  }
  for (const list of childrenOf.values()) list.sort((a, b) => a.localeCompare(b))
  // 层级(圈号):levels 优先,缺省沿父链 +1(带 memo)
  const lvMemo = new Map()
  const levelOf = (name) => {
    if (lvMemo.has(name)) return lvMemo.get(name)
    let lv = props.levels[name]
    if (lv == null) {
      const p = props.parentOf[name]
      lv = (p && names.has(p) ? levelOf(p) : 0) + 1
    }
    lvMemo.set(name, lv)
    return lv
  }
  // 权重 = 子树叶子数(无子=1):叶子等分角度,子孙多的父级占更大扇区(带环保护)
  const wMemo = new Map()
  const weightOf = (name, stack = []) => {
    if (wMemo.has(name)) return wMemo.get(name)
    if (stack.includes(name)) return 1
    const kids = childrenOf.get(name) || []
    const w = kids.length ? kids.reduce((s, k) => s + weightOf(k, [...stack, name]), 0) : 1
    wMemo.set(name, w)
    return w
  }
  const assign = (name, a0, a1) => {
    const kids = childrenOf.get(name) || []
    if (!kids.length) return
    const total = kids.reduce((s, k) => s + weightOf(k), 0)
    let a = a0
    for (const k of kids) {
      const span = ((a1 - a0) * weightOf(k)) / total
      const mid = a + span / 2
      const r = RING_BASE_R + (levelOf(k) - 1) * RING_GAP
      pos.set(k, [r * Math.cos(mid), r * Math.sin(mid)])
      assign(k, a, a + span)
      a += span
    }
  }
  assign(props.anchorTable, -Math.PI / 2, Math.PI * 1.5) // 整圈
}

/** 边样式:候选虚线灰色 / 确认实线主题色 / 疑似多对多红色;连线不标基数(关系详情在 ER 页点边看);
 *  width = 连线粗细基准(确认边 +0.4 加粗),由 buildData 按渲染口径传入 */
function edgeStyle(r, dimmed, width) {
  const c = themeColors()
  const suspect = r.cardinality === 'SUSPECT_MANY_TO_MANY'
  const color = suspect ? c.danger : (r.status === 'CONFIRMED' ? c.primary : c.borderDarker)
  return {
    stroke: color,
    lineWidth: r.status === 'CONFIRMED' ? width + 0.4 : width,
    lineDash: r.status === 'CANDIDATE' ? [6, 4] : 0,
    opacity: dimmed ? 0.06 : 1
  }
}

/** 碰撞消解:由内向外(同圈按名序,确定性)逐节点检查与已放置节点是否重叠;
 *  重叠时在附近搜索空位——先在原圈向两侧按「节点直径弧距」错开角度小范围搜索,
 *  整圈都放不下再逐档外扩半径(延长连接线)继续搜,直到不重叠为止;极端密集兜底保持原位 */
function resolveOverlaps(pos) {
  const rad = (name) => sizeOf(name) / 2
  const GAP = 8 // 节点间最小净距
  const placed = []
  const names = [...pos.keys()].sort((a, b) => {
    if (a === props.anchorTable) return -1
    if (b === props.anchorTable) return 1
    const pa = pos.get(a)
    const pb = pos.get(b)
    return Math.hypot(pa[0], pa[1]) - Math.hypot(pb[0], pb[1]) || a.localeCompare(b)
  })
  for (const name of names) {
    const [x0, y0] = pos.get(name)
    const collides = (x, y) =>
      placed.some((p) => Math.hypot(x - p.x, y - p.y) < rad(name) + rad(p.name) + GAP)
    if (name === props.anchorTable || !collides(x0, y0)) {
      placed.push({ name, x: x0, y: y0 })
      continue
    }
    const r0 = Math.hypot(x0, y0)
    const t0 = Math.atan2(y0, x0)
    let done = false
    for (let dr = 0; dr <= 4 * RING_GAP && !done; dr += 16) {
      const r = r0 + dr
      const dt = (NODE_SIZE + GAP * 2) / r // 角步长:弧距 ≈ 节点直径 + 两倍净距
      for (let i = 1; i * dt <= Math.PI && !done; i++) {
        for (const s of [1, -1]) {
          const t = t0 + s * i * dt
          const x = r * Math.cos(t)
          const y = r * Math.sin(t)
          if (!collides(x, y)) {
            pos.set(name, [x, y])
            placed.push({ name, x, y })
            done = true
            break
          }
        }
      }
    }
    if (!done) placed.push({ name, x: x0, y: y0 })
  }
}

/** props -> G6 数据;静态坐标直接放节点 style(不跑布局,渲染一次到位);
 *  force 模式不预设坐标(种子会让仿真收敛到种子附近,放不开),交由 d3-force 从零自动编排,
 *  碰撞消解/径向分层等静态逻辑全部跳过。
 *  hl/sel/selList 默认取当前 props;传 null/'' 可剥离高亮/选中态(结构数据用——见 graphData);
 *  style(连线粗细/文本透明度)同理:默认取 live props,结构数据显式传 STRUCT_STYLE 剥离依赖,
 *  让这些纯渲染口径参数的变化走 applyOverlay 的 repaint 原地刷,不触发重建+重跑仿真;
 *  colors(节点填充色)缺省取 live props.colors;结构数据由 graphData 显式传入快照(非响应式),
 *  使重建首帧即为最终色,同时不把颜色登记为结构依赖 */
function buildData(hl, sel, style, colors, selList) {
  const c = themeColors()
  const force = props.layout === 'force'
  // 力导参数(向心力/排斥力/吸引力)只进布局回调不进图数据,这里显式触碰建立依赖:
  // 变化时 graphData 重建 → 底座整体重渲 → 仿真按新参数重排(与「连线边长」同口径)
  void props.centerStrength
  void props.chargeStrength
  void props.linkStrength
  const { edgeWidth, labelOpacity } = style ?? { edgeWidth: props.edgeWidth, labelOpacity: props.labelOpacity }
  const pos = force ? new Map() : nodePositions()
  if (!force) resolveOverlaps(pos)
  const hlSet = hl === undefined ? (props.highlight ? new Set(props.highlight) : null) : hl
  const selectedName = sel === undefined ? props.selected : sel
  // 多选集合(Shift 框选/点选):缺省取 live props;结构数据传空集合剥离,由覆盖层 repaint 刷选中态
  const selSet = selList === undefined ? new Set(props.selectedList || []) : selList
  // 节点填充色:colors 缺省时取 live props.colors(覆盖层 repaint 走这条);
  // 结构数据由 graphData 显式传入快照,使重建首帧即最终色(改色不重建,见 applyOverlay)
  const fillOf = (name) => (colors === undefined ? props.colors : colors)?.[name] || c.primary
  const nodes = props.nodes.map((n) => {
    const isAnchor = n.name === props.anchorTable
    const isSelected = n.name === selectedName || selSet.has(n.name)
    const matched = !hlSet || hlSet.has(n.name)
    const color = fillOf(n.name)
    const [x, y] = pos.get(n.name) || []
    return {
      id: n.name,
      data: { table: n.name, comment: n.comment || '' },
      style: {
        // force 模式不落坐标(由力导自动编排);静态模式坐标算死
        ...(x === undefined ? {} : { x, y }),
        size: sizeOf(n.name),
        // 层级:节点恒定压过连线(边 zIndex=0)。必须显式给值——drag-element-force 拖拽开始时
        // G6 会 frontElement 把被拖节点置顶(zIndex=max+1 且不归位),此后新增边若依赖默认计算
        // (相连节点 zIndex 最大值 -1),沾到被拖节点的边会升到 0 与节点同级,后插入渲染压过节点,
        // 表现为「连线盖住节点、点名称选不中」(边在上吞掉点击)
        zIndex: 1,
        fill: color,
        // 选中态:正文色深描边;锚点描边用主题色但较细
        stroke: isSelected ? c.text : isAnchor ? c.primary : color,
        lineWidth: isSelected ? 3 : isAnchor ? 2 : 1.5,
        cursor: 'pointer',
        opacity: matched ? 1 : 0.12,
        // 光晕(halo 恒画在节点下方,不遮挡描边):选中节点加一圈「节点同色的浅色圆环」突出选中态,
        // 筛选命中的节点沿用同一视觉语言(略细);两者的颜色都随节点颜色走
        halo: isSelected || !!(hlSet && matched),
        haloStroke: color,
        haloStrokeOpacity: isSelected ? 0.25 : 0.3,
        haloLineWidth: isSelected ? 20 : 12,
        label: true,
        labelText: displayName(n),
        labelPlacement: 'bottom',
        labelFontSize: 12,
        labelFontWeight: isAnchor ? 600 : 400,
        labelFill: matched ? c.text : c.textSecondary,
        labelOffsetY: 4,
        // 全名换行展示(不截断):超 140px 宽自动折行;行数上限放大到 20(约等于不限,G6 label 默认
        // maxLines=1 会打省略号,必须显式放大;长注释完整显示,节点弧距已为多行标签预留)
        labelWordWrap: true,
        labelMaxWidth: 140,
        labelMaxLines: 20,
        labelOpacity: matched ? labelOpacity : 0.25 * labelOpacity
      }
    }
  })
  const edges = props.edges.map((r) => ({
    id: String(r.id),
    source: r.oneTable,
    target: r.manyTable,
    style: {
      ...edgeStyle(r, !!hlSet && !(hlSet.has(r.oneTable) && hlSet.has(r.manyTable)), edgeWidth),
      // 层级:连线恒定在节点之下(节点 zIndex=1,理由见节点样式注释);distance 仅入力导回调,不在连线上标数字
      zIndex: 0
    }
  }))
  return { nodes, edges }
}

// 力导回调不能从图数据上读自定义字段:G6→@antv/layout 的模型转换(layoutAdapter)只保留
// 边 id/source/target、节点 id/坐标,其余字段全被剥掉——边长/叶子判定只能在这里按 id 闭包回查
/** 边 id → 可见边长分档(供 link.distance/strength 回调) */
const edgeDistanceById = computed(
  () => new Map(props.edges.filter((e) => e.distance != null).map((e) => [String(e.id), e.distance]))
)
/** 叶子节点名集合(供 manyBody 分档):优先按父子树(parentOf)出度,未传则按边的一端出度兜底 */
const leafNames = computed(() => {
  const hasChild = new Set()
  if (props.parentOf) {
    for (const n of props.nodes) {
      const p = props.parentOf[n.name]
      if (p) hasChild.add(p)
    }
  } else {
    for (const e of props.edges) hasChild.add(e.oneTable)
  }
  return new Set(props.nodes.map((n) => n.name).filter((name) => !hasChild.has(name)))
})

/** d3-force 力导向布局(参考官方 demo,知识图谱 hub 口径:目录大节点下挂小节点表):
 *  边长按调用方给的分档(如 目录↔目录长边 = 3 × 目录→表短边),缺省 60;注意 G6→layout 的模型转换会剥掉
 *  边上/节点上的自定义字段,分档只能按边 id(edgeDistanceById)/节点名(leafNames)闭包回查;
 *  distance 语义 = 可见连线长度(两圆边缘间距)——d3-force 的 link 距离是圆心距,
 *  这里换算加回两端半径,否则大节点半径吃掉边长、且会被 collide 最小圆心距顶开导致长短边一样长;
 *  长边弱(主干摊开)、短边强(卫星节点向目录聚拢);
 *  叶子节点斥力大、内部节点斥力小;collide 半径按节点尺寸分档 + 16px 净距防节点重叠
 *  (padding 不能太大,否则会盖过短边 link 目标);
 *  向心力(forceX/forceY)与 link/manyBody 强度倍率均可由父级经 props 实时调节(回调读 live prop,重建即生效);
 *  布局动画保持开启(默认):仿真逐帧收敛,drag-element-force 依赖它 */
function forceLayoutOptions() {
  const idOf = (x) => (typeof x === 'object' ? x.id : x)
  const distOf = (e) => edgeDistanceById.value.get(String(e.id)) ?? 60
  return {
    type: 'd3-force',
    // 收敛提速:d3 仿真要跑到 alpha < alphaMin 才算完(默认 0.001 + alphaDecay≈0.0228 ≈ 300 tick ≈ 5s,
    // G6 render 全程等待、画布一直隐藏——切页签长时间空白的根因);提高 alphaMin/加快衰减压到 ~50 tick(<1s)。
    // 仿真精度略降,但强 link 力下结构成型很早,视觉无损;拖拽回温(alphaTarget 0.3)不受影响
    alphaMin: 0.05,
    alphaDecay: 0.06,
    link: {
      distance: (e) => distOf(e) + (sizeOf(idOf(e.source)) + sizeOf(idOf(e.target))) / 2,
      strength: (e) => (distOf(e) >= 120 ? 0.15 : 0.8) * props.linkStrength
    },
    manyBody: {
      strength: (d) => (leafNames.value.has(d.id) ? -220 : -80) * props.chargeStrength
    },
    // 向心力:d3 forceX/forceY 把各节点向布局中心拉(位置缺省取视口中心,与现状一致);
    // strength 传函数——d3 在仿真初始化时逐节点求值,读 live prop 保证调节后重排生效
    x: { strength: () => props.centerStrength },
    y: { strength: () => props.centerStrength },
    collide: {
      radius: (d) => sizeOf(d.id) / 2 + 16,
      strength: 0.9
    }
  }
}

// 底座输入:结构数据(节点/边集合、坐标、尺寸、力导参数)变化 → 底座整体重建(力导会重新仿真,尽量少触发);
// 高亮/选中态与渲染口径样式(连线粗细/文本透明度/节点填充色)不进结构依赖——它们走下方 applyOverlay
// 原地刷样式,避免每点一下节点/改一次颜色/拖一下样式滑杆就重建+重跑仿真;
// 结构数据携带的渲染口径样式固定为默认值(STRUCT_STYLE);节点填充色是例外——随结构数据一起写出当前值,
// 这样筛选裁剪等重建的首帧就是最终色,不会先画主题色再由覆盖层过渡过来:
// 用非响应式变量 structColors 存快照(由 sync watch 跟着 props.colors 走),graphData 读它时不建立依赖,
// 故改色不会重算结构数据(仍走 repaint 原地刷),而重建时读到的是最新快照;
// redrawTick = 强制整体重绘计数(父级「刷新」/「重置」按钮):递增即重建,与参数变更同一条 refresh 路径,天然合并成一次;
// 触碰 themeState.dark:亮/暗主题切换时重算(节点/边颜色取自主题变量),底座整体重建换色(不动视口)
const STRUCT_STYLE = { edgeWidth: EDGE_WIDTH_DEFAULT, labelOpacity: LABEL_OPACITY_DEFAULT }
// 结构数据用的空多选集合(常量复用,避免每次重建新建 Set)
const EMPTY_SELECTION = new Set()
// 结构色快照(普通变量,刻意非响应式):sync watch 保证改色瞬间就同步到快照,重建时读到即最终色
let structColors = {}
watch(() => props.colors, (v) => { structColors = v || {} }, { immediate: true, flush: 'sync' })
const redrawTick = ref(0)
const graphData = computed(() => {
  void themeState.dark
  void redrawTick.value
  // 多选集合传空 Set:选中态属覆盖层口径(见 applyOverlay);空 Set 常量复用,不每次新建
  return buildData(null, '', STRUCT_STYLE, structColors, EMPTY_SELECTION)
})

// 覆盖层(高亮/选中态/渲染口径样式/节点填充色):变化时经底座 repaint(setData+draw,不跑布局、不动仿真)
// 原地刷透明度/光晕/描边/线宽/标签透明度/填充色;结构重建(力导重排)后元素样式被 graphData
// 重置回 STRUCT_STYLE(填充色本身已是最新,补刷只是保持一致),借底座 rendered 事件补刷;签名判重防 rendered↔repaint 循环
let appliedOverlay = ''
// 强制重绘(redraw())进行中标记:此期间跳过覆盖层 watch 触发的中间 repaint——
// 「重置」批量改参数时,避免「旧布局上先原地刷一遍默认样式、再整体重排」的两段跳变,rendered 后统一补刷收尾
let pendingRedraw = false
function applyOverlay() {
  const sig = JSON.stringify([props.highlight, props.selected, props.selectedList, props.edgeWidth, props.labelOpacity, props.colors])
  if (sig === appliedOverlay) return
  if (!baseRef.value?.getGraph()) return // 图未就绪,等 rendered 事件补刷
  if (pendingRedraw) return // 强制重绘会整体重渲,等 rendered 后补刷(见 onRendered)
  appliedOverlay = sig
  baseRef.value.repaint(buildData())
}
watch(() => [props.highlight, props.selected, props.selectedList, props.edgeWidth, props.labelOpacity, props.colors], applyOverlay)
// 外部把 selectedList 清空(多选面板关闭/点节点开单表面板等):同步清掉底座选中态——
// 否则 G6 模型里残留 selected,下次 Shift+点击会被误判成「取消选中」(底座选中态是框选/点选的唯一口径)
watch(() => props.selectedList, (v) => { if (!v?.length) baseRef.value?.clearSelection() })
// 结构数据一重建就置空签名:rendered 后的补刷不被判重跳过,覆盖层(含用户调过的样式)才能重新刷上
watch(graphData, () => { appliedOverlay = '' })
/** 底座 rendered:强制重绘完成后清标记并补刷覆盖层(重建把元素样式重置回了 STRUCT_STYLE);
 *  重建同时会清掉 G6 模型里的选中态——把父级多选集合回灌底座,保持「底座选中态(框选/点选口径)」
 *  与「selectedList(呈现口径)」一致(setSelection 只在有变化时发事件,不会循环) */
function onRendered() {
  pendingRedraw = false
  applyOverlay()
  bindHoverEvents()
  if (props.selectedList?.length) baseRef.value?.setSelection(props.selectedList)
}

// hover 置顶:悬停节点临时抬到最上层(普通节点 zIndex=1、边=0、悬停=2),移开恢复——
// 密集图中被压住的节点/标签 hover 即可完整看清;zIndex 写进图数据(updateNodeData 浅合并 style,
// 只覆盖 zIndex 不动其余样式),力导 tick 重绘不会冲掉
let hoverBoundGraph = null
/** 给图实例绑定 hover 置顶(幂等:按实例判重,画布销毁重建/换实例后由 rendered 补绑) */
function bindHoverEvents() {
  const g = baseRef.value?.getGraph()
  if (!g || g === hoverBoundGraph) return
  hoverBoundGraph = g
  g.on('node:pointerenter', (e) => setNodeZIndex(e?.target?.id, 2))
  g.on('node:pointerleave', (e) => setNodeZIndex(e?.target?.id, 1))
}
/** 改单个节点 zIndex 并重绘(不跑布局) */
async function setNodeZIndex(id, z) {
  const g = baseRef.value?.getGraph()
  if (!g || !id) return
  g.updateNodeData([{ id, style: { zIndex: z } }])
  await g.draw()
}
/** 强制整体重绘(父级调试图谱「刷新」/「重置」按钮):递增 redrawTick 触发底座一次 setData+render,
 *  力导按当前配置重跑仿真(动画由 animated 决定,force 模式恒开);视口不动(底座 refitOnDataChange=false) */
function redraw() {
  pendingRedraw = true
  redrawTick.value++
}
const graphOptions = {
  node: { type: 'circle' },
  edge: { type: 'line' },
  // 同表对多条平行边按曲率分开(bundle 会把 line 边改写为 quadratic,本图无字段对齐诉求,直接可用)
  transforms: [{ type: 'process-parallel-edges', mode: 'bundle', distance: 24 }],
  behaviors: [
    // 力导模式换 drag-element-force(拖拽时仿真回温,松手自动归位);静态模式 drag-element;
    // Shift+拖 让位给底座框选(selectable 内置 brush-select,否则按住 Shift 拖节点会一边拖节点一边画框选)
    {
      type: props.layout === 'force' ? 'drag-element-force' : 'drag-element',
      enable: (e) => !e.shiftKey
    }
  ], // 底座内置 drag-canvas 拖画布(Shift+拖 时让位给框选);Shift 框选/多选走底座 selectable(选中集合经 selection-change 同步,选中呈现仍由 selectedList 驱动)
  ...(props.layout === 'force' ? { layout: forceLayoutOptions() } : {}),
  // 静态模式关掉元素入场动画:render() 会等 draw 动画 finished 才 resolve,关掉后 render/重建都是瞬时完成;
  // 力导模式必须保留动画——仿真收敛与 drag-element-force 都依赖 tick 渲染,关动画会导致节点拖不动
  ...(props.layout === 'force' ? {} : { animation: false })
}

// 高级操作(视口观测/导出等)经底座拿原始 Graph 实例;redraw = 强制整体重绘(调试图谱「刷新」/「重置」按钮)
defineExpose({ getGraph: () => baseRef.value?.getGraph(), redraw })
</script>
