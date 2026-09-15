import request from '../api'
import { exportSheetsToExcel } from './listExport'

/**
 * ER 关系导出 Excel(ER 关系页「导出ER关系」与字段明细页「ER 关系」页签「导出ER表格」共用):
 * 3 个 sheet,顺序固定为 **表清单 / 推导关系清单 / 关系变化**,三个 sheet 表头与数据逻辑一致——
 * - 表清单:当前图口径(全库总图仅确认边,星型图恒含候选边);列:库名/模式/表英文名称/表中文名称/数据量/级联字段编码/级联字段名称/标签
 * - 推导关系清单:大模型推导产出的原始关系(table_relation_original),同表头同数据逻辑,只是关系集合取原始关系
 * - 关系变化:同表头同数据逻辑,**逐「表.字段」做差,只保留只在一侧出现的行**(两边都有的 表.字段 不算变化),
 *   并在末列追加「备注」= 该 表.字段 对应最终关系的人工审核备注说明(去重后分号连接)
 * 三个 sheet 的共同口径:每张表 × 该表作为关系端点的去重级联字段(一表多个关联字段出多行,无关联字段出一行且留空);
 * **有中心/锚点表时整表不列(不显示主表信息,只显示副表)**;数据量/字段注释/表标记与表列表页同源;
 * 生成走通用多 sheet 列表导出通道(POST /api/list-exports + token 下载),不新增专用导出接口。
 *
 * @param {object}   p
 * @param {string|number} p.dsId 数据源 id
 * @param {string}   p.db 多库方言所选数据库(可空)
 * @param {string}   p.schema 库/模式
 * @param {string}   [p.table] 中心/锚点表:非空时三个 sheet 都不列该表(主表),只列与之关联的副表;空=全库总图,列出全部表
 * @param {string}   p.filename 文件名(不带扩展名)
 * @param {object}   p.graph 当前图数据 `{nodes, edges}`(表清单 sheet 的行来源)
 */
