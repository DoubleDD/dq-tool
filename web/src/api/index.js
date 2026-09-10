import axios from 'axios'
import { ElMessage } from '../utils/notify'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 所有请求统一追加时间戳参数,避免浏览器缓存 GET 响应
request.interceptors.request.use((config) => {
  config.params = { ...config.params, _t: Date.now() }
  return config
})

request.interceptors.response.use(
  // _raw: true 时返回完整响应(含 headers),供需要读取降级响应头 X-Dq-Cache-Fallback 的调用方使用
  (response) => (response.config?._raw ? response : response.data),
  async (error) => {
    const status = error.response?.status
    const url = error.config?.url || ''
    // 授权失效(如到期):整页跳激活页,同时清掉 keep-alive 缓存的页面状态
    if (status === 401 && !url.startsWith('/license/')) {
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
    // _silent: true 时不弹全局错误提示(由调用方自行展示,如行内状态反馈)
    if (!error.config?._silent) {
      ElMessage.error(message || error.message || '请求失败')
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

/** 否决关系(候选/确认均可转否决;否决对再推导时不跳过——重新验证,命中则回炉为候选) */
export function rejectRelation(id) {
  return request.post(`/relations/${id}/reject`)
}

/** 删除关系(仅候选态) */
export function deleteRelation(id) {
  return request.delete(`/relations/${id}`)
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

/** 删除目录(级联删除子目录/挂载表/关系表,响应含删除统计) */
export function deleteObjectDir(id) {
  return request.delete(`/object-dirs/${id}`)
}

/** 目录批量挂载表;payload: { dbName(可空), schemaName, items[{tableName, relKind(可空)}], remark };响应 {mounted, existing} */
export function mountObjectTable(dirId, payload) {
  return request.post(`/object-dirs/${dirId}/tables`, payload)
}

/** 取消挂载 */
export function unmountObjectTable(id) {
  return request.delete(`/object-tables/${id}`)
}

/** 挂载表批量添加关系表;payload: { dbName(可空), schemaName, items[{tableName, relKind?}], remark };响应 {mounted, existing} */
export function addObjectTableRelation(objectTableId, payload) {
  return request.post(`/object-tables/${objectTableId}/relations`, payload)
}

/** 删除关系表 */
export function deleteObjectTableRelation(id) {
  return request.delete(`/object-table-relations/${id}`)
}

export default request
