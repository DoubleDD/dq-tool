<template>
  <!-- 通用 G6 画布基础组件:容器需显式高度(由父级布局保证)。画布浮层统一约定:
       顶部 = 工具栏(左:内置默认工具[重绘/1:1/适应画布/线形(第 4 个图标位)/档位,按 tools 裁剪]
       + toolbar 插槽业务工具,控件区带毛玻璃底[图元素拖到下面不挡阅读];右:缩放控制条[同款毛玻璃底];
       整条工具栏的小组件高度严格一致,口径见样式里的 --bgc-ctl-h);
       右下角 = 鸟瞰图(G6 minimap 插件,相对画布自适应),其左 = Shift 框选/多选操作提示
       (selectable 开启时显示,半透明不挡交互);左下角 = 图例折叠面板(legend 插槽);
       其余浮层(节点面板等)走默认插槽由调用方自绘;首帧隐藏:render 先按世界坐标落笔,
       视口定位(settleView)完成前不显示,避免内容闪在左上角 -->
  <div ref="wrapRef" class="bgc-wrap">
    <div ref="containerRef" class="bgc-canvas" :class="{ 'bgc-canvas-hidden': !viewReady }" />
    <div v-if="showToolbar" class="bgc-toolbar">
      <!-- 左侧控件区:毛玻璃底(图元素拖到下面不挡阅读);只包有组件的地方,中部留空可拖画布 -->
      <div class="bgc-toolbar-main">
        <el-tooltip v-if="hasTool('refresh')" content="重绘画布(不重拉数据、不动缩放与位置;放大后字体发虚时点一下)" placement="bottom">
          <el-button size="small" :icon="Refresh" @click="redraw" />
        </el-tooltip>
        <el-tooltip v-if="hasTool('zoom100')" content="实际大小(100%)" placement="bottom">
          <el-button size="small" @click="zoomTo100">1:1</el-button>
        </el-tooltip>
        <el-tooltip v-if="hasTool('fit')" content="适应画布(容纳全部节点)" placement="bottom">
          <el-button size="small" :icon="Aim" @click="fitView" />
        </el-tooltip>
        <!-- 线形:第 4 个图标位(重绘/1:1/适应画布之后的图标位)。
             文字版「曲线/直角/圆角」太占宽且与档位文字组视觉混淆,改为图标单选:
             图标即线型示意(曲线/直角/圆角折线),名称走 tooltip;取值仍是 curve/orth/orth-round 不变 -->
        <el-radio-group
          v-if="hasTool('edge-type') && edgeType"
          class="bgc-edge-icons"
          :model-value="edgeType"
          size="small"
          @update:model-value="emit('update:edgeType', $event)"
        >
          <el-tooltip v-for="t in EDGE_TYPES" :key="t.value" :content="t.label" placement="bottom">
            <el-radio-button :value="t.value" :aria-label="t.label">
              <EdgeTypeIcon :type="t.value" />
            </el-radio-button>
          </el-tooltip>
        </el-radio-group>
        <!-- 导出 drawio:第 5 个图标位(线形之后、档位之前);可选工具(默认工具组不含,ER 画布经 tools 启用),
             导出数据组装在业务侧(画布 exportData),点击只发事件 -->
        <el-tooltip v-if="hasTool('export-drawio')" content="导出 drawio" placement="bottom">
          <el-button size="small" :icon="Download" @click="emit('export-drawio')" />
        </el-tooltip>
        <el-radio-group
          v-if="hasTool('level') && level"
          :model-value="level"
          size="small"
          @update:model-value="emit('update:level', $event)"
        >
          <el-radio-button value="name">仅表名</el-radio-button>
          <el-radio-button value="related">关联字段</el-radio-button>
          <el-radio-button value="all">全部字段</el-radio-button>
        </el-radio-group>
        <!-- 业务工具:调用方按需追加(字段数/筛选/导出等) -->
        <slot name="toolbar" />
      </div>
      <!-- 缩放控制条:固定在工具栏右侧(−/+ 步进、比例输入、全屏) -->
      <div v-if="zoomBar" class="bgc-zoombar">
        <button class="bgc-zb-btn" title="缩小" @click="zoomBy(-1)">−</button>
        <input
          v-model="zoomInput"
          class="bgc-zb-input"
          :title="`输入比例(${Math.round(minZoom * 100)}-${Math.round(maxZoom * 100)})后回车`"
          @focus="zoomEditing = true"
          @blur="onZoomBlur"
          @keyup.enter="($event.target).blur()"
          @keydown.esc.prevent="onZoomCancel"
        /><span class="bgc-zb-pct">%</span>
        <button class="bgc-zb-btn" title="放大" @click="zoomBy(1)">+</button>
        <template v-if="fullscreen">
          <span class="bgc-zb-divider" />
          <button class="bgc-zb-btn" :title="isFullscreen ? '退出全屏(Esc)' : '全屏显示'" @click="toggleFullscreen">⛶</button>
        </template>
      </div>
    </div>
    <!-- 图例折叠面板(左下角):默认展开,点标题栏收起为小条,再点开还原 -->
    <div v-if="$slots.legend" class="bgc-legend">
      <button v-if="legendCollapsed" class="bgc-legend-chip" title="展开图例" @click="legendCollapsed = false">图例 ▸</button>
      <div v-else class="bgc-legend-panel">
        <div class="bgc-legend-head" title="收起图例" @click="legendCollapsed = true">
          <span>图例</span>
          <span class="bgc-legend-caret">▾</span>
        </div>
        <div class="bgc-legend-body"><slot name="legend" /></div>
      </div>
    </div>
    <!-- Shift 框选/多选操作提示:鸟瞰图左侧、底边与鸟瞰图对齐(位置见 selectHintStyle),半透明;
         仅 selectable 开启时显示(能力关着不误导);纯提示不接收指针事件,不挡框选/拖画布 -->
    <div v-if="selectable" class="bgc-select-hint" :style="selectHintStyle">Shift+拖动框选 · Shift+点击多选</div>
    <slot />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, useSlots, watch } from 'vue'
import { Graph } from '@antv/g6'
import { Aim, Download, Refresh } from '@element-plus/icons-vue'
import { bindHtmlNodeWheel } from '../../utils/graphCanvasWheel'
import EdgeTypeIcon from './EdgeTypeIcon.vue'

