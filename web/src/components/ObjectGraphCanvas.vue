<template>
  <!-- 对象目录图视图画布:通用画布底座 BaseGraphCanvas 承载交互(滚轮平移/Ctrl+滚轮与触摸板捏合缩放/
       Alt+滚轮水平平移/html 节点滚轮转发与双击补发)、顶部工具栏(默认工具:重绘/1:1/适应画布 + toolbar 插槽
       业务工具[布局方向/字段显示开关等],右侧缩放控制条)、右下角鸟瞰图、视口定位与生命周期;
       本组件只负责目录树专属部分:html 节点渲染、树布局(compact-box,H=LR / V=TB,H/V 可切,
       切换走底座 reconfigure 就地重配,带聚拢-展开过渡动画,不销毁重建)、omg-cubic-h/v 单点起边、收起/展开与字段按需加载。容器需显式高度(由父级布局保证) -->
  <BaseGraphCanvas
    ref="baseRef"
    :data="graphData"
    :options="graphOptions"
    fit="view"
    :minimap="minimapOptions"
    :fullscreen="false"
    :refit-on-data-change="false"
    @node-click="onNodeClick"
    @node-dblclick="onNodeDblclick"
    @rendered="ensureColumns"
  >
    <!-- 业务工具透传:布局方向/字段显示开关等由调用方按需给 -->
    <template #toolbar><slot name="toolbar" /></template>
  </BaseGraphCanvas>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { CubicHorizontal, CubicVertical, ExtensionCategory, register } from '@antv/g6'
import request from '../api'
import BaseGraphCanvas from './canvas/BaseGraphCanvas.vue'
import { createHtmlMinimapShape } from './canvas/htmlMinimapShape'
import { themeState } from '../stores/theme'

// 对象目录图(G6 v5 配置式 API,树布局 compact-box,H=LR / V=TB,两个方向可切换;通用能力见底座 BaseGraphCanvas):
//  根节点:rootDir 非空时 = 选中目录(其挂载表/子目录直接挂根下,对象管理页「关系图」页签口径);
//    rootDir 为空时 = 数据源名称虚拟根 + 整棵目录树(向后兼容);
//  目录节点 = 浅色文件夹风格(无边框图标/徽标),标注挂载表计数,点击节点收起/展开子树;
//  表节点 = 实体样式(白底深色边框),名字口径与字段一致(默认中文注释优先、无注释回退英文名,副标题显示另一个),
//    下方列出字段(主键带 🔑,类型列由 showFieldType 控制,默认不显示),默认每表 maxFields 行、超出折叠成
//    「… 共 N 个字段」可点击展开/收起(展开态跨 rebuild 保持);字段走单表 columns 接口按需拉取(服务端有缓存不连业务库);
//    字段区可由 showFields 关闭;有关系表时右缘 ▸/▾ 按钮收起/展开关系;
//  关系表 = 挂载表的子节点,加「关系」徽标区分;
//  挂载表带 relKind(包含/关联)时,目录→表 连线中点展示该关系标签;
//  关系表带 relKind 时,挂载表→关系表 连线同样展示;
//  双击表/关系表节点跳该表字段明细页(底座容器层坐标反查补发——G6 v5 不转发 html 节点 dblclick);
//  自定义 omg-cubic-h/v 边:起点统一钉在父节点出边侧中点,避免多子节点连线从边框不同点发散
const props = defineProps({
  // 整棵目录树(ObjectDirNode 数组,children 嵌套);rootDir 非空时忽略
  catalog: { type: Array, default: () => [] },
  dsId: { type: [String, Number], required: true },
  // 数据源名称(虚拟根节点标签,rootDir 为空时用)
  dsName: { type: String, default: '' },
  // 指定根目录(ObjectDirNode,可空):非空时以该目录为根节点,只画其子树
  rootDir: { type: Object, default: null },
  // 布局方向:H=水平(从左往右),V=垂直(从上往下)
  direction: { type: String, default: 'H' },
  // 是否在表/关系表节点展示字段区(false 时不拉取字段、节点缩回标题+注释高度)
  showFields: { type: Boolean, default: true },
  // 每表默认显示字段数,超出折叠成「… 共 N 个字段」可点击展开
  maxFields: { type: Number, default: 10 },
  // 字段行是否显示数据类型列(默认不显示)
  showFieldType: { type: Boolean, default: false },
  // 名字口径三档(表名与字段同规则,默认 chinese):chinese=仅中文注释(无注释回退英文名),
  // english=仅英文名,both=中英文同时显示(中文在前,英文名淡色尾随;表名侧以主/副标题双行呈现)
  fieldNameMode: { type: String, default: 'chinese' }
})

