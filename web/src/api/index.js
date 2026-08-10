import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.response.use(
  (response) => response.data,
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
    ElMessage.error(message || error.message || '请求失败')
    return Promise.reject(error)
  }
)

/** 提交 Word 报告异步导出任务,返回 taskId;进度与文件在「导出任务」页查看 */
export async function submitReportExport(dsId, db, schemas) {
  const q = db ? `?db=${encodeURIComponent(db)}` : ''
  const resp = await request.post(`/datasources/${dsId}/report/exports${q}`, {
    schemas: schemas && schemas.length ? schemas : null
  })
  return resp.taskId
}

export default request
