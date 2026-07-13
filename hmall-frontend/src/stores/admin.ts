import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { logoutApi } from '@/api/user'

export const useAdminStore = defineStore('admin', () => {
  const adminToken = ref(sessionStorage.getItem('admin-token') || '')
  const adminUser = ref(
    JSON.parse(sessionStorage.getItem('admin-user') || 'null')
  )

  const isAdminLogin = computed(() => !!adminToken.value)

  function login(username: string, token: string) {
    adminToken.value = token
    adminUser.value = { username }
    sessionStorage.setItem('admin-token', token)
    sessionStorage.setItem('admin-user', JSON.stringify({ username }))
  }

  /** 登出：先调后端接口使 token 失效，再清除本地状态 */
  async function logout() {
    try {
      await logoutApi()
    } catch {
      // 即使后端调用失败，也继续清除本地状态
    }
    adminToken.value = ''
    adminUser.value = null
    sessionStorage.removeItem('admin-token')
    sessionStorage.removeItem('admin-user')
  }

  return { adminToken, adminUser, isAdminLogin, login, logout }
})