export async function exportErRelationExcel({ dsId, db, schema, table, filename, graph }) {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  const q = db ? `?db=${encodeURIComponent(db)}` : ''
  const [audit, latest, tableList, columns, dsList, tagMap] = await Promise.all([
    request.get('/relation-audit', {
      params: { datasourceId: dsId, dbName: db || undefined, schemaName: schema, table: table || undefined }
    }),
    request.get(`${base}/latest-scan-jobs${q}`).catch(() => ({})),
    request.get(`${base}/tables${q}`).catch(() => []),
    request.get(`${base}/columns${q}`).catch(() => []),
    request.get('/datasources').catch(() => []),
    request.get(`${base}/table-tags${q}`).catch(() => ({}))
  ])
  // 表名 -> 中文注释 / 元数据估算行数(忽略大小写兜底)
  const comments = {}
  const estRows = {}
  for (const t of tableList || []) {
    if (!t?.name) continue
    comments[t.name] = t.comment || ''
    comments[String(t.name).toLowerCase()] = t.comment || ''
    estRows[t.name] = t.estRows
  }
  // 「表|字段」(忽略大小写) -> 字段注释,供「级联字段名称」列
  const colComments = {}
  for (const c of columns || []) {
    if (!c?.table || !c?.name) continue
    colComments[`${c.table.toLowerCase()}|${c.name.toLowerCase()}`] = c.comment || ''
  }
  // 表名 -> 标记清单(含空表/备份表系统标记,按 id 升序);「标签」列取名字、顿号连接
  const tags = {}
  for (const [name, list] of Object.entries(tagMap || {})) {
    const sorted = (list || []).slice().sort((a, b) => a.id - b.id)
    tags[name] = sorted
    tags[String(name).toLowerCase()] = sorted
  }
  // 「表|字段」(忽略大小写) -> 最终关系的人工审核备注说明(去重、分号连接),供「关系变化」sheet 的「备注」列
  const remarkIndex = {}
  const indexRemark = (table, col, remark) => {
    if (!table || !col) return
    const text = (remark || '').trim()
    if (!text) return
    const key = `${table}|${col}`.toLowerCase()
    if (!remarkIndex[key]) remarkIndex[key] = []
    if (!remarkIndex[key].includes(text)) remarkIndex[key].push(text)
  }
  for (const f of audit.finals || []) {
    indexRemark(f.oneTable, f.oneColumn, f.remark)
    indexRemark(f.manyTable, f.manyColumn, f.remark)
  }
  const tagsOf = (name) => tags[name] ?? tags[String(name).toLowerCase()] ?? []
  const commentOf = (name) => comments[name] ?? comments[String(name).toLowerCase()] ?? ''
  // 数据量:与表列表页同口径——非采样的最新 DONE 扫描精确行数优先,否则回退元数据估算行数,都没有留空
  const rowCount = (name) => {
    const s = (latest || {})[name]
    if (s && !s.sampled && s.totalRows !== null && s.totalRows !== undefined) return s.totalRows
    const est = estRows[name]
    return est === null || est === undefined ? '' : est
  }
  const ds = (dsList || []).find((d) => String(d.id) === String(dsId)) || {}
  const [dbName, schemaName] = resolveDbSchema(ds.dbType || '', ds.jdbcUrl || '', db, schema)

  const tableHeaders = ['库名', '模式', '表英文名称', '表中文名称', '数据量', '级联字段编码', '级联字段名称', '标签']
  // 中心/锚点表(主表):非空时三个 sheet 都不列该表
  const center = table || ''

  /** 关系集合 -> 表名(原始大小写) -> 该表的去重端点级联字段(保序);同时给出全部「表|字段」键(小写)供逐字段做差 */
  const collect = (relations) => {
    const tables = new Map()
    const pairs = new Set()
    for (const e of relations || []) {
      for (const [t, col] of [[e.oneTable, e.oneColumn], [e.manyTable, e.manyColumn]]) {
        if (!t || !col) continue
        if (!tables.has(t)) tables.set(t, [])
        if (!tables.get(t).includes(col)) tables.get(t).push(col)
        pairs.add(`${t}|${col}`.toLowerCase())
      }
    }
    return { tables, pairs }
  }

  /**
   * 三个 sheet 共用的行构造:每张表 × 该表的关系端点级联字段(一表多个关联字段出多行,无关联字段出一行且留空);
   * 有中心/锚点表时整表不列(不显示主表信息,只显示副表);withRemark=true 时末列追加人工审核备注(关系变化 sheet 用)。
   * @param {Array<{name: string, comment?: string}>} tables 要列出的表(顺序即行顺序)
   * @param {Map<string, string[]>} colMap 表名 -> 该表的端点级联字段
   * @param {boolean} withRemark 是否追加「备注」列
   */
  const buildRows = (tables, colMap, withRemark) => {
    const rows = []
    for (const t of tables) {
      const name = t.name
      if (!name || (center && name === center)) continue
      const cols = (colMap && colMap.get(name)) || ['']
      const tagText = tagsOf(name).map((x) => x.name).join('、')
      for (const col of cols) {
        const row = [
          dbName, schemaName, name, t.comment || commentOf(name), rowCount(name),
          col, col ? colComments[`${name.toLowerCase()}|${col.toLowerCase()}`] || '' : '', tagText
        ]
        if (withRemark) row.push(col ? (remarkIndex[`${name}|${col}`.toLowerCase()] || []).join('; ') : '')
        rows.push(row)
      }
    }
    return rows
  }

  // ---------- sheet 1 表清单:当前图口径(全库总图仅确认边,星型图恒含候选边) ----------
  const finalSet = collect(graph?.edges)
  const tableRows = buildRows(graph?.nodes || [], finalSet.tables, false)

  // ---------- sheet 2 推导关系清单:大模型推导产出的原始关系,表头/数据逻辑同表清单 ----------
  const originalSet = collect(audit.originals)
  const originalTables = [...originalSet.tables.keys()].sort().map((name) => ({ name }))
  const originalRows = buildRows(originalTables, originalSet.tables, false)

  // ---------- sheet 3 关系变化:只保留两边存在差异的「表.字段」行,附人工审核备注 ----------
  // 逐字段做差:两边都有的「表.字段」不算变化(如 d_x.id 两个 sheet 都有),只列只在一侧出现的字段;
  // 表清单侧独有 = 人工补充/图上有、模型未提;原始关系侧独有 = 模型提过但被否决/删除
  const diffCols = new Map()
  const addDiff = (name, col) => {
    if (!diffCols.has(name)) diffCols.set(name, [])
    if (!diffCols.get(name).includes(col)) diffCols.get(name).push(col)
  }
  for (const e of graph?.edges || []) {
    for (const [t, col] of [[e.oneTable, e.oneColumn], [e.manyTable, e.manyColumn]]) {
      if (t && col && !originalSet.pairs.has(`${t}|${col}`.toLowerCase())) addDiff(t, col)
    }
  }
  for (const o of audit.originals || []) {
    for (const [t, col] of [[o.oneTable, o.oneColumn], [o.manyTable, o.manyColumn]]) {
      if (t && col && !finalSet.pairs.has(`${t}|${col}`.toLowerCase())) addDiff(t, col)
    }
  }
  const diffTables = [...diffCols.keys()].sort().map((name) => ({ name }))
  const changeRows = buildRows(diffTables, diffCols, true)
  const changeHeaders = [...tableHeaders, '备注']

  await exportSheetsToExcel(filename, [
    { name: '表清单', headers: tableHeaders, rows: tableRows },
    { name: '推导关系清单', headers: tableHeaders, rows: originalRows },
    { name: '关系变化', headers: changeHeaders, rows: changeRows }
  ])
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
