/**
 * 画布 HTML 节点滚轮接管。
 *
 * 背景:G6 v5 的 html 节点是叠在 <canvas> 上方的真实 DOM,其上的 wheel 事件两头不到——
 *  1. scroll-canvas 的原生 wheel 监听绑在 <canvas> 元素上,节点 DOM 不是它的子级,收不到;
 *  2. HTML 节点只向 g 事件总线转发 pointer/click 系事件(见 G6 HTML 节点源码 events 列表),不转发 wheel;
 * 表现为「鼠标悬在节点上时,滚轮平移/缩放全部失效」。
 *
 * 方案:在画布容器上监听 wheel(节点 DOM 的滚轮会冒泡到容器),按 behaviors 同口径转发给画布视口:
 *  - ctrlKey(含触摸板捏合合成的 ctrlKey wheel,无物理修饰键)→ 以指针为中心缩放(zoom-canvas 同款算式);
 *  - 普通滚轮 → 按滚动方向平移(scroll-canvas 同款算式);
 *  - 落在 <canvas> 元素上的滚轮不接管(scroll-canvas/zoom-canvas 原生监听已处理,避免重复)。
 *
 * @param {HTMLElement} container G6 画布容器(节点 DOM 与 canvas 的公共父级)
 * @param {() => any} getGraph 惰性取 Graph 实例(组件内 graph 为非响应式变量)
 * @param {{minZoom?: number, maxZoom?: number}} [opts] 缩放范围,默认 [0.2, 4](与 ER 画布视口控制条一致)
 * @returns {() => void} 解绑函数(组件卸载时调用)
 */
export function bindHtmlNodeWheel(container, getGraph, opts = {}) {
  const min = opts.minZoom ?? 0.2
  const max = opts.maxZoom ?? 4
  const onWheel = (ev) => {
    // canvas 上的滚轮已由 scroll-canvas / zoom-canvas 处理(如主画布),这里不再转发;
    // 但仍要 preventDefault,否则悬在鸟瞰图等插件小画布上时会穿透滚动外层页面
    if (!ev.target || ev.target.tagName === 'CANVAS') {
      ev.preventDefault()
      return
    }
    ev.preventDefault()
    const graph = getGraph()
    if (!graph) return
    // 指针位置(容器坐标)作为缩放中心
    const rect = container.getBoundingClientRect()
    const at = [ev.clientX - rect.left, ev.clientY - rect.top]
    const dy = ev.deltaY ?? ev.deltaX ?? 0
    if (ev.ctrlKey) {
      // 与 zoom-canvas 同口径:value ∈ [-50, 50],ratio = 1 + value/100
      const ratio = 1 + Math.max(-50, Math.min(50, -dy)) / 100
      graph.zoomTo(Math.min(max, Math.max(min, graph.getZoom() * ratio)), false, at)
    } else {
      // 与 scroll-canvas 同口径:内容随滚动方向平移(位移取反)
      graph.translateBy([-ev.deltaX, -dy], false)
    }
  }
  container.addEventListener('wheel', onWheel, { passive: false })
  return () => container.removeEventListener('wheel', onWheel)
}
