import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    const url = error.config?.url || ''
    // 授权失效(如到期):整页跳激活页,同时清掉 keep-alive 缓存的页面状态
    if (status === 401 && !url.startsWith('/license/')) {
      window.location.href = '/activate'
      return Promise.reject(error)
    }
    const message = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default request