/**
 * 通用 G6 画布基础组件(ER 图 / 对象管理图视图 / 图谱 三处画布的公共底座,后续迁移统一走这里)。
 *
 * 画布浮层统一约定(三处画布同口径,不再各自实现):
 *  - 顶部工具栏:左侧 = 内置默认工具(重绘/1:1/适应画布/线形[第 4 个图标位,三图标单选]/档位,按 tools 裁剪;
 *    重绘 = 只按当前数据 graph.render() 重画一遍,不重拉数据、不动缩放与视口——放大后 HTML 节点字体发虚时点它恢复清晰;
 *    档位/线形需配 v-model 才显示)+ toolbar 插槽(业务工具:字段数/筛选/导出等,调用方按需给);右侧 = 缩放控制条;
 *    小组件高度严格一致:统一取 --bgc-ctl-h(默认 24px,与 Element Plus 小号档位同口径),
 *    缩放控制条是同样 24px 高的容器,插槽里的业务工具也被这条规则覆盖,调用方不必各自写死高度;
 *  - 右下角鸟瞰图(G6 minimap 插件,相对画布自适应,视野框可拖拽平移主画布;html 节点需经 minimap.shape 给缩略块,
 *    可用 ./htmlMinimapShape 的 createHtmlMinimapShape 工厂);鸟瞰图左侧 = Shift 框选/多选操作提示
 *    (selectable 开启时显示,半透明、不接收指针事件);
 *  - 左下角图例折叠面板(legend 插槽给内容,面板外壳/折叠交互由底座提供;默认展开,点标题栏收起);
 *  - 缩放控制条:−/+ 步进(×1.2,绕视口中心)、比例可输入(回车/失焦提交,Esc 取消,
 *    输入中不被 aftertransform 回写覆盖)、全屏切换;比例与画布双向同步;
 *  - 主题跟随:画布本体透明(init 传 background:'transparent'),背景由容器 CSS 变量 var(--el-bg-color) 提供,
 *    鸟瞰图容器/视野框配色同样走 var(),亮/暗主题切换即时生效无需重建;
 *    节点/边等数据内颜色由调用方监听 stores/theme 的 themeState.dark 触发重建换色(见各画布 graphData)。
 *
 * 收敛的公共能力:
 *  - 交互(滚轮系,与三处现有画布同口径):
 *      滚轮 = 平移画布(上下左右,scroll-canvas);
 *      物理 Ctrl+滚轮 = 以指针为中心缩放(zoom-canvas trigger Control,Shortcut 严格匹配真实按键);
 *      触摸板捏合 = 缩放(浏览器合成为 ctrlKey=true 的 wheel,无物理 Control keydown,走 enable 回调,与上一条互斥);
 *      触屏双指捏合 = 缩放(PinchHandler 合成 pinch);
 *      Alt+滚轮 = 水平平移(deltaY 映射为横向位移;容器捕获阶段拦截,优先于 scroll-canvas,canvas/节点 DOM 统一生效);
 *      悬在 HTML 节点上滚轮同样生效——html 节点是叠在 canvas 上的真实 DOM,wheel 不进 g 事件总线,
 *      由容器层 bindHtmlNodeWheel(utils/graphCanvasWheel.js)按 behaviors 同口径转发;
 *      html 节点双击由容器层按世界坐标反查补发(G6 v5 只转发 html 节点的 click/pointer 系事件,不转发 dblclick);
 *  - 框选/多选(selectable 开启,底座基础能力):Shift+拖动 = 矩形框选节点(G6 brush-select,union 累加,
 *    保留元素既有其他状态),Shift+点击节点 = 切换其选中态(click-select multiple;普通点击不进选中,
 *    留给 node-click 等既有交互),点空白 = 清空选中;选中只作用节点(边/组合不纳入),
 *    选中集合变化统一发 selection-change(当前选中节点 id 全量),选中后做什么由调用方决定
 *    (选中呈现:给节点配 state.selected 样式,或监听事件自行渲染);clearSelection() 供消费完复位,
 *    setSelection(ids) 供自有单选交互程序化接入;框选松手后浏览器补发的 click 由容器捕获阶段
 *    按 300ms 时间窗吞掉(否则 brush-select 自带的 canvas:click 清空会把刚框出的选中当场清掉);
 *  - 视口定位:autoFit 关闭(render 每次都跑 autoFit,交给它会把就地重渲染也重新定位),
 *    初始/重建定位显式收敛到 settleView()(fit='center' → fitCenter + 归一到 defaultZoom;'view' → fitView;'none' → 不动);
 *  - 保持视口口径的锚点补偿:refresh(..., false) 与 redraw() 都走 render(会重跑布局),而 G6 树布局在 postLayout 里
 *    会按 inferTreeLayoutOffset 重新锚定整棵树(布局结果装得进视口按布局原点锚定、装不下平移到视口中心),两种口径
 *    切换时视口不动而整张图瞬移一次(同状态幂等,故只跳第一次)。树布局下按「根节点位移」反向平移视口抵消(根中心恒在
 *    布局原点,位移即隐式平移量),屏幕内容保持不动;其余布局(dagre/静态坐标)没有这层平移,自动跳过;
 *  - 生命周期:挂载建图、卸载销毁、data 变化整体重建(setData+render)、optionsKey 变化销毁重建(如布局方向切换);
 *    只改渲染口径、不改节点尺寸与集合的刷新走 repaint()(setData+draw,不跑布局,连补偿都用不上);
 *    首帧隐藏:render 先按世界坐标落笔,首次视口定位完成前画布 opacity 0,避免内容闪在左上角;
 *  - 容器尺寸同步:G6 autoResize 只挂 window resize,页签 display:none ↔ 恢复等容器尺寸变化它不感知
 *    (隐藏期间的 window resize 还会把画布改成最小尺寸,切回后缩成一小块);底座用 ResizeObserver 盯容器,
 *    尺寸为 0 跳过并记 hiddenStale(隐藏期的 resize/fitView 均不可信),恢复非零且与画布尺寸不一致时同步,
 *    若经历过隐藏则同步后重新定位视口;
 *
 * 调用方负责:节点/边类型与样式、布局、transforms、业务事件处理(经 emits 拿到 id/data/原始事件自行判定,
 * 如 html 节点内 DOM 命中用 event.nativeEvent.target.closest(...))、额外 behaviors/plugins(如 'drag-element')、
 * toolbar 插槽的业务工具与 refresh 事件的数据重拉。
 *
 * props:
 *  data        G6 图数据 { nodes, edges, combos? },深监听变化即整体重建
 *  options     业务侧 G6 配置:node/edge/layout/transforms(每次重建重放)/background/animation,以及追加的 behaviors/plugins 数组
 *  optionsKey  变更时销毁重建 graph(建图期配置如布局方向变化时用)
 *  fit         视口定位方式:'view'(默认,fitView 自适应) | 'center'(fitCenter + 归一到 defaultZoom) | 'none'
 *  defaultZoom fit='center' 时的归一比例,默认 1
 *  refitOnDataChange  data 变化重建后是否重新定位视口,默认 true(局部更新请走 getGraph() 自行 render,
 *                     或 refresh(data, false) 保持视口)
 *  minimap     鸟瞰图:true(默认,180×120 右下角)/ false 关闭 / 对象覆盖 { size, filter, shape, containerStyle, maskStyle }
 *  zoomBar     是否显示缩放控制条,默认 true
 *  fullscreen  缩放控制条是否带全屏按钮,默认 true
 *  minZoom/maxZoom  缩放范围,默认 [0.2, 4]
 *  tools       顶部工具栏内置默认工具(按数组裁剪):refresh 重绘(见下) / zoom100 1:1 / fit 适应画布 /
 *              edge-type 线形(三图标单选)/ level 档位;默认全五个(level/edge-type 未配 v-model 时自动隐藏);
 *              另有可选工具 export-drawio 导出 drawio(不在默认组,ER 画布启用,点击发 export-drawio 事件由业务侧导出);
 *              渲染顺序固定为 重绘 → 1:1 → 适应画布 → 线形(第 4 个图标位)→ 导出 drawio(第 5 位)→ 档位 → toolbar 插槽业务工具
 *  level       档位当前值(name 仅表名/related 关联字段/all 全部字段),配 @update:level 使用
 *  edgeType    线形当前值(curve 曲线/orth 直角/orth-round 圆角),图标单选,配 @update:edgeType 使用
 *  animated    重建/重绘渲染是否开动画,默认 false(关动画瞬时渲染,配合锚点补偿原地更新不飘移);
 *              活体仿真布局(d3-force 等)必须传 true——关动画路径会 layout.stop() 且不挂 onTick,
 *              仿真死后 drag-element-force 拖拽回温画面不更新;树/静态坐标布局保持默认 false 即可
 *  selectable  框选/多选开关,默认 false。开启后:Shift+拖动矩形框选节点、Shift+点击节点增减选中、
 *              点空白清空;选中集合变化发 selection-change(见 emits),鸟瞰图左侧显示操作提示。
 *              建图期配置(与 minimap/tools 同口径,运行期切换不生效);开启后业务侧勿再自配
 *              brush-select/lasso-select/click-select behavior(会重复);自配 drag-element 的调用方
 *              需对 Shift 让位(enable: e => !e.shiftKey),否则按住 Shift 拖节点会一边拖一边框选;
 *              Shift+点击仍会触发 node-click,调用方可按 event.nativeEvent.shiftKey 区分
 *
 * emits:
 *  node-click / node-dblclick / edge-click —— 均传 (id, data, 原始事件);node-dblclick 的原始事件为原生 DOM 事件
 *  (容器层坐标反查补发,G6 v5 不产生 dblclick),其余为 G6 事件(html 节点内 DOM 命中用 nativeEvent.target 判定);
 *  canvas-click —— 点击画布空白(不传参,供调用方做「点空白取消选中」);
 *  selection-change(ids) —— 选中集合变化(selectable 开启时):当前带 selected 状态的节点 id 全量,
 *  框选/点选/清空(含 clearSelection())都会发;选中后做什么由调用方决定;
 *  ready(graph) 首次渲染+定位完成后触发;rendered 每次 render 完成后触发(初始化/重建,供调用方做后置加载);
 *  update:level / update:edgeType 档位/线形切换;export-drawio 导出 drawio 按钮(可选工具,业务侧组装数据并下载)
 *
 * defineExpose:
 *  getGraph() 拿原始 Graph 实例(高级操作:updateNodeData、导出取坐标等);
 *  getContainer() 拿画布容器 DOM(调用方需在容器上做事件委托时用,如节点内特定区域的单击/双击);
 *  refresh(data?, settle?) 重建(setData+render,settle 默认取 refitOnDataChange;settle=false 保持视口并做锚点补偿);
 *  repaint(data?) 只重绘不重排(setData+draw,不跑布局;仅适用于节点尺寸与集合不变、只改渲染口径的刷新);
 *  reconfigure() 就地重配 node/edge/layout/transforms 并重渲(不销毁,布局切换带过渡动画);
 *  recreate() 销毁重建;settleView() 重新定位;
 *  redraw() 仅重绘(工具栏「重绘」按钮同口径;走 render 会重跑布局,与 repaint 的区别在此,同样带锚点补偿);
 *  clearSelection() 清空当前选中(selectable 的选中集合,变化同样发 selection-change);
 *  setSelection(ids) 程序化设置选中(替换语义):调用方把自有交互(如普通点击单选)接入同一选中状态时用,
 *  保证 selection-change 上报的集合始终完整;
 *  fitView() / fitCenter() / zoomTo100() / zoomTo(z) / zoomBy(dir) / getZoom()
 */
