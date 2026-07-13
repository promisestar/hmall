import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, loginByCode as loginByCodeApi, logoutApi } from '@/api/user'
import type { UserInfo, LoginFormDTO, LoginByCodeDTO } from '@/types'

export const useUserStore = defineStore('user', () => {
  const token = ref(sessionStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(sessionStorage.getItem('user-info') || 'null')
  )

  const isLogin = computed(() => !!token.value)
  const username = computed(() => userInfo.value?.username || '')
  const balance = computed(() => userInfo.value?.balance || 0)

  /** 密码登录 */
  async function login(data: LoginFormDTO) {
    const res = await loginApi(data)
    saveLoginResult(res)
  }

  /** 验证码登录 */
  async function loginByCode(data: LoginByCodeDTO) {
    const res = await loginByCodeApi(data)
    saveLoginResult(res)
  }

  /** 保存登录结果到 state + sessionStorage */
  function saveLoginResult(res: UserLoginVO) {
    token.value = res.token
    userInfo.value = {
      id: res.userId,
      username: res.username,
      balance: res.balance,
    }
    sessionStorage.setItem('token', res.token)
    sessionStorage.setItem('user-info', JSON.stringify(userInfo.value))
  }

  /** 登出：先调后端接口使 token 失效，再清除本地状态 */
  async function logout() {
    try {
      await logoutApi()
    } catch {
      // 即使后端调用失败，也继续清除本地状态
    }
    clearState()
  }

  /** 清除本地状态（不做 API 调用） */
  function clearState() {
    token.value = ''
    userInfo.value = null
    sessionStorage.removeItem('token')
    sessionStorage.removeItem('user-info')
  }

  function setUserInfo(info: UserInfo) {
    userInfo.value = info
    sessionStorage.setItem('user-info', JSON.stringify(info))
  }

  return { token, userInfo, isLogin, username, balance, login, loginByCode, logout, setUserInfo }
})
