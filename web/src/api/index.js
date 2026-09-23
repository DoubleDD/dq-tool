import axios from 'axios'
import { ElMessage } from '../utils/notify'
import { reportApiError } from '../utils/errorCapture'
import { apiBase, authHeaders } from './base'

const request = axios.create({
  baseURL: apiBase,
  timeout: 30000
})

/** 当前是否已在激活页(同源 / jpackage 与 Tauri 本地来源下,激活页 pathname 均为 /activate) */
function isOnActivatePage() {
  return /\/activate\/?$/.test(window.location.pathname)
}

// 所有请求统一追加时间戳参数,避免浏览器缓存 GET 响应
request.interceptors.request.use((config) => {
  // 基址每次请求读取:端口避让后 initApiBase 会更新 apiBase,不能缓存首次端口
  config.baseURL = apiBase
  Object.assign(config.headers, authHeaders())
  config.params = { ...config.params, _t: Date.now() }
  return config
})

request.interceptors.response.use(
  // _raw: true 时返回完整响应(含 headers),供需要读取降级响应头 X-Dq-Cache-Fallback 的调用方使用
  (response) => (response.config?._raw ? response : response.data),
  async (error) => {
    const status = error.response?.status
    const url = error.config?.url || ''
    // 授权失效(如到期):整页跳激活页,同时清掉 keep-alive 缓存的页面状态。
    // 已在激活页时绝不再跳:激活页上被 401 拦下的请求(后台任务轮询等)会把「跳到同一地址」
    // 变成整页重载,页面反复消失又出现,用户连授权码都输不进去(2026-09 首装未激活实例实测)
    if (status === 401 && !url.startsWith('/license/') && !isOnActivatePage()) {
      window.location.href = '/activate'
      return Promise.reject(error)
    }
    let message = error.response?.data?.message
    // blob 下载类接口的错误响应体也是 blob,解析回 JSON 取 message(如报告导出的「未扫描」拦截)
    if (!message && error.response?.data instanceof Blob) {
      try {
        message = JSON.parse(await error.response.data.text())?.message
      } catch { /* 非 JSON 错误体,忽略 */ }
    }
    // _silent: true 时不弹全局错误提示(由调用方自行展示,如行内状态反馈),也**不上报前端错误中心**——
    // 「静默」必须同时覆盖 UI 与采集两端:轮询类接口的失败(未授权实例的菜单 403、会话过期等)属预期内状态,
    // 调用方已自行兜底,记进错误中心只会变成噪音(2026-09 未开放 compare 菜单实例每次启动一条 API_403)
    if (!error.config?._silent) {
      ElMessage.error(message || error.message || '请求失败')
      // 前端错误中心:接口 4xx/5xx 与网络错误统一上报(401/503 为状态类响应,内部已排除)
      reportApiError(error, url, (error.config?.method || 'GET').toUpperCase(), message)
    }
    return Promise.reject(error)
  }
)

/** 提交 Word 报告异步导出任务,返回 taskId;进度与文件在「报告列表」页查看 */
export async function submitReportExport(dsId, db, schemas) {
  const q = db ? `?db=${encodeURIComponent(db)}` : ''
  const resp = await request.post(`/datasources/${dsId}/report/exports${q}`, {
    schemas: schemas && schemas.length ? schemas : null
  })
  return resp.taskId
}

// ---------- ER 关系推导 ----------
// 四元组定位一个库:datasourceId + dbName(可空) + schemaName;params 均为 query 参数

/** 提交一轮关系推导(锚点表+锚点字段,字段带映射名 fields:[{name, aliases[]}]),返回 { jobId };异步执行,轮询任务详情看进度 */
export function submitRelationInfer(payload) {
  return request.post('/relation-infer', payload)
}

/** 按库查询推导任务列表(新的在前);params: { datasourceId, dbName, schemaName } */
export function listRelationInferJobs(params) {
  return request.get('/relation-infer-jobs', { params })
}

/** 推导任务详情(前端 1s 轮询) */
export function getRelationInferJob(id) {
  return request.get(`/relation-infer-jobs/${id}`)
}

/** 全部未完成推导任务(跨库,后台任务中心 1s 轮询口径) */
export function listActiveInferJobs() {
  return request.get('/relation-infer-jobs/active', { _silent: true })
}

