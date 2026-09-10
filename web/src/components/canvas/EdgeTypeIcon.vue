<template>
  <!-- 线形图标:与画布实际线型同口径(curve 贝塞尔曲线 / orth 直角折线 / orth-round 直角折线带倒角)。
       三个图标共用同一组端点((3,18) → (21,6))与描边口径,横排对比时差异只在拐角,一眼可辨;
       颜色走 currentColor,选中态(主题色底白字)与未选中态(常规文字色)都由 el-radio-button 定,图标不用管 -->
  <svg
    class="eti-svg"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="path" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

/** 线形 → SVG 路径;未知值退化为直角折线,避免图标位空白 */
const PATHS = {
  // 曲线:水平出边后 S 形过渡到水平入边(与 field-cubic 的「水平 stub + 贝塞尔」同观感);
  // 控制点同 x(转折最陡)才看得出「曲线」,否则两条控制点拉开会退化成一条斜线
  curve: 'M3 18C12 18 12 6 21 6',
  // 直角:两段直角折线(H-V-H),拐角为尖角
  orth: 'M3 18H12V6H21',
  // 圆角:同上,两处拐角用二次贝塞尔倒角(对应 field-polyline 的 radius)
  'orth-round': 'M3 18H10Q12 18 12 16V8Q12 6 14 6H21'
}

const props = defineProps({
  // 线形:curve / orth / orth-round(与画布 edgeType 取值同口径)
  type: { type: String, default: 'curve' },
  // 图标边长(px):工具栏内与 el-button 的图标尺寸同口径
  size: { type: Number, default: 14 }
})

const path = computed(() => PATHS[props.type] || PATHS.orth)
</script>

<style scoped>
.eti-svg {
  /* 块级元素:图标在按钮内由 flex 居中,去掉行内基线带来的额外留白 */
  display: block;
  flex-shrink: 0;
}
</style>