const router = useRouter()
const baseRef = ref(null)
// 收起状态(节点 id 集合):跨 rebuild 保持,节点 id 由目录/表/关系主键派生,稳定可复用
const collapsedIds = new Set()
// 字段清单展开状态(节点 id 集合):点击「… 共 N 个字段」展开全部字段,同样跨 rebuild 保持
const expandedFieldIds = new Set()
// 字段缓存:db|schema|table → undefined=未拉取 / null=拉取中 / 数组=已拉取(可为空)
const columnsCache = new Map()

// ---------- 单点起边 omg-cubic-h / omg-cubic-v ----------
// 端点钉死:水平=父节点右缘中点 → 子节点左缘中点;垂直=父节点底缘中点 → 子节点顶缘中点,
// 一个父节点的所有出边从同一点散开(默认边框交点会让每条边起点不同)
function edgeAnchors(self) {
  const [sc, tc] = [self.sourceNode.getCenter(), self.targetNode.getCenter()]
  const g = self.context.graph
  // 收起/展开的重建里,边可能在端点节点已从数据控制器移除后仍被重绘(退场动画期间的瞬时状态),
  // getNodeData 对不存在的节点直接抛错,这里兜底为空对象退化为默认尺寸
  const safeData = (id) => { try { return g.getNodeData(id)?.data || {} } catch { return {} } }
  const sd = safeData(self.sourceNode.id)
  const td = safeData(self.targetNode.id)
  return { sc, tc, sw: sd.w || 180, sh: sd.h || 40, tw: td.w || 180, th: td.h || 40 }
}
class OmgCubicHEdge extends CubicHorizontal {
  getEndpoints() {
    const { sc, tc, sw, tw } = edgeAnchors(this)
    return [[sc[0] + sw / 2, sc[1], 0], [tc[0] - tw / 2, tc[1], 0]]
  }
}
class OmgCubicVEdge extends CubicVertical {
  getEndpoints() {
    const { sc, tc, sh, th } = edgeAnchors(this)
    return [[sc[0], sc[1] + sh / 2, 0], [tc[0], tc[1] - th / 2, 0]]
  }
}
register(ExtensionCategory.EDGE, 'omg-cubic-h', OmgCubicHEdge)
register(ExtensionCategory.EDGE, 'omg-cubic-v', OmgCubicVEdge)

/** 主题色:跟随 Element Plus CSS 变量(亮/暗主题自适应),取不到用兜底值 */
function themeColors() {
  const cs = getComputedStyle(document.documentElement)
  const get = (k, fb) => cs.getPropertyValue(k).trim() || fb
  return {
    primary: get('--el-color-primary', '#409eff'),
    warning: get('--el-color-warning', '#e6a23c'),
    border: get('--el-border-color', '#dcdfe6'),
    borderDarker: get('--el-border-color-darker', '#cdd0d6'),
    bg: get('--el-bg-color', '#ffffff'),
    text: get('--el-text-color-primary', '#303133'),
    textSecondary: get('--el-text-color-secondary', '#909399'),
    fill: get('--el-fill-color-light', '#f5f7fa'),
    warningLight: get('--el-color-warning-light-9', '#fdf6ec')
  }
}

