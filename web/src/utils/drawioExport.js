/**
 * ER 图导出 .drawio(drawio 明文 mxfile XML,无第三方依赖):
 * - 表节点 = drawio 原生 ER 表(shape=table + tableRow + partialRectangle),
 *   在 drawio 桌面版/在线版中可直接增删行、把连线拖到具体字段行上;
 * - 关系边 = entityRelationEdgeStyle + ERone/ERmany 乌鸦脚端标记,基数作边标签,
 *   颜色语义与画布一致:确认=蓝实线 / 候选=灰虚线 / 疑似多对多=红实线;
 * - 布局:节点坐标取画布 G6 实测布局中心(所见即所得),取不到时网格兜底;
 * - 档位与字段行由画布按当前显示档位给出,与页面渲染一致(最多 15 行,超出折叠「+N 个字段」)。
 * 配色固定中性值:导出文件用于外发交付,不随应用亮/暗主题变化。
 */
import { downloadText } from './download'

// 节点宽度与画布 NODE_W 一致;表头高(有注释更高);字段行高(画布行高 18,drawio 表格取更易读的 26)
const TABLE_W = 240
const HEADER_H = 30
const HEADER_H_COMMENT = 44
const ROW_H = 26
// 无布局坐标时的网格兜底:每行列数
const GRID_COL = 5

const COLOR = {
  tableFill: '#dae8fc',
  tableStroke: '#6c8ebf',
  textSecondary: '#909399',
  confirmed: '#409eff',
  candidate: '#909399',
  suspect: '#f56c6c'
}

// 基数标签(与画布 CARD_TEXT 同口径)
const CARD_TEXT = { ONE_TO_ONE: '1:1', ONE_TO_MANY: '1:N', SUSPECT_MANY_TO_MANY: 'M:N(疑似)' }

