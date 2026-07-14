import adminRequest from '../admin'
import type { Menu, AdminMenu } from '@/types/admin'

/** 获取菜单树 */
export function getMenuTree() {
  return adminRequest.get('/admin/menu/tree') as Promise<AdminMenu[]>
}

/** 新增菜单 */
export function createMenu(data: Partial<Menu>) {
  return adminRequest.post('/admin/menu/create', data) as Promise<void>
}

/** 更新菜单 */
export function updateMenu(id: number, data: Partial<Menu>) {
  return adminRequest.post(`/admin/menu/update/${id}`, data) as Promise<void>
}

/** 删除菜单 */
export function deleteMenu(id: number) {
  return adminRequest.post(`/admin/menu/delete/${id}`) as Promise<void>
}
