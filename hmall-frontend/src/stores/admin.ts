import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { adminLogin, adminLogout, getAdminInfo } from '@/api/admin/auth'
import type { AdminInfo, AdminMenu, AdminLoginDTO } from '@/types/admin'

export const useAdminStore = defineStore('admin', () => {
  const adminToken = ref(sessionStorage.getItem('admin-token') || '')
  const adminInfo = ref<AdminInfo | null>(
    JSON.parse(sessionStorage.getItem('admin-info') || 'null')
  )
  const menus = ref<AdminMenu[]>([])
  const permissions = ref<string[]>([])

  const isAdminLogin = computed(() => !!adminToken.value)
  const username = computed(() => adminInfo.value?.username || '管理员')

  /** 登录：对接 admin-service 专属接口 */
  async function login(loginForm: AdminLoginDTO) {
    const res = await adminLogin(loginForm)
    adminToken.value = res.token
    sessionStorage.setItem('admin-token', res.token)
    // 登录后立即获取管理员信息（含菜单和权限）
    await fetchAdminInfo()
  }

  /** 获取管理员信息：菜单树 + 角色列表 + 权限编码 */
  async function fetchAdminInfo() {
    const info = await getAdminInfo()
    adminInfo.value = info
    menus.value = info.menus || []
    permissions.value = info.permissions || []
    sessionStorage.setItem('admin-info', JSON.stringify(info))
  }

  /** 登出 */
  async function logout() {
    try {
      await adminLogout()
    } catch { /* 忽略 */ }
    adminToken.value = ''
    adminInfo.value = null
    menus.value = []
    permissions.value = []
    sessionStorage.removeItem('admin-token')
    sessionStorage.removeItem('admin-info')
  }

  /** 检查路由权限 */
  function hasRoutePermission(path: string): boolean {
    // 超级管理员拥有全部权限
    if (permissions.value.includes('*')) return true
    return menus.value.some(m =>
      m.path === path || m.children?.some(c => c.path === path)
    )
  }

  /** 检查按钮级权限 */
  function hasPermission(code: string): boolean {
    if (permissions.value.includes('*')) return true
    return permissions.value.includes(code)
  }

  return {
    adminToken, adminInfo, menus, permissions,
    isAdminLogin, username,
    login, fetchAdminInfo, logout,
    hasRoutePermission, hasPermission,
  }
})
