import adminRequest from '../admin'
import type { AdminInfo, AdminLoginDTO, TokenVO } from '@/types/admin'

/** 管理员登录 */
export function adminLogin(data: AdminLoginDTO): Promise<TokenVO> {
  return adminRequest.post('/admin/login', data)
}

/** 管理员登出 */
export function adminLogout(): Promise<void> {
  return adminRequest.post('/admin/logout')
}

/** 获取当前管理员信息（含菜单和权限） */
export function getAdminInfo(): Promise<AdminInfo> {
  return adminRequest.get('/admin/info')
}

/** 刷新 token */
export function refreshToken(): Promise<TokenVO> {
  return adminRequest.get('/admin/refreshToken')
}