const props = defineProps({
  data: { type: Object, required: true },
  options: { type: Object, default: () => ({}) },
  optionsKey: { type: [String, Number], default: 0 },
  fit: { type: String, default: 'view' }, // 'view' | 'center' | 'none'
  defaultZoom: { type: Number, default: 1 },
  refitOnDataChange: { type: Boolean, default: true },
  minimap: { type: [Boolean, Object], default: true },
  zoomBar: { type: Boolean, default: true },
  fullscreen: { type: Boolean, default: true },
  minZoom: { type: Number, default: 0.2 },
  maxZoom: { type: Number, default: 4 },
  tools: { type: Array, default: () => ['refresh', 'zoom100', 'fit', 'level', 'edge-type'] },
  level: { type: String, default: '' },
  edgeType: { type: String, default: '' },
  // 重建/重绘渲染是否开动画(调用方按布局需要决定;力导向活体仿真必须 true,见文件头 props 说明)
  animated: { type: Boolean, default: false },
  // 框选/多选开关(建图期配置):Shift+拖动框选节点、Shift+点击多选、点空白清空,
  // 选中集合变化发 selection-change;详见文件头 props 说明
  selectable: { type: Boolean, default: false }
})

const emit = defineEmits(['ready', 'rendered', 'node-click', 'node-dblclick', 'edge-click', 'canvas-click', 'selection-change', 'update:level', 'update:edgeType', 'export-drawio'])

const slots = useSlots()
/** 顶部工具栏显隐:有缩放控制条/内置工具/业务工具插槽任一即显示 */
const showToolbar = computed(() => props.zoomBar || props.tools.length > 0 || !!slots.toolbar)
/** 内置默认工具是否启用(按 tools 裁剪) */
const hasTool = (k) => props.tools.includes(k)
/** 线形图标单选项:value 与画布 edgeType 同口径,label 走 tooltip(图标本身放不下文字) */
const EDGE_TYPES = [
  { value: 'curve', label: '曲线' },
  { value: 'orth', label: '直角' },
  { value: 'orth-round', label: '圆角' }
]
// 图例折叠面板:默认展开,收起为小条(不持久化)
const legendCollapsed = ref(false)

/** Shift 框选/多选提示定位:贴鸟瞰图左侧、底边与鸟瞰图对齐(鸟瞰图固定 right/bottom:12px,
 *  宽度取 minimap 配置,缺省 180);鸟瞰图关闭时退到右下角原位 */
const selectHintStyle = computed(() => {
  if (!props.minimap) return { right: '12px', bottom: '12px' }
  const m = typeof props.minimap === 'object' ? props.minimap : {}
  const [w] = m.size || [180, 120]
  return { right: `${12 + w + 10}px`, bottom: '12px' }
})

