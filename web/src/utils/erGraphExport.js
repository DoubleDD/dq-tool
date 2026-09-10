import request from '../api'
import { exportListToExcel } from './listExport'

/**
 * ER 图导出 Excel(ER 关系页「导出ER关系」与字段明细页「ER 关系」页签「导出ER表格」共用):
 * 行 = 图内每张表 × 该表作为关系端点的去重级联字段(孤儿表无任何关系,级联字段留空);
 * 列:库名/模式/表英文名称/表中文名称/数据量/级联字段编码/级联字段名称。
 * 数据量与表列表页同口径:非采样的最新 DONE 扫描精确行数优先,否则回退元数据估算行数;
 * 级联字段名称 = 字段注释(columnsMap 由调用方先经整库字段清单接口加载后传入)。
 * 生成走通用列表导出(POST /api/list-exports + token 下载),所见即当前图数据。
 */
export async function exportErGraphExcel({ dsId, db, schema, filename, graph, columnsMap }) {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  const q = db ? `?db=${encodeURIComponent(db)}` : ''
  const [latest, tableList, dsList] = await Promise.all([
    request.get(`${base}/latest-scan-jobs${q}`).catch(() => ({})),
    request.get(`${base}/tables${q}`).catch(() => []),
    request.get('/datasources').catch(() => [])
  ])
  // 元数据估算行数(表名 -> estRows),扫描精确值的兜底
  const estRows = {}
  for (const t of tableList || []) estRows[t.name] = t.estRows
  // 字段注释索引:表|字段(忽略大小写) -> 注释
  const colComments = {}
  for (const [table, cols] of Object.entries(columnsMap || {})) {
    for (const c of cols) colComments[`${table.toLowerCase()}|${c.name.toLowerCase()}`] = c.comment || ''
  }
  // 每张表作为关系端点的级联字段(去重,按边 id 顺序)
  const relCols = {}
  for (const e of graph.edges || []) {
    for (const [table, col] of [[e.oneTable, e.oneColumn], [e.manyTable, e.manyColumn]]) {
      if (!relCols[table]) relCols[table] = []
      if (!relCols[table].includes(col)) relCols[table].push(col)
    }
  }
  const rowCount = (name) => {
    const s = (latest || {})[name]
    if (s && !s.sampled && s.totalRows !== null && s.totalRows !== undefined) return s.totalRows
    const est = estRows[name]
    return est === null || est === undefined ? '' : est
  }
  const headers = ['库名', '模式', '表英文名称', '表中文名称', '数据量', '级联字段编码', '级联字段名称']
  const ds = (dsList || []).find((d) => String(d.id) === String(dsId)) || {}
  const [dbName, schemaName] = resolveDbSchema(ds.dbType || '', ds.jdbcUrl || '', db, schema)
  const rows = []
  for (const n of graph.nodes || []) {
    const cols = relCols[n.name] || ['']
    for (const col of cols) {
      rows.push([
        dbName, schemaName, n.name, n.comment || '', rowCount(n.name),
        col, col ? colComments[`${n.name.toLowerCase()}|${col.toLowerCase()}`] || '' : ''
      ])
    }
  }
  await exportListToExcel(filename, headers, rows)
}

/**
 * 库名/模式列按数据库类型取值(与后端方言口径一致):
 * - 多库方言(SQL Server/Kingbase):库名=所选数据库(db),模式=schema
 * - MySQL/OceanBase:只有库没有模式,路由的 schema 参数实为 catalog(库名),模式留空
 * - PostgreSQL/瀚高:库名=JDBC URL 上的数据库名,模式=schema
 * - Oracle:库名=JDBC URL 上的 SID/服务名,模式=schema
 * - 达梦等无库概念方言:库名留空,仅模式
 */
function resolveDbSchema(dbType, jdbcUrl, db, schema) {
  if (['SQLSERVER', 'KINGBASE'].includes(dbType)) return [db || '', schema || '']
  if (['MYSQL', 'OCEANBASE'].includes(dbType)) return [schema || '', '']
  if (['POSTGRESQL', 'HIGHGO', 'ORACLE'].includes(dbType)) return [dbNameFromUrl(dbType, jdbcUrl), schema || '']
  return ['', schema || '']
}

/** 从 JDBC URL 解析库名:PG 系取 host:port 后的路径段;Oracle 取 SID(@host:port:SID)或服务名(@//host:port/service);解析失败返回空串 */
function dbNameFromUrl(dbType, url) {
  if (!url) return ''
  if (dbType === 'ORACLE') {
    const sid = url.match(/@[^/;:?,]+:\d+:([^;:?,]+)/)
    if (sid) return sid[1]
    const svc = url.match(/@\/\/[^/;:?,]+(?::\d+)?\/([^?;:]+)/)
    return svc ? svc[1] : ''
  }
  const m = url.match(/^[a-z][a-z0-9:+]*:\/\/[^/;:?,]+(?::\d+)?\/([^?;:]+)/i)
  return m ? m[1] : ''
}
