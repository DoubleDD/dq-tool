/**
 * HTML 节点鸟瞰图缩略块工厂(配合 BaseGraphCanvas 的 minimap.shape 使用)。
 *
 * 背景:G6 v5 html 节点的 key 形状是真实 DOM(GHTML),画不进 minimap 的独立小画布,
 * 默认缩略图里 html 节点不可见。这里克隆每个 html 节点自带的等大小透明容器矩形
 * ('key-container')补可见样式,充当鸟瞰图里的缩略块。
 *
 * 几何说明:克隆出的矩形以左上角为原点,先平移半宽高(-w/2, -h/2),
 * 插件随后 setPosition(节点中心) 恰好让缩略块居中。
 *
 * @param {(id: string) => boolean} [highlight] 需突出显示的节点(如锚点表),命中用主题色实心
 * @param {{primary?: string, bg?: string, textSecondary?: string}} [colors]
 *        主题色;不传时每次回调从 Element Plus CSS 变量现读(亮/暗主题自适应)
 * @returns {(id: string, type: string, target: any) => any} G6 minimap 插件的 shape 回调
 */
export function createHtmlMinimapShape(highlight, colors) {
  const read = () => {
    if (colors) return colors
    const cs = getComputedStyle(document.documentElement)
    const get = (k, fb) => cs.getPropertyValue(k).trim() || fb
    return {
      primary: get('--el-color-primary', '#409eff'),
      // 缩略块用正文次级色实心:容器是毛玻璃(低透明底色+模糊),浅填充+浅描边会被透上来的图元素冲没,
      // 实心灰块在缩略尺度上对比度最高、最可读
      textSecondary: get('--el-text-color-secondary', '#909399')
    }
  }
  return (id, type, target) => {
    const c = read()
    const hot = highlight ? highlight(id) : false
    const box = target.getShape('key-container').cloneNode()
    box.style.opacity = 1
    box.style.fill = hot ? c.primary : c.textSecondary
    box.style.stroke = 'transparent'
    box.style.lineWidth = 0
    const [w, h] = target.getSize()
    box.style.x = -w / 2
    box.style.y = -h / 2
    return box
  }
}