const wrapRef = ref(null)
const containerRef = ref(null)
let graph = null
// 重建/销毁串行化:recreate 与 refresh 可能同帧触发(如 optionsKey 与 data 同变),
// 用 opChain 排队 + recreateQueued 丢弃冗余 refresh,避免 destroy 与 render 交错
let opChain = Promise.resolve()
let recreateQueued = false
// 容器层滚轮接管解绑函数:html 节点 wheel 转发(bubble)+ Alt 水平平移(capture)
let unbindNodeWheel = null
let unbindAltWheel = null
// 容器层 html 节点双击补发解绑函数
let unbindDblclick = null
// selectable 的框选补发 click 吞掉解绑函数
let unbindSelectClick = null
// 容器尺寸监听器(见 onMounted:G6 autoResize 只挂 window resize,页签 display:none ↔ 恢复不会触发)
let resizeObserver = null
// 视口定位完成前隐藏画布(防首帧闪左上角;render 先按世界坐标原点附近落笔)
const viewReady = ref(false)

/** 鸟瞰图插件配置:默认 180×120 右下角;边框/底色用 CSS 变量(亮/暗主题切换自动跟随,
 *  minimap 容器/视野框是 DOM,var() 随 <html> dark 类即时生效);底色低透明 + backdrop-filter 模糊,
 *  与顶部工具栏控件区同款毛玻璃(图元素经过下面不挡缩略内容);minimap 为对象时逐项覆盖 */
function minimapPlugins() {
  if (!props.minimap) return []
  const m = typeof props.minimap === 'object' ? props.minimap : {}
  const plugin = {
    type: 'minimap',
    key: 'bgc-minimap',
    size: m.size || [180, 120],
    containerStyle: {
      left: 'auto',
      top: 'auto',
      right: '12px',
      bottom: '12px',
      border: '1px solid var(--el-border-color)',
      // 比工具栏略实(72%):压低背后图元素的透入噪音,保证缩略块对比度
      background: 'color-mix(in srgb, var(--el-bg-color) 72%, transparent)',
      backdropFilter: 'blur(12px)',
      WebkitBackdropFilter: 'blur(12px)',
      borderRadius: '6px',
      boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
      overflow: 'hidden',
      ...(m.containerStyle || {})
    },
    maskStyle: { border: '1px solid var(--el-color-primary)', background: 'rgba(64,158,255,0.12)', ...(m.maskStyle || {}) }
  }
  if (m.filter) plugin.filter = m.filter
  if (m.shape) plugin.shape = m.shape
  return [plugin]
}

/** 渲染后的视口定位:autoFit 已关,定位显式收敛到这里;视口操作全部瞬时(动画传 false)——
 *  重建内容已瞬时替换(renderNoAnimation),相机若再滑动过去,观感仍是「图在动」 */
async function settleView() {
  if (!graph) return
  if (props.fit === 'center') {
    await graph.fitCenter(false)
    // fitCenter 只平移不改比例;绕视口中心归一到默认比例,居中的内容保持居中
    if (Math.abs(graph.getZoom() - props.defaultZoom) > 1e-6) await graph.zoomTo(props.defaultZoom, false)
  } else if (props.fit === 'view') {
    await graph.fitView({}, false)
  }
}

// ---------- 保持视口时的锚点补偿(抵消 G6 树布局的整树重新锚定) ----------
// 背景:G6 v5 的树布局在每次 render 的 postLayout 里会调 inferTreeLayoutOffset——布局结果能装进当前视口世界矩形
//   就按布局原点锚定(偏移 0),装不下就把整棵树平移到视口中心。它改的是节点坐标、不动相机,而保持视口的重建
//   (refresh(..., false)/重绘)又刻意不重新 fitView,于是「布局结果装不装得进视口」这个口径一旦切换,整张图就会
//   在屏幕上瞬移一次(同状态下结果幂等,所以只跳第一次,第二次起正常原地更新)。
// 补偿:树布局的根节点中心恒在布局原点(@antv/hierarchy 的 fixedRoot 默认 true,先排完再整体平移把根中心拽到原点),
//   所以根节点的世界位移 Δ 正好等于 G6 这次的重新锚定量;按 Δ×zoom 换算成屏幕位移,把视口反向平移同样的像素,
//   屏幕上的内容位置就保持不变(世界坐标照旧交给 G6,不做手脚)。
/** G6 树布局类型名单(与 G6 内部 utils/layout.js 的 isTreeLayout 同口径):只有这些走 inferTreeLayoutOffset,
 *  其余布局(dagre/静态坐标等)没有这层隐式平移,无需补偿;数组形式的布局管线不处理 */
const TREE_LAYOUTS = ['compact-box', 'mindmap', 'dendrogram', 'indented']

function isTreeLayout() {
  const l = props.options?.layout
  return !!l && !Array.isArray(l) && TREE_LAYOUTS.includes(l.type)
}

/** 锚点 = 树根:数据里第一个没有入边的节点(无匹配时取第一个节点) */
function anchorIdOf(nodes, edges) {
  if (!nodes.length) return ''
  const targets = new Set(edges.map((e) => e.target))
  const root = nodes.find((n) => !targets.has(n.id)) || nodes[0]
  return root.id
}

/** 渲染前记录锚点世界坐标;非树布局/锚点不存在(首帧、本次被移除)时返回 null,表示本次不补偿。
 *  锚点取自图模型而非 props.data:业务侧可能显式传了新数据(如字段拉取后 refresh(buildData(), false)),
 *  此时 props.data(computed)还是旧的,而模型一定与当前渲染的内容一致 */
function snapshotAnchor() {
  if (!graph || !isTreeLayout()) return null
  let id = ''
  try {
    id = anchorIdOf(graph.getNodeData(), graph.getEdgeData())
  } catch {
    return null
  }
  if (!id) return null
  try {
    const [x, y] = graph.getElementPosition(id)
    return Number.isFinite(x) && Number.isFinite(y) ? { id, x, y } : null
  } catch {
    return null
  }
}

/** 渲染后抵消隐式平移(见上方说明);位移小于 0.5px 视为没动(偏移为 0 的正常情况) */
async function compensateAnchor(anchor) {
  if (!anchor || !graph) return
  let after
  try {
    after = graph.getElementPosition(anchor.id)
  } catch {
    return
  }
  if (!after) return
  const dx = after[0] - anchor.x
  const dy = after[1] - anchor.y
  if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return
  // translateBy 是屏幕像素口径(G6 内部 delta = -translate / zoom 作用到相机上),故世界位移要乘 zoom
  await graph.translateBy([-dx * graph.getZoom(), -dy * graph.getZoom()], false)
}

// ---------- Shift 框选/多选(selectable 开启时的底座基础能力,见文件头 props 说明) ----------
/** 主题主色(框选矩形配色):跟随 Element Plus CSS 变量,取不到回退 #409eff;建图期取一次 */
function primaryColor() {
  return getComputedStyle(document.documentElement).getPropertyValue('--el-color-primary').trim() || '#409eff'
}

/** 选择能力的两个 behavior:框选(brush-select,矩形) + 点击多选(click-select)。
 *  只选节点(边/组合不纳入——三处画布都是表节点图,选边没有业务场景;需要边选中的调用方不走
 *  selectable、自配 behaviors 即可);选中集合变化统一经 emitSelectionChange 读模型全量上报 */
