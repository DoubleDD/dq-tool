import request from '../api'
import { downloadFile } from './download'

/**
 * 通用列表导出 Excel:把当前表格所见数据(表头 + 行,含过滤/合并后的展示口径)
 * POST 给后端渲染 xlsx 并暂存,再用返回的 token 走 GET 一次性下载
 * (复用 downloadFile:桌面端弹原生保存对话框,浏览器 window.open,见 utils/download.js)。
 * @param {string} filename 文件名(不带扩展名,如 `库列表-生产库`)
 * @param {string[]} headers 表头
 * @param {Array<Array>} rows 数据行,与表头等长;值为展示字符串,null/undefined 导出为空单元格
 * @param {string} [sheetName] sheet 名,默认与文件名相同
 */
export async function exportListToExcel(filename, headers, rows, sheetName) {
  const { token } = await request.post('/list-exports', {
    filename,
    sheets: [{ name: sheetName || filename, headers, rows }]
  })
  downloadFile(`/api/list-exports/${token}`)
}

/** 单元格取值:null/undefined 归一为空串(导出空单元格),其余转字符串 */
export function cellText(v) {
  return v === null || v === undefined ? '' : String(v)
}