/** 全部未完成批量 AI 打标任务(跨库,后台任务中心 1s 轮询口径) */
export function listActiveAiTagTasks() {
  return request.get('/ai-tag-batch/active', { _silent: true })
}

/** 关系列表;params: { datasourceId, dbName, schemaName, status?, table? } */
export function listRelations(params) {
  return request.get('/relations', { params })
}

/** 手动补充关系(直接 CONFIRMED;命中唯一键的已存在关系转 CONFIRMED 返回原 id) */
export function addRelation(payload) {
  return request.post('/relations', payload)
}

/** 确认关系(候选/否决均可转确认) */
export function confirmRelation(id) {
  return request.post(`/relations/${id}/confirm`)
}

/** 否决关系(候选/确认均可转否决;人工审核结论落库后重新推导不再覆盖) */
export function rejectRelation(id) {
  return request.post(`/relations/${id}/reject`)
}

/** 删除关系(任意状态,误删可重新推导找回) */
export function deleteRelation(id) {
  return request.delete(`/relations/${id}`)
}

// 批量确认/否决,入参 ids 数组 + 可选 remarks(键为关系 id 字符串,值为人工填写的否决原因;
// 只有本次修改过的行才带,缺省的行保留原备注);返回 { ok, updated }(updated=实际影响数,不存在的 id 忽略)
export function batchConfirmRelations(ids, remarks) {
  return request.post('/relations/batch-confirm', { ids, remarks })
}
export function batchRejectRelations(ids, remarks) {
  return request.post('/relations/batch-reject', { ids, remarks })
}

/** 关系审核数据(导出 ER 关系 3 个 sheet:表清单 / 推导关系清单 / 关系变化)
 *  params: { datasourceId, dbName, schemaName, table? };table 非空时只保留该表参与的关系 */
export function getRelationAudit(params) {
  return request.get('/relation-audit', { params })
}

/** ER 图数据;params: { datasourceId, dbName, schemaName, table?, includeCandidate? }
 *  table 空=全库总图(CONFIRMED 边+孤儿表,includeCandidate 加候选边);非空=该表星型(含候选边) */
export function getRelationGraph(params) {
  return request.get('/relation-graph', { params })
}

// ---------- 对象管理(数据目录) ----------
// 目录树:ObjectDirNode { id, datasourceId, parentId, name, children[], tables[] },
// 挂载表 ObjectTableView { id, dirId, dbName, schemaName, tableName, comment, remark, relKind(INCLUDE=包含/ASSOC=关联,可空), relations[] }

/** 整棵对象目录树:返回虚拟根 ObjectDirNode { id:0, name:"", children:[顶层目录…] },渲染用其 children */
export function getObjectCatalog(dsId) {
  return request.get(`/datasources/${dsId}/object-catalog`)
}

/** 新建目录;payload: { datasourceId, parentId(可空=根目录), name } */
export function createObjectDir(payload) {
  return request.post('/object-dirs', payload)
}

/** 重命名目录 */
export function renameObjectDir(id, name) {
  return request.put(`/object-dirs/${id}`, { name })
}

/** 同级目录重排(目录树拖动排序);payload: { datasourceId, parentId(可空=根目录), orderedIds:[同级目录 id 按期望顺序] } */
export function sortObjectDirs(payload) {
  return request.post('/object-dirs/sort', payload)
}

/** 删除目录(级联删除子目录/挂载表/关系表,响应含删除统计) */
export function deleteObjectDir(id) {
  return request.delete(`/object-dirs/${id}`)
}

/** 移动目录到其它目录下(变更所属目录);parentId 传 0/null=根目录 */
export function moveObjectDir(id, parentId) {
  return request.post(`/object-dirs/${id}/move`, { parentId })
}

/** 目录批量挂载表;payload: { dbName(可空), schemaName, items[{tableName, relKind(可空)}], remark };响应 {mounted, existing} */
export function mountObjectTable(dirId, payload) {
  return request.post(`/object-dirs/${dirId}/tables`, payload)
}

/** 取消挂载 */
export function unmountObjectTable(id) {
  return request.delete(`/object-tables/${id}`)
}