function selectionBehaviors() {
  const primary = primaryColor()
  return [
    {
      // Shift+拖动矩形框选:mode=union 与 Shift+点击同为「加选」语义——连续框选/点选累加,
      // 且不覆盖元素既有其他状态(如调用方的 highlight);trigger 默认 ['shift'],
      // 底座 drag-canvas 已对 Shift 让位(调用方自配 drag-element 需同样让位);
      // onSelect 在 setElementState 之前触发,模型更新在同一同步段内完成,微任务后读模型即为最新选中;
      // lastBrushAt 记框选结束时刻:松手后浏览器补发的那次 click 由容器捕获阶段吞掉(见 onContainerClickCapture)
      type: 'brush-select',
      trigger: ['shift'],
      enableElements: ['node'],
      mode: 'union',
      state: 'selected',
      animation: false,
      style: { lineWidth: 2, lineDash: [4, 4], stroke: primary, fill: primary, fillOpacity: 0.12, zIndex: 2, pointerEvents: 'none' },
      onSelect: () => {
        lastBrushAt = Date.now()
        queueMicrotask(emitSelectionChange)
      }
    },
    {
      // Shift+点击多选:multiple + trigger(['shift'] 默认) → 按住 Shift 点击节点切换其选中态;
      // enable 收口:节点仅 Shift+点击进选中(普通点击留给 node-click 等既有交互,不产生选中),
      // 画布空白点击(任意修饰键)清空选中——与 brush-select 自带的空白清除同口径,事件在此统一发;
      // onClick 在状态更新 await 完成后触发,直接读模型即可
      type: 'click-select',
      multiple: true,
      state: 'selected',
      animation: false,
      enable: (e) => e.targetType === 'canvas' || (!!e.shiftKey && e.targetType === 'node'),
      onClick: () => emitSelectionChange()
    }
  ]
}

/** 选中集合变化上报:当前模型里带 selected 状态的节点 id 全量(选中后做什么由调用方决定) */
function emitSelectionChange() {
  if (!graph) return
  emit('selection-change', graph.getElementDataByState('node', 'selected').map((d) => d.id))
}

/** 程序化设置选中(替换语义):调用方把自有交互(如普通点击单选)接入同一选中状态时用,
 *  保证 selection-change 上报的集合始终完整;只在选中集合实际变化时发 selection-change
 *  (调用方常在 rendered 后用它把自身选中集回灌底座——重建会清掉模型里的选中态,无条件发事件会循环) */
function setSelection(ids = []) {
  if (!graph) return
  const want = new Set(ids)
  const states = {}
  graph.getNodeData().forEach((n) => {
    const cur = n.states || []
    const has = cur.includes('selected')
    if (want.has(n.id) && !has) states[n.id] = [...cur, 'selected']
    else if (!want.has(n.id) && has) states[n.id] = cur.filter((s) => s !== 'selected')
  })
  if (!Object.keys(states).length) return
  graph.setElementState(states, false)
  emitSelectionChange()
}

/** 清空当前选中(调用方消费完选中结果后复位;选中集合变化同样发 selection-change) */
function clearSelection() {
  if (!graph) return
  const selected = graph.getElementDataByState('node', 'selected')
  if (!selected.length) return
  const states = {}
  selected.forEach((d) => { states[d.id] = (d.states || []).filter((s) => s !== 'selected') })
  graph.setElementState(states, false)
  emitSelectionChange()
}

// 框选松手后浏览器会补发一次 click(同一元素上 mousedown/mouseup 即使位置不同也会派发 click,
// 落在画布或节点上):不吞掉的话,brush-select 的 clearStates 与 click-select 的清空都挂在 canvas:click 上,
// 刚框出的选中会被当场清掉;落在节点上还会误触 node-click。容器捕获阶段按时间窗吞掉这次 click
// (此前各业务侧靠 300ms 时间戳忽略绕过同一问题,现收口到底座;本监听随底座 onMounted 注册,
//  早于调用方 mounted 里在同一容器上的捕获委托,stopImmediatePropagation 一并挡掉那些委托)
let lastBrushAt = 0
function onContainerClickCapture(ev) {
  if (Date.now() - lastBrushAt >= 300) return
  ev.stopImmediatePropagation()
  ev.preventDefault()
}

async function init() {
  const o = props.options || {}
  graph = new Graph({
    container: containerRef.value,
    autoResize: true,
    // 画布本体透明:背景由容器 CSS 变量 var(--el-bg-color) 提供(见 .bgc-canvas),
    // 亮/暗主题切换即时跟随——g-canvas 的 background 是在清屏时 fillStyle 画进像素的,改不了也认不了 var()
    background: 'transparent',
    ...o,
    data: props.data,
    // 滚轮=平移画布(ctrl/alt 让位);ctrl+滚轮 / 触摸板捏合 / 触屏双指=缩放;左键拖动=拖画布
    behaviors: [
      // 左键拖动平移画布;Shift+拖动让位给框选(底座 selectable 内置的 brush-select,
      // 或业务侧自配的 lasso-select/brush-select,均以 Shift 为 trigger),
      // 否则 Shift+拖动会同时平移画布,框选轨迹跟着画布一起飘。
      // 必须保留 targetType==='canvas' 守卫(G6 默认 enable 自带,覆盖时不能丢):G6 总线会把节点拖拽
      // 冒泡成裸 dragstart 同步转发,丢了守卫拖节点会连带平移画布
      { type: 'drag-canvas', enable: (e) => !e.shiftKey && (!('targetType' in e) || e.targetType === 'canvas') },
      // 触摸板捏合被浏览器合成为 ctrlKey=true 的 wheel 事件,此处让位给缩放行为;
      // alt+滚轮由容器捕获阶段接管为水平平移(见 onAltWheel),此处同步排除
      { type: 'scroll-canvas', enable: (e) => !e.ctrlKey && !e.altKey },
      // 物理按住 Ctrl + 滚轮:Shortcut 按「真实键盘按下的修饰键」严格匹配,须保留 trigger 写法
      { type: 'zoom-canvas', trigger: ['Control'] },
      // 触摸板捏合缩放:合成的 wheel 只有 ctrlKey=true 而无 Control keydown,
      // trigger 快捷键匹配不到,须走 enable 回调判定;trigger 留空(纯 wheel)+仅无物理修饰键时触发,与上一条互斥不重复缩放
      { type: 'zoom-canvas', enable: (e) => !!e.ctrlKey },
      // 触屏双指捏合(PinchHandler 由 pointerType=touch 的指针事件合成,触控板不走此通道)
      { type: 'zoom-canvas', trigger: ['pinch'] },
      // Shift 框选/多选(selectable 开启;置于业务 behaviors 之前,业务侧勿再自配框选/点选)
      ...(props.selectable ? selectionBehaviors() : []),
      ...(o.behaviors || [])
    ],
    plugins: [...minimapPlugins(), ...(o.plugins || [])],
    autoFit: false, // 视口定位显式收敛到 settleView()
  })
  graph.on('node:click', (e) => emit('node-click', e.target.id, graph.getNodeData(e.target.id), e))
  graph.on('edge:click', (e) => emit('edge-click', e.target.id, graph.getEdgeData(e.target.id), e))
  graph.on('canvas:click', () => emit('canvas-click'))
  await graph.render()
  emit('rendered')
  await settleView()
  // 视口变换(滚轮/捏合/控制条缩放、fitView 等)后同步比例显示;
  // 须待首次渲染完成后绑定——渲染期间 ViewportController 尚未就绪,aftertransform 里调 getZoom 会抛错
  graph.on('aftertransform', updateZoomDisplay)
  updateZoomDisplay()
  viewReady.value = true // 视口定位完成,显示画布
  emit('ready', graph)
}

