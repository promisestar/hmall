import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi } from '@/api/user'
import type { UserInfo, LoginFormDTO } from '@/types'

export const useUserStore = defineStore('user', () => {
  const token = ref(sessionStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(sessionStorage.getItem('user-info') || 'null')
  )

  const isLogin = computed(() => !!token.value)
  const username = computed(() => userInfo.value?.username || '')
  const balance = computed(() => userInfo.value?.balance || 0)

  async function login(data: LoginFormDTO) {
    const res = await loginApi(data)
    token.value = res.token
    userInfo.value = {
      id: res.userId,
      username: res.username,
      balance: res.balance,
    }
    sessionStorage.setItem('token', res.token)
    sessionStorage.setItem('user-info', JSON.stringify(userInfo.value))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    sessionStorage.removeItem('token')
    sessionStorage.removeItem('user-info')
  }

  function setUserInfo(info: UserInfo) {
    userInfo.value = info
    sessionStorage.setItem('user-info', JSON.stringify(info))
  }

  return { token, userInfo, isLogin, username, balance, login, logout, setUserInfo }
})
