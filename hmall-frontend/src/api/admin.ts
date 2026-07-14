import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * admin 专属 axios 实例
 * - 独立 baseURL（走 Vite 代理 → 网关 → admin-service）
 * - 独立 sessionStorage key（admin-token）
 * - 响应拦截解包 R<T> + token 续期 + 401/403 处理
 */
const adminInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截：携带 admin-token
adminInstance.interceptors.request.use(
  (config) => {
    const token = sessionStorage.getItem('admin-token')
    if (token) {
      config.headers.authorization = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：解包 R<T> + token续期 + 401处理
adminInstance.interceptors.response.use(
  (response) => {
    // Token 续期
    const newToken = response.headers['authorization']
    if (newToken) {
      sessionStorage.setItem('admin-token', newToken)
    }
    const { code, msg, data } = response.data
    if (code === 200) return data
    ElMessage.error(msg || '请求失败')
    return Promise.reject(new Error(msg))
  },
  (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('admin-token')
      sessionStorage.removeItem('admin-info')
      ElMessage.warning('登录已过期，请重新登录')
      // 用 location.hash 避免循环依赖，直接跳转登录页
      if (!location.hash.includes('/admin/login')) {
        location.hash = '#/admin/login'
      }
    } else if (error.response?.status === 403) {
      ElMessage.error('无权限执行此操作')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default adminInstance