/** 保持视口的渲染必须关动画:开着动画时,树布局的隐式重新锚定会随动画播放(整树飘向新锚点),
 *  而锚点补偿只能等渲染结束后反向拉相机——用户看到的就是「整图先飘过去再被拽回来」的两段式移动;
 *  关掉动画让重锚定与补偿在同一帧完成,视觉上是原地更新。仅本次渲染生效,渲染完恢复 */
async function renderNoAnimation() {
  graph.setOptions({ animation: false })
  try {
    await graph.render()
  } finally {
    graph.setOptions({ animation: true })
  }
}

/** 重建/重绘的渲染入口:动画开关由调用方经 animated prop 决定——
 *  开:正常动画渲染(力导向活体仿真必须开:关动画路径会 layout.stop() 且不挂 onTick,
 *      仿真死后 drag-element-force 拖拽回温画面不更新,表现为「节点拖不动/没有动画」);
 *  关:renderNoAnimation 瞬时渲染(树布局配合锚点补偿原地更新不飘移);
 *  重新 render 前把当前图数据打到控制台(排查布局/数据问题用) */
async function renderForRebuild() {
  console.log('[BaseGraphCanvas] 重新 render,图数据:', graph.getData())
  if (props.animated) await graph.render()
  else await renderNoAnimation()
}

/** 工具栏「重绘」:只做一件事——按当前数据重画一遍(graph.render()),
 *  不重拉数据、不动缩放比例与视口位置;典型场景:从小比例放大后 HTML 节点字体发虚,重绘一次恢复清晰。
 *  仍走 render(会重跑布局),故同样做锚点补偿——不补偿的话树布局会借机把整棵树重新锚定,重绘一下图就跑了 */
async function redraw() {
  if (!graph || recreateQueued) return
  opChain = opChain.then(async () => {
    if (!graph || recreateQueued) return
    const anchor = snapshotAnchor()
    await renderForRebuild()
    emit('rendered')
    await compensateAnchor(anchor)
  })
  await opChain
}

/** 整体重建:数据变化时调用(局部更新请经 getGraph() 自行 update+render);
 *  settle 默认取 refitOnDataChange,手动调用可显式传 false 保持视口(如收起/展开等就地重建);
 *  保持视口(settle=false)时做锚点补偿,避免树布局的隐式重新锚定把整张图挪走。
 *  重建渲染是否开动画由调用方 animated prop 决定(renderForRebuild);关动画时:settle=false 让重锚定与锚点补偿同帧完成(原地更新不飘移);
 *  settle=true(整树重拉/切换选中目录等内容整体替换)时,元素退出动画会让旧内容淡出叠在新图上形成残影,
 *  内容既已整体换掉,过渡动画没有意义,瞬时替换 + settleView 重新定位即可 */
async function refresh(nextData, settle = props.refitOnDataChange) {
  // recreate 排队/进行中时跳过:销毁重建的 init 会用最新 props.data 整体重渲,此处重建是冗余且危险的
  // (render 与 destroy 交错会让 G6 内部状态悬空,报 reading 'draw' 之类的错)
  if (!graph || recreateQueued) return
  opChain = opChain.then(async () => {
    if (!graph || recreateQueued) return
    // transforms 可能随业务档位变化(如仅表名档才需要平行边 bundle):每次重建按当前 options 重放
    if (props.options?.transforms) graph.setTransforms(props.options.transforms)
    const anchor = settle ? null : snapshotAnchor()
    if (nextData) graph.setData(nextData)
    await renderForRebuild()
    emit('rendered')
    // settle=true 会重新定位视口(fitView 覆盖一切),不需要也不该补偿
    if (settle) await settleView()
    else await compensateAnchor(anchor)
  })
  await opChain
}

/** 只重绘不重排:换数据(setData)后走 graph.draw(),重算元素样式(html 节点 innerHTML 随之刷新)但不跑布局。
 *  不跑布局就不会经过 G6 树布局那层隐式重新锚定(见上方「锚点补偿」说明),连补偿都省了,是「只改渲染口径」的最优解:
 *  只影响渲染口径、不影响布局输入(节点尺寸 w/h 与节点集合)的刷新走这里(如对象图「显示类型」「优先字段英文名」);
 *  节点尺寸/结构会变的刷新必须走 refresh()(需重排;保持视口由 refresh 的锚点补偿兜底),否则不重排会重叠、新增节点落原点。
 *  安全性:setData 对 style 是浅合并(mergeElementsData),新数据不带 x/y 不会丢位置;draw 的样式求值与 render 同口径。 */
async function repaint(nextData) {
  if (!graph || recreateQueued) return
  opChain = opChain.then(async () => {
    if (!graph || recreateQueued) return
    if (nextData) graph.setData(nextData)
    await graph.draw()
    emit('rendered')
  })
  await opChain
}

/** 销毁重建(建图期配置如布局方向/边类型变化时用,经 optionsKey 触发或手动调用) */
async function recreate() {
  recreateQueued = true
  // 串行化:排在进行中的 refresh 之后执行,避免 destroy 与 render 交错
  opChain = opChain.then(async () => {
    graph?.destroy()
    graph = null
    viewReady.value = false
    await nextTick()
    await init()
    recreateQueued = false
  })
  await opChain
}

/** 就地重配(node/edge/layout/transforms 按当前 options 重放)并重新渲染,不销毁重建:
 *  布局方向切换走这里——销毁重建会整段白屏(首帧隐藏)+ 画布尺寸抖动,就地重配无白屏且布局带过渡动画
 *  (树布局动画为聚拢-展开);动画配置仅本次渲染生效,渲染完恢复 options 原值,不影响后续收起/展开重建 */
async function reconfigure() {
  if (!graph) return
  recreateQueued = true // 复用互斥标记:期间 data watch 触发的 refresh 跳过(setOptions 已按最新 options 重放)
  opChain = opChain.then(async () => {
    if (!graph) return
    const o = props.options || {}
    const next = {}
    if (o.node) next.node = o.node
    if (o.edge) next.edge = o.edge
    if (o.transforms) next.transforms = o.transforms
    if (o.layout) next.layout = { ...o.layout, animation: true }
    graph.setOptions(next)
    await graph.render()
    emit('rendered')
    if (o.layout) graph.setOptions({ layout: o.layout })
    await settleView()
  }).finally(() => { recreateQueued = false })
  await opChain
}

// ---------- Alt+滚轮水平平移:容器捕获阶段拦截,优先于 scroll-canvas 与 html 节点 wheel 转发 ----------
function onAltWheel(ev) {
  if (!ev.altKey || ev.ctrlKey) return
  ev.preventDefault()
  ev.stopPropagation()
  if (!graph) return
  // 内容随滚动方向水平移动(位移取反,与 scroll-canvas 同口径):deltaY 映射为横向位移
  const d = ev.deltaY || ev.deltaX || 0
  graph.translateBy([-d, 0], false)
}