/** HTML 标签内文本转义(表名/注释/字段名可能含 <>&" 等) */
function h(value) {
  return String(value ?? '')
    // 剔除 XML 1.0 非法控制字符,折叠空白(注释可能含换行/制表符)
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 组合好的 HTML 作为 XML 属性值的转义(drawio 约定的二次编码:
 * XML 解析后还原为 HTML,单元格 html=1 时按 HTML 渲染;内层 &lt; 来自 h())
 */
function x(html) {
  return html.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 节点高度 = 表头 + 字段行数(+折叠行)* 行高 */
function nodeHeight(n) {
  const headerH = n.comment ? HEADER_H_COMMENT : HEADER_H
  return headerH + (n.rows.length + (n.more > 0 ? 1 : 0)) * ROW_H
}

/** 表节点:表头 cell(有中文名时中文名为主标题、英文表名小字在下,与画布一致)+ 字段行(tableRow+partialRectangle)+ 折叠行 */
function tableXml(n, id) {
  const headerH = n.comment ? HEADER_H_COMMENT : HEADER_H
  const header = `<b>${h(n.comment || n.name)}</b>`
    + (n.comment ? `<font style="font-size:9px" color="${COLOR.textSecondary}"><br/>${h(n.name)}</font>` : '')
  const parts = [
    `<mxCell id="${id}" value="${x(header)}" style="shape=table;startSize=${headerH};container=1;childLayout=tableLayout;align=center;verticalAlign=middle;rounded=0;shadow=0;strokeColor=${COLOR.tableStroke};strokeWidth=1;fillColor=${COLOR.tableFill};swimlaneFillColor=#ffffff;fontSize=12;html=1;whiteSpace=wrap;collapsible=0;" vertex="1" parent="1"><mxGeometry x="${n.x}" y="${n.y}" width="${TABLE_W}" height="${nodeHeight(n)}" as="geometry" /></mxCell>`
  ]
  let y = headerH
  n.rows.forEach((f, i) => {
    parts.push(rowXml(id, i, f, y))
    y += ROW_H
  })
  if (n.more > 0) parts.push(rowXml(id, n.rows.length, { name: `+${n.more} 个字段`, muted: true }, y))
  return parts.join('')
}

/** 一行 = tableRow(容器,行左右缘带连接点,便于后续手动连线到字段)+ 单格 partialRectangle(内容) */
function rowXml(tableId, idx, f, y) {
  const rid = `${tableId}r${idx}`
  const font = f.muted ? `fontColor=${COLOR.textSecondary};` : (f.related ? `fontStyle=1;fontColor=${COLOR.confirmed};` : '')
  const value = h(f.name)
    + (!f.muted && f.type ? `<font style="font-size:9px" color="${COLOR.textSecondary}">  ${h(f.type)}</font>` : '')
  return `<mxCell id="${rid}" value="" style="shape=tableRow;horizontal=0;startSize=0;swimlaneHead=0;swimlaneBody=0;fillColor=none;collapsible=0;dropTarget=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;topRightLeftRoundtrip=0;" vertex="1" parent="${tableId}"><mxGeometry y="${y}" width="${TABLE_W}" height="${ROW_H}" as="geometry" /></mxCell>`
    + `<mxCell id="${rid}c" value="${x(value)}" style="shape=partialRectangle;html=1;whiteSpace=wrap;connectable=1;strokeColor=inherit;overflow=hidden;fillColor=none;top=0;left=0;bottom=0;right=0;pointerEvents=1;align=left;spacingLeft=6;fontSize=11;${font}" vertex="1" parent="${rid}"><mxGeometry width="${TABLE_W}" height="${ROW_H}" as="geometry" /></mxCell>`
}

/**
 * 关系边:one 侧为起点(「一」端)、many 侧为终点;
 * 端标记语义与画布一致:1:1 双端竖杠 / 1:N 起点竖杠终点鸦脚 / 疑似多对多双端鸦脚;
 * 不写 exitX/entryX(连接点交给 drawio 正交路由自动选取,用户可手动拖到字段行)
 */
function edgeXml(e, sourceId, targetId, id) {
  const suspect = e.cardinality === 'SUSPECT_MANY_TO_MANY'
  const color = suspect ? COLOR.suspect : (e.status === 'CONFIRMED' ? COLOR.confirmed : COLOR.candidate)
  const endArrow = e.cardinality === 'ONE_TO_ONE' ? 'ERone' : 'ERmany'
  const dashed = e.status === 'CANDIDATE' ? 'dashed=1;' : ''
  const style = `edgeStyle=entityRelationEdgeStyle;html=1;rounded=0;strokeColor=${color};strokeWidth=1.5;${dashed}fontSize=10;fontColor=#666666;labelBackgroundColor=#ffffff;startArrow=ERone;endArrow=${endArrow};`
  return `<mxCell id="${id}" value="${x(h(CARD_TEXT[e.cardinality] || e.cardinality))}" style="${style}" edge="1" parent="1" source="${sourceId}" target="${targetId}"><mxGeometry relative="1" as="geometry" /></mxCell>`
}

/** 布局:有画布中心坐标的按导出行高重新以中心对齐;缺失的按网格兜底排布 */
function layoutNodes(nodes) {
  let grid = 0
  return nodes.map((n) => {
    if (typeof n.cx === 'number' && typeof n.cy === 'number') {
      const height = nodeHeight(n)
      return { ...n, x: Math.round(n.cx - TABLE_W / 2), y: Math.round(n.cy - height / 2) }
    }
    const x = 40 + (grid % GRID_COL) * (TABLE_W + 80)
    const y = 40 + Math.floor(grid / GRID_COL) * 280
    grid += 1
    return { ...n, x, y }
  })
}

/**
 * 组装 .drawio XML(明文 mxfile,drawio 桌面版/在线版直接打开)。
 * @param {Object} data { nodes, edges } — nodes: [{ name, comment, cx, cy, rows: [{name,type,related}], more }],
 *                                      edges: TableRelation(oneTable/manyTable/cardinality/status/...)
 * @param {Object} [options] { name } diagram 页签名(同时用于下载文件名)
 * @returns {string} mxfile XML 文本
 */
export function buildDrawioXml(data, { name = 'ER 图' } = {}) {
  const nodes = layoutNodes(data.nodes || [])
  const idByTable = new Map(nodes.map((n, i) => [n.name, `n${i}`]))
  const cells = nodes.map((n, i) => tableXml(n, `n${i}`))
  let edgeSeq = 0
  for (const e of data.edges || []) {
    const sourceId = idByTable.get(e.oneTable)
    const targetId = idByTable.get(e.manyTable)
    // 端点表不在节点集(理论不发生,画布数据组装保证)则跳过该边
    if (!sourceId || !targetId) continue
    cells.push(edgeXml(e, sourceId, targetId, `e${edgeSeq}`))
    edgeSeq += 1
  }
  return `<mxfile host="dq-tool" agent="dq-tool" version="24.7.7" modified="${new Date().toISOString()}" type="device"><diagram id="dq-er" name="${x(h(name))}"><mxGraphModel dx="1000" dy="700" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1169" pageHeight="826" math="0" shadow="0"><root><mxCell id="0" /><mxCell id="1" parent="0" />${cells.join('')}</root></mxGraphModel></diagram></mxfile>`
}

/** 组装并触发下载(浏览器 Blob 下载 / Tauri 原生保存,同诊断报告导出口径);文件名非法字符替换为下划线 */
export function downloadDrawio(data, name = 'ER 图') {
  const safe = name.replace(/[\\/:*?"<>|]/g, '_')
  downloadText(`${safe}.drawio`, buildDrawioXml(data, { name }), 'application/xml')
}
