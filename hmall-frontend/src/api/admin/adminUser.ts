import adminRequest from '../admin'
import type { AdminUser } from '@/types/admin'
import type { PageResult, PageQuery } from '@/types'

/** 分页查询管理员 */
export function getAdminUserPage(params: PageQuery & { keyword?: string }) {
  return adminRequest.get('/admin/admin/list', { params }) as Promise<PageResult<AdminUser>>
}

/** 管理员详情 */
export function getAdminUserDetail(id: number) {
  return adminRequest.get(`/admin/admin/${id}`) as Promise<AdminUser>
}

/** 新增管理员 */
export function createAdminUser(data: Partial<AdminUser>) {
  return adminRequest.post('/admin/admin', data) as Promise<void>
}

/** 更新管理员 */
export function updateAdminUser(id: number, data: Partial<AdminUser>) {
  return adminRequest.post(`/admin/admin/update/${id}`, data) as Promise<void>
}

/** 删除管理员 */
export function deleteAdminUser(id: number) {
  return adminRequest.post(`/admin/admin/delete/${id}`) as Promise<void>
}

/** 修改启用状态 */
export function updateAdminUserStatus(id: number, status: number) {
  return adminRequest.post(`/admin/admin/updateStatus/${id}`, null, { params: { status } }) as Promise<void>
}

/** 给管理员分配角色 */
export function allocAdminRoles(adminId: number, roleIds: number[]) {
  return adminRequest.post('/admin/admin/role/update', null, { params: { adminId, roleIds } }) as Promise<void>
}

/** 获取管理员的角色列表 */
export function getAdminRoles(adminId: number) {
  return adminRequest.get(`/admin/admin/role/${adminId}`) as Promise<number[]>
}