/** 移动挂载表到其它目录(变更所属目录) */
export function moveObjectTable(id, dirId) {
  return request.post(`/object-tables/${id}/move`, { dirId })
}

/** 挂载表批量添加关系表;payload: { dbName(可空), schemaName, items[{tableName, relKind?}], remark };响应 {mounted, existing} */
export function addObjectTableRelation(objectTableId, payload) {
  return request.post(`/object-tables/${objectTableId}/relations`, payload)
}

/** 删除关系表 */
export function deleteObjectTableRelation(id) {
  return request.delete(`/object-table-relations/${id}`)
}

/** 移动关系表到其它挂载表下(变更所属挂载表) */
export function moveObjectRelation(id, objectTableId) {
  return request.post(`/object-table-relations/${id}/move`, { objectTableId })
}

// ---------- 元数据批量同步 ----------
// 任务结构:{ id, status(PENDING/RUNNING/DONE/FAILED/CANCELED), totalDs, doneDs, failedDs, error,
//   items: [{ datasourceId, datasourceName, status, dbCount, schemaCount, tableCount, progress, error }] }

/** 启动元数据批量同步;datasourceIds 为整数据源同步 id 数组,schemas 为库/schema 级清单 [{datasourceId, db, schema}],
 *  tables 为表级清单 [{datasourceId, db, schema, table}](可只传其一),返回 { jobId };已有运行中任务时 409(静默,由调用方接管提示) */
export function startMetadataSync(datasourceIds, tables, schemas) {
  return request.post('/metadata-sync', { datasourceIds, tables, schemas }, { _silent: true })
}

/** 最近一次同步任务;无任务返回 null(204/空响应体归一为 null),静默不弹全局错误 */
export async function getLatestMetadataSync() {
  const data = await request.get('/metadata-sync/latest', { _silent: true })
  return data && typeof data === 'object' ? data : null
}

/** 同步任务详情(进度视图 1s 轮询;静默,轮询失败由调用方下个周期重试) */
export function getMetadataSyncJob(id) {
  return request.get(`/metadata-sync/${id}`, { _silent: true })
}

/** 取消同步任务 */
export function cancelMetadataSync(id) {
  return request.post(`/metadata-sync/${id}/cancel`)
}

// ---------- 数据比对 ----------
// 视图结构:任务 CompareJobView / 目标指标 CompareTargetView / 差异行 CompareDiffRow,字段见后端 CompareModels.kt

/** 提交比对任务;payload: {name, baseDatasourceId, baseDb, baseSchema, baseTable,
 *  keyFields[](任务级默认身份字段,基准字段名数组,至少 1 个), keyField(旧列兼容,恒 = keyFields[0]),
 *  fields[], targets[{datasourceId, db, schema, table, mapping?, identity?: {keys[]}|null(仅人工收缩身份时带,null=按推导)}],
 *  displayFields[](V73 对象名称字段多选,有序,取值=行内第一个非空值,与 keyFields 互斥),
 *  displayField?(旧列兼容,恒 = displayFields[0]), matchMode?, compareMode?};返回 {jobId} */
export function createCompareJob(payload) {
  return request.post('/compare-jobs', payload)
}

/** 列级对比·字段映射预生成(大模型逐目标串行产出建议,人工审核后随任务提交);
 *  timeoutMs 按目标数放宽(后端逐目标串行调大模型,单目标最长约 120s,默认值只够 1 个目标);
 *  返回 {targets: [{datasourceId, db, schema, table, mapping, note}]} */
export function suggestCompareMapping(payload, timeoutMs = 130000) {
  return request.post('/compare-jobs/mapping-suggest', payload, { timeout: timeoutMs })
}

/** 任务列表(分页);archived=true 时含已归档(默认不含);
 *  filters 服务端筛选(空项自动忽略):kw 关键字 / status、tagIds 数组 / datasourceId / matchMode(LEGACY=仅编码) / compareMode
 *  返回 {rows, total, page, size} */
