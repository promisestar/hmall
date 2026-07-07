import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

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

  function logout() {
    adminToken.value = ''
    adminUser.value = null
    sessionStorage.removeItem('admin-token')
    sessionStorage.removeItem('admin-user')
  }

  return { adminToken, adminUser, isAdminLogin, login, logout }
})