// ---------- 节点双击:容器层统一兜底 ----------
// G6 v5 不产生 dblclick:html 节点只向 g 事件总线转发 click/pointer 系事件(见 g6 elements/nodes/html.js
// events 列表),原生节点(圆/矩形)的 node:dblclick 枚举虽在但底层 g-lite 不派发——两条路都不通。
// 统一在容器层监听原生 dblclick,按世界坐标反查命中的节点补发 node-dblclick(html 节点与原生节点同口径)。
function onContainerDblclick(ev) {
  if (!graph) return
  const [x, y] = graph.getCanvasByClient([ev.clientX, ev.clientY])
  for (const node of graph.getNodeData()) {
    const b = graph.getElementRenderBounds(node.id)
    if (b && x >= b.min[0] && x <= b.max[0] && y >= b.min[1] && y <= b.max[1]) {
      emit('node-dblclick', node.id, node, ev)
      return
    }
  }
}

// ---------- 缩放控制条(顶部工具栏右侧):± 步进缩放 + 比例输入 + 全屏 ----------
const zoomInput = ref('100')
const zoomEditing = ref(false) // 用户正在输入比例时,aftertransform 不回写覆盖

function updateZoomDisplay() {
  if (graph && !zoomEditing.value) zoomInput.value = String(Math.round(graph.getZoom() * 100))
}

/** 缩放中心:画布视口中心 */
function zoomCenter() {
  return [containerRef.value.clientWidth / 2, containerRef.value.clientHeight / 2]
}

function clampZoom(z) {
  return Math.min(props.maxZoom, Math.max(props.minZoom, z))
}

/** ± 步进缩放:以画布中心为原点,步进 ×1.2 */
function zoomBy(dir) {
  if (!graph || !containerRef.value) return
  graph.zoomTo(clampZoom(graph.getZoom() * (dir > 0 ? 1.2 : 1 / 1.2)), undefined, zoomCenter())
}

/** 缩放/归一到指定比例(绕视口中心) */
function zoomTo(z) {
  if (!graph || !containerRef.value) return
  graph.zoomTo(clampZoom(z), undefined, zoomCenter())
}

/** 归一到 100%(工具栏「1:1」同口径) */
function zoomTo100() {
  zoomTo(1)
}

function getZoom() {
  return graph ? graph.getZoom() : 1
}

/** 比例输入提交(回车/失焦):百分比数字,容忍「80%」写法,范围 clamp 到 [minZoom, maxZoom] */
function onZoomBlur() {
  zoomEditing.value = false
  if (!graph || !containerRef.value) return
  const n = parseFloat(zoomInput.value)
  if (!Number.isFinite(n)) {
    zoomInput.value = String(Math.round(graph.getZoom() * 100))
    return
  }
  const pct = Math.round(clampZoom(n / 100) * 100)
  graph.zoomTo(pct / 100, undefined, zoomCenter())
  zoomInput.value = String(pct)
}

/** Esc:放弃输入,恢复当前比例 */
function onZoomCancel(e) {
  zoomEditing.value = false
  if (graph) zoomInput.value = String(Math.round(graph.getZoom() * 100))
  e.target.blur()
}

const isFullscreen = ref(false)
function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen()
  else wrapRef.value?.requestFullscreen()
}
function onFullscreenChange() {
  isFullscreen.value = document.fullscreenElement === wrapRef.value
}

async function fitView() {
  if (!graph) return
  await graph.fitView()
}

async function fitCenter() {
  if (!graph) return
  await graph.fitCenter()
}

// 数据变化:整体重建(布局/静态坐标重算,按 refitOnDataChange 决定是否重新定位视口)
watch(() => props.data, () => refresh(props.data), { deep: true })

// optionsKey 变化:销毁重建(边类型/布局方向等建图期配置变化)
watch(() => props.optionsKey, recreate)

onMounted(async () => {
  document.addEventListener('fullscreenchange', onFullscreenChange)
  await nextTick()
  await init()
  // html 节点 wheel 转发(bubble 阶段,canvas 目标不接管)+ Alt+滚轮水平平移(capture 阶段,统一拦截)
  unbindNodeWheel = bindHtmlNodeWheel(containerRef.value, () => graph, { minZoom: props.minZoom, maxZoom: props.maxZoom })
  containerRef.value.addEventListener('wheel', onAltWheel, { capture: true, passive: false })
  unbindAltWheel = () => containerRef.value?.removeEventListener('wheel', onAltWheel, { capture: true })
  // 节点双击补发(G6 v5 不产生 dblclick,容器层坐标反查,html/原生节点同口径)
  containerRef.value.addEventListener('dblclick', onContainerDblclick)
  unbindDblclick = () => containerRef.value?.removeEventListener('dblclick', onContainerDblclick)
  // selectable:框选松手后浏览器补发的 click 在捕获阶段吞掉(见 onContainerClickCapture)
  if (props.selectable) containerRef.value.addEventListener('click', onContainerClickCapture, true)
  unbindSelectClick = () => containerRef.value?.removeEventListener('click', onContainerClickCapture, true)
  // 容器尺寸同步:G6 autoResize 只监听 window resize,页签 display:none ↔ 恢复、侧栏拖拽等容器尺寸变化
  // 不会触发;隐藏期间若发生 window resize,G6 会把画布改成最小尺寸,切回页签后画布就缩成一小块回不来了。
  // 这里用 ResizeObserver 盯住容器:尺寸为 0(页签隐藏)时跳过并记 hiddenStale——隐藏期间的 window resize /
  // 重建 fitView 都发生在不可信的画布尺寸上;恢复非零且与画布尺寸不一致时同步尺寸,若经历过隐藏则重新定位视口
  // (RO 观察的是 CSS 定尺寸的容器 div,内层 canvas 改像素不会回灌,无循环)
  let hiddenStale = false
  resizeObserver = new ResizeObserver((entries) => {
    if (!graph || recreateQueued) return
    const { width, height } = entries[0].contentRect
    const w = Math.round(width)
    const h = Math.round(height)
    if (!w || !h) {
      hiddenStale = true
      return
    }
    const [cw, ch] = graph.getSize()
    if (cw === w && ch === h) {
      hiddenStale = false
      return
    }
    graph.resize(w, h)
    if (hiddenStale) {
      hiddenStale = false
      settleView()
    }
  })
  resizeObserver.observe(containerRef.value)
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  unbindNodeWheel?.()
  unbindNodeWheel = null
  unbindAltWheel?.()
  unbindAltWheel = null
  unbindDblclick?.()
  unbindDblclick = null
  unbindSelectClick?.()
  unbindSelectClick = null
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  graph?.destroy()
  graph = null
})

defineExpose({
  getGraph: () => graph,
  getContainer: () => containerRef.value,
  refresh,
  repaint,
  reconfigure,
  recreate,
  redraw,
  settleView,
  clearSelection,
  setSelection,
  fitView,
  fitCenter,
  zoomTo,
  zoomBy,
  zoomTo100,
  getZoom
})
</script>

