import adminRequest from '../admin'
import type { Item, PageResult, PageQuery } from '@/types'

/** 分页查询商品 */
export function getAdminProductPage(params: PageQuery & { key?: string }) {
  return adminRequest.get('/admin/product/list', { params }) as Promise<PageResult<Item>>
}

/** 商品详情 */
export function getProductDetail(id: number) {
  return adminRequest.get(`/admin/product/${id}`) as Promise<Item>
}

/** 新增商品 */
export function createProduct(data: Partial<Item>) {
  return adminRequest.post('/admin/product', data) as Promise<void>
}

/** 更新商品 */
export function updateProduct(id: number, data: Partial<Item>) {
  return adminRequest.put(`/admin/product/${id}`, data) as Promise<void>
}

/** 批量上下架 */
export function batchUpdatePublishStatus(ids: number[], publishStatus: number) {
  return adminRequest.post('/admin/product/publishStatus', null, { params: { ids, publishStatus } }) as Promise<void>
}

/** 批量删除商品 */
export function deleteProducts(ids: number[]) {
  return adminRequest.delete('/admin/product', { params: { ids } }) as Promise<void>
}

/** 调整库存 */
export function updateProductStock(id: number, stock: number) {
  return adminRequest.put(`/admin/product/stock/${id}`, null, { params: { stock } }) as Promise<void>
}