export function listCompareJobs(archived = false, filters = null, page = 1, size = 20) {
  const params = { page, size }
  if (archived) params.archived = true
  if (filters) {
    if (filters.kw && filters.kw.trim()) params.kw = filters.kw.trim()
    if (filters.status && filters.status.length) params.status = filters.status.join(',')
    if (filters.tagIds && filters.tagIds.length) params.tagIds = filters.tagIds.join(',')
    if (filters.datasourceId != null && filters.datasourceId !== '') params.datasourceId = filters.datasourceId
    if (filters.matchMode) params.matchMode = filters.matchMode
    if (filters.compareMode) params.compareMode = filters.compareMode
  }
  return request.get('/compare-jobs', { params })
}

/** RUNNING 任务瘦出行(后台任务中心 1s 轮询口径;compare 授权校验同前缀,未授权实例静默失败) */
export function listActiveCompareJobs() {
  return request.get('/compare-jobs/active', { _silent: true })
}

/** 任务详情 { job, targets };job.keyFields = 任务级默认身份字段(字符串数组,老任务后端已归一为 [keyField] 单列),
 *  targets[].identityKeys = 目标级身份人工覆盖(字符串数组,null=按推导);silent=true 用于轮询(失败不弹全局提示) */
export function getCompareJob(id, silent = false) {
  return request.get(`/compare-jobs/${id}`, silent ? { _silent: true } : {})
}

/** 差异明细分页;params: { targetId?, diffType?, kw?, page?, size? } → { rows, total, page, size };silent=true 用于轮询 */
export function listCompareDiffs(id, params = {}, silent = false) {
  return request.get(`/compare-jobs/${id}/diffs`, { params, ...(silent ? { _silent: true } : {}) })
}

/** 质量报告 { targets, fieldIssues, baseCount, sameCount, diffObjectCount, missingTotal, extraTotal, avgFieldConsistency } */
export function getCompareReport(id) {
  return request.get(`/compare-jobs/${id}/report`)
}

/** AI 判定明细(按目标+时间升序):[{ id, jobId, targetId, targetLabel, scene, stage, batchNo, model,
 *  requestContent, responseContent, resultJson, durationMs(调用耗时毫秒,老数据 null), createdAt }];stage: RESIDUE 补配 / SAME_NAME 同名消歧 /
 *  MAPPING 字段映射 / TIME 时间列识别 / EVIDENCE 佐证字段识别;
 *  resultJson: RESIDUE={pairs:[{baseCode,baseName,targetCode,targetName}],unmatchedBase:[{code,name}]},
 *  SAME_NAME={pairs:[{group,baseName,targetCode,targetName}]}, MAPPING={mapping,locked,failed?},
 *  TIME={field}, EVIDENCE={fields:{类别:列名}};
 *  失败批次:RESIDUE/SAME_NAME/TIME/EVIDENCE 的 resultJson 为 null、responseContent 为错误摘要,
 *  MAPPING 失败仍带锁定项、以 resultJson.failed=true 标记 */
export function listCompareAiTraces(id) {
  return request.get(`/compare-jobs/${id}/ai-traces`)
}

/** 重新比对(按原目标清单重跑;RUNNING 时后端 409) */
export function rerunCompareJob(id) {
  return request.post(`/compare-jobs/${id}/rerun`)
}

/** 归档/取消归档 */
export function setCompareArchived(id, archived) {
  return request.post(`/compare-jobs/${id}/archive`, null, { params: { archived } })
}

/** 批量删除任务(body {ids});返回 {deleted, skipped},RUNNING 跳过 */
export function batchDeleteCompareJobs(ids) {
  return request.post('/compare-jobs/batch-delete', { ids })
}

// ---------- 比对任务批量导入 ----------
// 批次视图 CompareImportView:{ id, fileName, fileSize, status(DS_REVIEW 待确认数据源 / BUILDING 建任务中 / DONE / FAILED),
//   dsReport[], taskCount, jobIds[], error };dsReport 逐数据源一行:
//   { key, name, host, port, databaseName, action(MATCHED 已匹配 / CREATE 待新建 / CREATED 已建档 / REBOUND 改绑 / ERROR 异常 / NOTE 提示), datasourceId, error }
// 模版下载(GET /api/compare-import-template)与原件下载(GET /api/compare-imports/{id}/file)为流式响应,直接走 downloadFile

