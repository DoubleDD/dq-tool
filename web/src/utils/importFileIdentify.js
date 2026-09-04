// 导入文件自动识别与确认
// 系统三种导出文件都是 .json,靠顶层 app 字段区分("dq-tool" 数据源 / "dq-tool-scans" 扫描记录 / "dq-tool-annotations" 标记与描述);
// Navicat .ncx 与 DataGrip 剪贴板按 XML 特征识别。选文件后先识别归属并弹窗确认,
// 防止用户拿错文件(喂错 JSON 只会得到一句混淆的后端报错)。
import { h } from 'vue'
import { ElMessageBox } from 'element-plus'

// 各导入功能的归属描述:功能 key → 名称与入口提示(用于拿错文件时指路)
export const IMPORT_FEATURES = {
  datasource: { name: '数据源导入', where: '「数据源」页的「导入配置」' },
  scans: { name: '扫描记录导入', where: '「扫描记录」页的「导入记录」' },
  annotations: { name: '标记与描述数据导入', where: '「系统设置」页的「标记与描述数据」' },
}

// 文件种类 → 归属功能与展示名
const KINDS = {
  'datasource-json': { feature: 'datasource', label: '数据源导出文件' },
  'navicat-ncx': { feature: 'datasource', label: 'Navicat 连接导出文件(.ncx)' },
  'datagrip': { feature: 'datasource', label: 'DataGrip 数据源剪贴板内容' },
  'scans-json': { feature: 'scans', label: '扫描记录导出文件' },
  'annotations-json': { feature: 'annotations', label: '标记与描述导出文件' },
}

// 读取文件文本:UTF-8 优先,失败按 GBK 兜底(客户可能用记事本把文件另存为 ANSI=GBK)
async function readTextTolerant(file) {
  const buf = await file.arrayBuffer()
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf)
  } catch {
    return new TextDecoder('gbk').decode(buf)
  }
}

/**
 * 识别导入文件种类。返回 { kind, label, summary, version? }:
 * kind 为 KINDS 的 key 或 'unknown';summary 为内容摘要(如"共 3 个数据源");
 * version 仅三种 JSON 导出文件有,不等于 1 表示与当前工具不兼容。
 */
export async function identifyImportFile(file) {
  const text = (await readTextTolerant(file)).trim()
  if (text.startsWith('{')) {
    try {
      const obj = JSON.parse(text)
      if (obj?.app === 'dq-tool') {
        return { kind: 'datasource-json', version: obj.version, summary: `共 ${obj.items?.length ?? 0} 个数据源` }
      }
      if (obj?.app === 'dq-tool-scans') {
        return { kind: 'scans-json', version: obj.version, summary: `共 ${obj.jobs?.length ?? 0} 条扫描记录、${obj.tagDefs?.length ?? 0} 个标记定义` }
      }
      if (obj?.app === 'dq-tool-annotations') {
        return { kind: 'annotations-json', version: obj.version, summary: `共 ${obj.tags?.length ?? 0} 个标记定义、${obj.tableTags?.length ?? 0} 条表标记、${obj.tableDocs?.length ?? 0} 条表描述` }
      }
    } catch {
      // JSON 解析失败,落到 unknown
    }
    return { kind: 'unknown' }
  }
  // XML 类:Navicat .ncx(<Connection ...>)或 DataGrip 剪贴板(<data-source ...>)
  const connCount = (text.match(/<Connection\s/g) || []).length
  if (connCount > 0) return { kind: 'navicat-ncx', summary: `共 ${connCount} 个连接` }
  const dsCount = (text.match(/<data-source\s/g) || []).length
  if (dsCount > 0) return { kind: 'datagrip', summary: `共 ${dsCount} 个数据源` }
  return { kind: 'unknown' }
}

/**
 * 识别文件并弹窗确认是否导入。
 * @param file 待导入的 File 对象
 * @param currentFeature 当前入口功能 key(IMPORT_FEATURES 的 key)
 * @returns true=用户确认导入;false=无法识别/版本不兼容/不属于当前功能/用户取消
 */
export async function confirmImportFile(file, currentFeature) {
  const info = await identifyImportFile(file)
  const def = KINDS[info.kind]
  if (!def) {
    await ElMessageBox.alert(
      '无法识别该文件:既不是本工具的导出文件(数据源 / 扫描记录 / 标记与描述),也不是支持的 Navicat .ncx 或 DataGrip 剪贴板格式。',
      '导入文件无法识别',
      { type: 'error', confirmButtonText: '知道了', closeOnPressEscape: false }
    )
    return false
  }
  const head = h('div', [
    h('span', '识别到该文件是:'),
    h('b', def.label),
    info.summary ? h('span', `(${info.summary})`) : null,
  ])
  if (info.version !== undefined && info.version !== 1) {
    await ElMessageBox.alert(
      h('div', [
        head,
        h('div', { style: 'margin-top: 8px' }, `文件版本(v${info.version})与当前工具不兼容,请使用导出该文件的工具版本导入,或升级本工具。`),
      ]),
      '导入文件版本不兼容',
      { type: 'warning', confirmButtonText: '知道了', closeOnPressEscape: false }
    )
    return false
  }
  if (def.feature !== currentFeature) {
    const target = IMPORT_FEATURES[def.feature]
    await ElMessageBox.alert(
      h('div', [
        head,
        h('div', { style: 'margin-top: 8px' }, `它与当前「${IMPORT_FEATURES[currentFeature].name}」功能不匹配,请到${target.where}导入。`),
      ]),
      '导入文件与当前功能不匹配',
      { type: 'warning', confirmButtonText: '知道了', closeOnPressEscape: false }
    )
    return false
  }
  try {
    await ElMessageBox({
      title: '导入文件确认',
      message: h('div', [
        head,
        h('div', { style: 'margin-top: 8px' }, '确认导入该文件?'),
      ]),
      type: 'info',
      showCancelButton: true,
      confirmButtonText: '确认导入',
      cancelButtonText: '取消',
      closeOnPressEscape: false,
    })
    return true
  } catch {
    // 用户取消
    return false
  }
}
