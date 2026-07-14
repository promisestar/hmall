import adminRequest from '../admin'
import type { Role } from '@/types/admin'
import type { PageResult, PageQuery } from '@/types'

/** 分页查询角色 */
export function getRolePage(params: PageQuery & { keyword?: string }) {
  return adminRequest.get('/admin/role/list', { params }) as Promise<PageResult<Role>>
}

/** 查询全部角色 */
export function getAllRoles() {
  return adminRequest.get('/admin/role/listAll') as Promise<Role[]>
}

/** 新增角色 */
export function createRole(data: Partial<Role>) {
  return adminRequest.post('/admin/role/create', data) as Promise<void>
}

/** 更新角色 */
export function updateRole(id: number, data: Partial<Role>) {
  return adminRequest.post(`/admin/role/update/${id}`, data) as Promise<void>
}

/** 批量删除角色 */
export function deleteRoles(ids: number[]) {
  return adminRequest.post('/admin/role/delete', ids) as Promise<void>
}

/** 修改角色状态 */
export function updateRoleStatus(id: number, status: number) {
  return adminRequest.post(`/admin/role/updateStatus/${id}`, null, { params: { status } }) as Promise<void>
}

/** 获取角色的菜单 */
export function getRoleMenus(roleId: number) {
  return adminRequest.get(`/admin/role/listMenu/${roleId}`) as Promise<number[]>
}

/** 获取角色的资源 */
export function getRoleResources(roleId: number) {
  return adminRequest.get(`/admin/role/listResource/${roleId}`) as Promise<number[]>
}

/** 给角色分配菜单 */
export function allocRoleMenus(roleId: number, menuIds: number[]) {
  return adminRequest.post('/admin/role/allocMenu', null, { params: { roleId, menuIds } }) as Promise<void>
}

/** 给角色分配资源 */
export function allocRoleResources(roleId: number, resourceIds: number[]) {
  return adminRequest.post('/admin/role/allocResource', null, { params: { roleId, resourceIds } }) as Promise<void>
}