/** 上传 Excel 提交导入批次(multipart 字段 file,仅 .xlsx;一 sheet 一任务);未配置大模型 409;返回 { batchId } */
export function submitCompareImport(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/compare-imports', formData)
}

/** 导入批次详情(数据源映射报告 + fileName/fileSize;confirm 后异步建任务,轮询到 DONE/FAILED 取 jobIds) */
export function getCompareImport(id) {
  return request.get(`/compare-imports/${id}`)
}

/** 确认数据源映射(仅 DS_REVIEW,否则 409):mapping 为用户逐行选择 { <ds_report 行 key>: 数据源 id 或 null=待新建 },
 *  缺省/空映射 = 全部按匹配结果照旧;转后台实测建档 + 逐 sheet 建 PENDING 任务,返回 { ok } */
export function confirmCompareImport(id, mapping) {
  return request.post(`/compare-imports/${id}/confirm`, mapping ? { mapping } : undefined)
}

/** 「字段审核」确认映射并开始比对(仅 PENDING,否则 409;校验口径同新建提交);
 *  payload: { mappings: { <targetId>: { 基准字段: 目标列 } }, identities?: { <targetId>: { keys: [基准身份字段] } } }
 *  (identities 仅人工收缩过身份的目标才带,缺省 = 全部按推导),确认后 PENDING→RUNNING 进执行器 */
export function confirmCompareMapping(id, payload) {
  return request.post(`/compare-jobs/${id}/confirm-mapping`, payload)
}

/** 「待处理」任务向导编辑提交(仅 PENDING,否则 409;payload 同 createCompareJob);
 *  保存后任务仍停 PENDING(原因归一 MAPPING_REVIEW),需再走字段审核确认开跑 */
export function updateCompareJob(id, payload) {
  return request.put(`/compare-jobs/${id}`, payload)
}

/** 「待处理」任务直接开始比对(仅 PENDING 且非 DS_ERROR,否则 409);
 *  映射已在编辑提交(updateCompareJob)时校验落库——编辑向导「保存并比对」在 PUT 成功后调它 */
export function startCompareJob(id) {
  return request.post(`/compare-jobs/${id}/start`)
}

/** 更新比对目标自定义显示名(备用接口,向导内编辑走 updateCompareJob 随 targets 提交);
 *  displayName 传 null/空串 = 清除,展示回落数据源名快照 */
export function updateCompareTargetDisplayName(id, targetId, displayName) {
  return request.put(`/compare-jobs/${id}/targets/${targetId}/display-name`, { displayName })
}

/** 打开比对任务的报告导出件 / 其所在文件夹(导出件在数据目录/compare;打开文件要求 checksum 一致,否则 409) */
export function openCompareExport(id, reveal = false) {
  return request.post(`/compare-jobs/${id}/${reveal ? 'reveal-export' : 'open-export'}`)
}

/** 导出比对报告:服务端直存数据目录/compare(任务 ID 前缀命名,同名覆盖只留最后一次),
 *  落库导出状态 + checksum;返回 {path,name,size,checksum},前端完成后通知(可打开文件/文件夹) */
export function exportCompareReport(id) {
  return request.post(`/compare-jobs/${id}/export`)
}

// ---------- 导出中心(V66) ----------
/** 统一分页列表;params: { kind?, keyword?, start?, end?, page?, size? } → {total, items}
 *  item: {id:"KIND:源id", kind, title, fileName, fileSize, storage(NONE/DISK), downloadPath, status, error, createdAt, finishedAt} */
export function listExportCenter(params = {}) {
  return request.get('/export-center', { params })
}

/** 删除导出记录(push 模型下所有类型都是登记记录,仅删记录不动磁盘文件;不存在 400) */
export function deleteExportCenter(kind, id) {
  return request.delete(`/export-center/${kind}/${id}`)
}

/** 统一直存成功后的导出中心回填:按文件名关联最新 RUNNING 登记,补 rel_path(exports/<文件名>)+ 实测大小 */
export function exportLanded(fileName) {
  return request.post('/export-center/landed', { fileName })
}

/** 导出失败标记:把点击时登记的「生成中」记录翻 FAILED(path 关联登记的 params.path) */
export function exportFailed(path, error) {
  return request.post('/export-center/fail', { path, error })
}

export default request
