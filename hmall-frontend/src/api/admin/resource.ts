import adminRequest from '../admin'
import type { Resource, ResourceCategory } from '@/types/admin'
import type { PageResult, PageQuery } from '@/types'

/** 分页查询资源 */
export function getResourcePage(params: PageQuery & { keyword?: string; categoryId?: number }) {
  return adminRequest.get('/admin/resource/list', { params }) as Promise<PageResult<Resource>>
}

/** 查询全部资源 */
export function getAllResources() {
  return adminRequest.get('/admin/resource/listAll') as Promise<Resource[]>
}

/** 新增资源 */
export function createResource(data: Partial<Resource>) {
  return adminRequest.post('/admin/resource/create', data) as Promise<void>
}

/** 更新资源 */
export function updateResource(id: number, data: Partial<Resource>) {
  return adminRequest.post(`/admin/resource/update/${id}`, data) as Promise<void>
}

/** 删除资源 */
export function deleteResource(id: number) {
  return adminRequest.post(`/admin/resource/delete/${id}`) as Promise<void>
}

/** 查询资源分类列表 */
export function getResourceCategories() {
  return adminRequest.get('/admin/resource/category/listAll') as Promise<ResourceCategory[]>
}
