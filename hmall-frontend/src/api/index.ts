import axios from 'axios'
import router from '@/router'

const instance = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// 请求拦截器：自动携带 token
instance.interceptors.request.use(
  (config) => {
    const token = sessionStorage.getItem('token')
    if (token) {
      config.headers.authorization = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：自动解包 data + 401 处理
instance.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('token')
      sessionStorage.removeItem('user-info')
      const currentPath = router.currentRoute?.value?.path || ''
      if (!currentPath.includes('/portal/login') && !currentPath.includes('/admin/login')) {
        router.push('/portal/login')
      }
    }
    return Promise.reject(error)
  }
)

export default instance