/** HTML 转义(表名/注释可能含 <>& 等字符) */
function esc(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// 节点尺寸常量(与 renderNodeHtml 渲染一致,树布局按此间距防重叠)
const ROOT_SIZE = [170, 40]
const DIR_SIZE = [170, 40]
const TABLE_W = 240
const REL_W = 220
// 字段区:行高;每表默认展示行数由 props.maxFields 控制,超出折叠成「… 共 N 个字段」可点击展开
const FIELD_ROW_H = 16

/** 字段缓存键 */
const colKey = (d) => `${d.dbName || ''}|${d.schemaName}|${d.tableName}`

/** 表/关系表节点高度:基础区(上下 padding 10 + 名字行 16 + 注释行 14)+ 字段区动态撑高(expanded=字段清单已展开) */
function tableHeight(comment, fields, expanded = false) {
  const base = 10 + 16 + (comment ? 14 : 0)
  if (fields == null) return base + 30 // 字段加载中占位行
  if (!fields.length) return base + 10 // 与无字段旧高度一致(36/50)
  const rows = expanded ? fields.length : Math.min(fields.length, props.maxFields)
  return base + 7 + rows * FIELD_ROW_H + (fields.length > props.maxFields ? 14 : 0) + 5
}

/** 目录徽标计数:本目录及全部子目录挂载表总数(与目录视图树节点口径一致) */
function countTables(dir) {
  let n = (dir.tables || []).length
  for (const c of dir.children || []) n += countTables(c)
  return n
}

const ROOT_ID = 'om-root'

// 挂载表与目录的关系类型(relKind)→ 目录→表 连线标签文字
const REL_KIND_TEXT = { INCLUDE: '包含', ASSOC: '关联' }

/** 目录树 -> G6 数据(nodes/edges;父子关系用边表达,树布局按边排布) */
function buildData() {
  const nodes = []
  const edges = []
  // 根节点:rootDir 非空 = 选中目录本身;为空 = 数据源虚拟根(整树模式)
  nodes.push({
    id: ROOT_ID,
    data: { kind: 'root', label: props.rootDir ? props.rootDir.name : (props.dsName || '数据源'), w: ROOT_SIZE[0], h: ROOT_SIZE[1] },
    style: { size: ROOT_SIZE }
  })
  // label 可选:挂载表带 relKind 时,目录→表 连线展示「包含/关联」
  const link = (source, target, label) =>
    edges.push({ id: `e-${source}-${target}`, source, target, data: { label: label || '' } })
  /** 目录直接挂载的表(及其关系表)挂到 id 节点下 */
  const walkTables = (dir, id) => {
    for (const t of dir.tables || []) {
      const tid = `table-${t.id}`
      const relCount = (t.relations || []).length
      const tCollapsed = collapsedIds.has(tid) && relCount > 0
      const fields = props.showFields ? (columnsCache.get(colKey(t)) ?? null) : []
      const h = tableHeight(t.comment, fields, expandedFieldIds.has(tid))
      nodes.push({
        id: tid,
        data: {
          kind: 'table', label: t.tableName, comment: t.comment || '', w: TABLE_W, h,
          relCount, collapsed: tCollapsed, fields,
          dbName: t.dbName || '', schemaName: t.schemaName, tableName: t.tableName
        },
        style: { size: [TABLE_W, h] }
      })
      link(id, tid, REL_KIND_TEXT[t.relKind])
      if (tCollapsed) continue
      for (const r of t.relations || []) {
        const rid = `rel-${r.id}`
        const rFields = props.showFields ? (columnsCache.get(colKey(r)) ?? null) : []
        const rh = tableHeight(r.comment, rFields, expandedFieldIds.has(rid))
        nodes.push({
          id: rid,
          data: {
            kind: 'rel', label: r.tableName, comment: r.comment || '', w: REL_W, h: rh, fields: rFields,
            dbName: r.dbName || '', schemaName: r.schemaName, tableName: r.tableName
          },
          style: { size: [REL_W, rh] }
        })
        link(tid, rid, REL_KIND_TEXT[r.relKind])
      }
    }
  }
  const walk = (dir, parentId) => {
    const id = `dir-${dir.id}`
    const childCount = (dir.tables || []).length + (dir.children || []).length
    const collapsed = collapsedIds.has(id) && childCount > 0
    nodes.push({
      id,
      data: { kind: 'dir', label: dir.name, count: countTables(dir), childCount, collapsed, w: DIR_SIZE[0], h: DIR_SIZE[1] },
      style: { size: DIR_SIZE }
    })
    link(parentId, id)
    if (collapsed) return
    walkTables(dir, id)
    for (const c of dir.children || []) walk(c, id)
  }
  if (props.rootDir) {
    // 选中目录为根:其挂载表与子目录直接挂根下
    walkTables(props.rootDir, ROOT_ID)
    for (const c of props.rootDir.children || []) walk(c, ROOT_ID)
  } else {
    for (const d of props.catalog) walk(d, ROOT_ID)
  }
  return { nodes, edges }
}

/** 节点 HTML:root=主题色实心;dir=浅色文件夹风格(纯文字,无图标/徽标)+ 挂载表计数 + 收起/展开箭头;table=实体样式(字段清单,有关系时右缘 ▸/▾ 收起/展开按钮);rel=实体样式 + 「关系」徽标 */
function renderNodeHtml(d) {
  const c = themeColors()
  const { kind, label, comment, count, childCount, relCount, collapsed, fields, w, h } = d.data
  if (kind === 'root') {
    return `<div style="width:${w}px;height:${h}px;box-sizing:border-box;background:${c.primary};border-radius:6px;color:#fff;font-size:13px;font-weight:600;display:flex;align-items:center;justify-content:center;padding:0 10px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${esc(label)}</div>`
  }
  if (kind === 'dir') {
    const chevron = childCount > 0
      ? `<span data-omg-toggle style="flex-shrink:0;font-size:11px;color:${c.textSecondary};line-height:14px" title="${collapsed ? '展开' : '收起'}">${collapsed ? `▸ ${childCount}` : '▾'}</span>` : ''
    return `<div title="${childCount > 0 ? (collapsed ? '点击展开' : '点击收起') : ''}" style="width:${w}px;height:${h}px;box-sizing:border-box;background:${c.warningLight};border:1px solid ${c.warning};border-radius:6px;display:flex;align-items:center;gap:6px;padding:0 10px;overflow:hidden;${childCount > 0 ? 'cursor:pointer;' : ''}">
  <span style="flex:1;min-width:0;font-size:13px;font-weight:600;color:${c.text};overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${esc(label)}</span>
  <span style="flex-shrink:0;font-size:11px;color:${c.textSecondary}">${count ?? 0} 表</span>
  ${chevron}
</div>`
  }
  const isRel = kind === 'rel'
  const badge = isRel
    ? `<span style="flex-shrink:0;font-size:10px;color:${c.warning};border:1px solid ${c.warning};border-radius:3px;padding:0 3px;line-height:13px;margin-left:6px">关系</span>` : ''
  // 关系表收起/展开按钮:仅表节点且有挂载关系时出现;事件在 node-click 里按 data-omg-toggle 区分于跳转
  const toggle = (!isRel && relCount > 0)
    ? `<span data-omg-toggle title="${collapsed ? '展开关系表' : '收起关系表'}" style="flex-shrink:0;font-size:11px;color:${c.textSecondary};line-height:13px;margin-left:6px;cursor:pointer;padding:0 2px">${collapsed ? `▸ ${relCount}` : '▾'}</span>` : ''
  // 名字口径与字段一致:中文档(含 both)中文注释优先、无注释回退英文名;仅英文名档则反过来;副标题显示另一个
  const primary = props.fieldNameMode === 'english' ? label : (comment || label)
  const secondary = comment ? (primary === comment ? label : comment) : ''
  const commentHtml = secondary
    ? `<div style="height:14px;line-height:14px;font-size:11px;color:${c.textSecondary};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(secondary)}</div>` : ''
  // 字段区:null=拉取中;空数组=无字段不渲染;超出 maxFields 折叠成统计行,点击展开/收起全部字段
  let fieldsHtml = ''
  if (fields == null) {
    fieldsHtml = `<div style="border-top:1px dashed ${c.border};margin-top:4px;padding-top:3px;height:${FIELD_ROW_H}px;line-height:${FIELD_ROW_H}px;font-size:11px;color:${c.textSecondary}">字段加载中…</div>`
  } else if (fields.length) {
    const expanded = expandedFieldIds.has(d.id)
    const shown = expanded ? fields : fields.slice(0, props.maxFields)
    const rows = shown.map((f) => {
      // 字段名口径三档:chinese=仅中文注释(无注释回退英文名);english=仅英文名;both=中文在前、英文名更淡尾随;
      // tooltip 始终给完整信息
      const text = props.fieldNameMode === 'english' ? f.name : (f.comment || f.name)
      const tailHtml = props.fieldNameMode === 'both' && f.comment
        ? `<span style="margin-left:4px;color:${c.textSecondary}">${esc(f.name)}</span>` : ''
      const tip = [f.name, f.comment, f.type].filter(Boolean).join(' ')
      const typeHtml = props.showFieldType
        ? `<span style="flex-shrink:0;max-width:45%;color:${c.textSecondary};margin-left:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(f.type)}</span>` : ''
      return `<div title="${esc(tip)}" style="display:flex;align-items:center;height:${FIELD_ROW_H}px;line-height:${FIELD_ROW_H}px;font-size:11px">
      <span style="flex:1;min-width:0;color:${c.text};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${f.pk ? '🔑 ' : ''}${esc(text)}${tailHtml}</span>
      ${typeHtml}
    </div>`
    }).join('')
    const more = fields.length > props.maxFields
      ? `<div data-omg-fields title="${expanded ? '收起字段' : '展开全部字段'}" style="cursor:pointer;height:14px;line-height:14px;font-size:11px;color:${c.primary}">${expanded ? '▴ 收起字段' : `▾ … 共 ${fields.length} 个字段`}</div>` : ''
    fieldsHtml = `<div style="border-top:1px dashed ${c.border};margin-top:4px;padding-top:3px">${rows}${more}</div>`
  }
  return `<div title="双击打开字段明细" style="width:${w}px;height:${h}px;box-sizing:border-box;background:${c.bg};border:1px solid ${c.borderDarker};border-radius:6px;padding:5px 8px;overflow:hidden;cursor:pointer;display:flex;flex-direction:column;justify-content:center">
  <div style="display:flex;align-items:center;height:16px;line-height:16px">
    <span style="flex:1;min-width:0;font-size:12px;font-weight:600;color:${c.text};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(primary)}</span>${badge}${toggle}
  </div>
  ${commentHtml}
  ${fieldsHtml}
</div>`
}

// ---------- 字段按需加载 ----------
let ensuring = false
let ensureQueued = false

/** 当前可见表/关系表节点中尚未拉取字段的表(按 colKey 去重) */
function missingColumns(data) {
  const seen = new Map()
  for (const n of data.nodes) {
    const d = n.data
    if ((d.kind === 'table' || d.kind === 'rel') && d.schemaName) {
      const k = colKey(d)
      if (!columnsCache.has(k) && !seen.has(k)) seen.set(k, d)
    }
  }
  return [...seen.entries()]
}

/** 按需拉取可见表字段(复用字段明细页的单表 columns 接口,服务端有 meta 缓存,命中不连业务库);
 *  拉到后重建刷新节点(保持视口 + 底座锚点补偿,字段撑高节点不会把整张图挪走) */
async function ensureColumns() {
  if (!props.showFields) return // 字段显示关闭:不拉取,节点按无字段渲染
  if (ensuring) {
    ensureQueued = true
    return
  }
  ensuring = true
  try {
    do {
      ensureQueued = false
      const missing = missingColumns(buildData())
      if (!missing.length) break
      for (const [k] of missing) columnsCache.set(k, null)
      const CHUNK = 5 // 限流:目录树表多时避免一次性打满连接
      for (let i = 0; i < missing.length; i += CHUNK) {
        await Promise.all(missing.slice(i, i + CHUNK).map(async ([k, d]) => {
          const q = d.dbName ? `?db=${encodeURIComponent(d.dbName)}` : ''
          const list = await request
            .get(`/datasources/${props.dsId}/schemas/${encodeURIComponent(d.schemaName)}/tables/${encodeURIComponent(d.tableName)}/columns${q}`)
            .catch(() => [])
          columnsCache.set(k, (list || []).map((col) => ({
            name: col.name, type: col.displayType || col.typeName || '', pk: !!col.primaryKey, comment: col.comment || ''
          })))
        }))
      }
      if (!baseRef.value) return
      await baseRef.value.refresh(buildData(), false)
    } while (ensureQueued)
  } finally {
    ensuring = false
  }
}

/** 收起/展开切换:重建数据并重新布局(保持当前视口,不重新 fitView;根节点屏幕位置由底座锚点补偿钉住);
 *  新露出的表按需补拉字段 */
async function toggleCollapse(id) {
  if (collapsedIds.has(id)) collapsedIds.delete(id)
  else collapsedIds.add(id)
  if (!baseRef.value) return
  await baseRef.value.refresh(buildData(), false)
  ensureColumns()
}

/** 字段清单展开/收起:节点高度变化需重新布局(保持当前视口,同上) */
async function toggleFieldsExpand(id) {
  if (expandedFieldIds.has(id)) expandedFieldIds.delete(id)
  else expandedFieldIds.add(id)
  if (!baseRef.value) return
  await baseRef.value.refresh(buildData(), false)
}

// ---------- 底座输入 ----------
// 数据:目录树/影响节点尺寸的显示口径(显示字段、字段数)变化 → computed 重算 → 底座整体重建,但 refitOnDataChange=false
//   → 保持当前视口(不重新 fitView),由底座的锚点补偿保证「根节点屏幕位置不动」,不再出现「第一次重渲染整张图跳一下」;
//   只改 HTML 渲染口径的两个开关走下面的 repaint(连布局都不跑);触碰 themeState.dark:亮/暗主题切换时重算,
//   底座整体重建换色(节点色经 innerHTML 函数、边色经样式函数,重建即刷新),同样不动视口
const graphData = computed(() => {
  void themeState.dark
  return buildData()
})
// 布局方向切换:不销毁重建(整段白屏+画布尺寸抖动),走底座 reconfigure 就地重配 layout/edge 类型并带过渡动画;
// 收起状态、字段展开态与字段缓存天然保留(不重建组件)
const graphOptions = computed(() => ({
  node: {
    type: 'html',
    style: {
      size: (d) => d.style?.size || [180, 40],
      innerHTML: (d) => renderNodeHtml(d)
    }
  },
  edge: {
    type: props.direction === 'V' ? 'omg-cubic-v' : 'omg-cubic-h',
    // 关系标签(包含/关联)与 ER 图连线标签同风格:小字 + 底色背景压线;
    // 颜色用样式函数(每次渲染求值)——主题切换重建时换色生效,静态对象则因底座 refresh 不重放 options 而滞留旧色
    style: {
      stroke: () => themeColors().borderDarker,
      lineWidth: 1.2,
      labelText: (d) => d.data?.label || '',
      labelFontSize: 10,
      labelFill: () => themeColors().textSecondary,
      labelBackground: true,
      labelBackgroundFill: () => themeColors().bg,
      labelBackgroundOpacity: 0.85,
      labelPadding: [1, 4]
    }
  },
  // 树布局:统一 compact-box(H='LR' 单侧向右 / V='TB' 单侧向下);不用内置 mindmap —— 它把一半子树翻到根节点
  //  对侧,目录一多就左右发散不易读,且 @antv/hierarchy@0.7.1 的 mindmap 对 'V' 未做坐标转置。
  //  间距口径随主轴对调:水平方向 HGap=主轴层间距 60、VGap=交叉轴兄弟间距 14;垂直方向反之
  layout: {
    type: 'compact-box',
    direction: props.direction === 'V' ? 'TB' : 'LR',
    animation: false,
    getWidth: (d) => d.data?.w || 180,
    getHeight: (d) => d.data?.h || 40,
    getHGap: () => (props.direction === 'V' ? 14 : 60),
    getVGap: () => (props.direction === 'V' ? 60 : 14)
  }
}))

// 鸟瞰图:只画节点缩略块(边在小图里只是噪音;shape 回调会覆盖到边,html 容器矩形克隆只适用于节点);
// html 节点用底座工厂克隆等大小容器矩形充当缩略块(默认 minimap 画不出 html 节点)
const minimapOptions = { size: [160, 100], filter: (id, type) => type === 'node', shape: createHtmlMinimapShape() }

// 布局方向变化:就地重配(布局/边类型随 graphOptions 重放)+ 过渡动画,不销毁重建
watch(() => props.direction, () => baseRef.value?.reconfigure())

// 显示类型/名字口径:这两个开关只影响节点 HTML 渲染(renderNodeHtml),既不进图数据、也不改节点尺寸(w/h 与结构都不变)
// → 走底座 repaint(只重绘不重排):html 节点 innerHTML 重新求值,但不跑布局(自然也不会被树布局重新锚定)
watch(() => [props.showFieldType, props.fieldNameMode], () => baseRef.value?.repaint(buildData()))

// 目录树数据本身变化(挂载表/加关系表/删目录/换数据源后整树重拉):内容变了,显式重新定位到全貌(settle=true → fitView);
// 其余显示口径变化一律保持视口(底座 refitOnDataChange=false),需要看全貌时由用户点工具栏「适应画布」
watch(() => props.catalog, () => baseRef.value?.refresh(buildData(), true))
// 指定根目录变化(对象管理页切换选中目录):重建并重新定位全貌
watch(() => props.rootDir, () => baseRef.value?.refresh(buildData(), true))

// ---------- 事件口径 ----------
// 目录节点单击=收起/展开子树;表节点右缘 ▸/▾ 按钮单击=收起/展开关系表;「… 共 N 个字段」行单击=展开/收起全部字段
function onNodeClick(id, nodeData, event) {
  const d = nodeData?.data
  if (!d) return
  // 点中收起/展开按钮(HTML 节点内 DOM,经 nativeEvent.target 判定)
  const toggleHit = !!event?.nativeEvent?.target?.closest?.('[data-omg-toggle]')
  if (d.kind === 'dir' && d.childCount > 0) {
    toggleCollapse(id)
    return
  }
  if (d.kind === 'table' && d.relCount > 0 && toggleHit) {
    toggleCollapse(id)
    return
  }
  // 字段清单「展开/收起」行
  if ((d.kind === 'table' || d.kind === 'rel') && !!event?.nativeEvent?.target?.closest?.('[data-omg-fields]')) {
    toggleFieldsExpand(id)
  }
}

// 表/关系表节点双击=跳该表字段明细页(同表列表页跳 TableDetail 的路由参数形态)
function onNodeDblclick(id, nodeData) {
  const d = nodeData?.data
  if (!d || (d.kind !== 'table' && d.kind !== 'rel')) return
  const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(d.schemaName)}/tables/${encodeURIComponent(d.tableName)}`
  router.push(d.dbName ? `${base}?db=${encodeURIComponent(d.dbName)}` : base)
}
</script>