<style scoped>
.bgc-wrap {
  position: relative;
  width: 100%;
  height: 100%;
}
.bgc-canvas {
  width: 100%;
  height: 100%;
  /* 画布背景:G6 画布本体透明(init 传 background:'transparent'),背景由这里提供,
     亮/暗主题切换随 CSS 变量即时生效,无需重建 */
  background: var(--el-bg-color);
  /* 画布是拖拽操作面:禁文本选择——否则 Shift+拖动套索 / Shift+点击多选会把 html 节点里的
     表名/字段文字整片框选高亮(G6 画出来的图形文字天然不可选,只有 ER 的 html 节点 DOM 文字会中招);
     工具栏/缩放条/鸟瞰图是 .bgc-canvas 的兄弟节点,不受影响 */
  user-select: none;
  -webkit-user-select: none;
}
/* 视口定位完成前隐藏画布内容:render 首帧按世界坐标落笔,直接可见会闪在左上角 */
.bgc-canvas-hidden {
  opacity: 0;
}
/* 全屏态:铺满屏幕,背景与画布同色避免边缘露底 */
.bgc-wrap:fullscreen {
  background: var(--el-bg-color);
}
/* 顶部工具栏:左 = 内置默认工具 + toolbar 插槽业务工具,右 = 缩放控制条(margin-left:auto 推到最右);
   整条 bar 不接收指针事件(空隙处可拖画布),子控件各自恢复 */
.bgc-toolbar {
  position: absolute;
  top: 10px;
  left: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  pointer-events: none;
  z-index: 10;
  /* 小组件统一高度:24px = Element Plus 小号档位(按钮/输入框/单选组/下拉/复选框原生就是 24px),
     缩放控制条按同一数值收口;想整体调高调低只改这一个值 */
  --bgc-ctl-h: 24px;
  /* el 的尺寸令牌同步压低:调用方插槽里漏写 size="small" 的组件也按同一高度渲染,
     不会出现 32px 的默认档组件把这一行撑高(弹出层 teleport 到 body,不受影响) */
  --el-component-size: var(--bgc-ctl-h);
  --el-component-size-small: var(--bgc-ctl-h);
}
.bgc-toolbar > * {
  pointer-events: auto;
}
/* 左侧控件区:毛玻璃底(低透明底色 + 背景模糊),图元素拖到下面不挡组件阅读;
   只包有组件的地方(宽度随内容),工具栏中部留空仍可拖画布 */
.bgc-toolbar-main {
  display: flex;
  align-items: center;
  gap: 8px;
  box-sizing: border-box;
  min-height: calc(var(--bgc-ctl-h) + 8px);
  padding: 4px 8px;
  background: color-mix(in srgb, var(--el-bg-color) 55%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-lighter);
}
/* gap 统一间距,清掉 el-button 相邻默认 margin(与各页面工具栏同写法) */
.bgc-toolbar :deep(.el-button) {
  margin-left: 0;
}
/* 工具栏小组件高度严格一致(第二道保险):各类组件外层元素直接定高,
   覆盖组件内部写死高度(如 el-checkbox 默认 32px、radio-button__inner 的小号 padding 撑高)的场景 */
.bgc-toolbar :deep(.el-button),
.bgc-toolbar :deep(.el-checkbox),
.bgc-toolbar :deep(.el-input),
.bgc-toolbar :deep(.el-input-number),
.bgc-toolbar :deep(.el-select),
.bgc-toolbar :deep(.el-select__wrapper),
.bgc-toolbar :deep(.el-radio-group),
.bgc-toolbar :deep(.el-radio-button__inner) {
  height: var(--bgc-ctl-h);
}
/* 单选组不参与压缩、也不许内部换行:el-radio-group 自带 flex-wrap:wrap,被 flex 挤窄时会把按钮
   折到第二行(外层量出来仍是 24px,内容却溢出行高,视觉上直接破坏「高度严格一致」),这里钉死单行;
   窗口太窄时宁可压缩同排其他控件,单选组保持一行 */
.bgc-toolbar :deep(.el-radio-group) {
  flex-shrink: 0;
  flex-wrap: nowrap;
}
/* 漏写 size="small" 的默认档按钮:内边距/字号同步收到小号口径,免得 24px 高度被大内边距挤变形 */
.bgc-toolbar :deep(.el-button:not(.el-button--small)) {
  padding: 0 11px;
  font-size: 12px;
}
.bgc-toolbar :deep(.el-button.is-circle) {
  width: var(--bgc-ctl-h);
  padding: 0;
}
/* 线形图标单选组:原生小号内边距(5px 11px)是给文字的,图标只要方角小格,收成 0 8px 并居中 */
.bgc-edge-icons :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
}
/* 缩放控制条:固定在工具栏右侧,毛玻璃底同左侧控件区;
   高度与左侧控件区外壳一致(24px 小组件 + 上下各 4px 内边距 = 32px) */
.bgc-zoombar {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
  box-sizing: border-box;
  height: calc(var(--bgc-ctl-h) + 8px);
  padding: 0 6px;
  background: color-mix(in srgb, var(--el-bg-color) 55%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-lighter);
}
.bgc-zb-btn {
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.bgc-zb-btn:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.bgc-zb-input {
  box-sizing: border-box;
  width: 44px;
  height: 22px;
  padding: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font-size: 12px;
  line-height: 1;
  text-align: center;
  outline: none;
}
.bgc-zb-input:focus {
  border-color: var(--el-color-primary);
}
.bgc-zb-pct {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.bgc-zb-divider {
  width: 1px;
  height: 14px;
  background: var(--el-border-color-lighter);
}
/* 左下角图例折叠面板:外壳/折叠交互由底座提供,内容走 legend 插槽 */
.bgc-legend {
  position: absolute;
  left: 12px;
  bottom: 10px;
  z-index: 10;
}
/* 收起态:小条,点开还原 */
.bgc-legend-chip {
  padding: 3px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-lighter);
  cursor: pointer;
}
.bgc-legend-chip:hover {
  color: var(--el-color-primary);
}
.bgc-legend-panel {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-lighter);
  opacity: 0.95;
}
/* 标题栏:整行可点收起 */
.bgc-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  user-select: none;
}
.bgc-legend-head:hover {
  color: var(--el-color-primary);
}
.bgc-legend-caret {
  font-size: 10px;
}
.bgc-legend-body {
  padding: 6px 10px;
}
/* Shift 框选/多选操作提示:鸟瞰图左侧、底边与鸟瞰图对齐(位置见 selectHintStyle);
   半透明毛玻璃弱提示(比工具栏控件区更弱的底色,无边框不抢视觉),不接收指针事件——不挡框选/拖画布 */
.bgc-select-hint {
  position: absolute;
  z-index: 10;
  padding: 3px 10px;
  font-size: 12px;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
  background: color-mix(in srgb, var(--el-bg-color) 45%, transparent);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border-radius: 6px;
  pointer-events: none;
  user-select: none;
}
</style>
