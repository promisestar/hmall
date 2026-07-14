import adminRequest from '../admin'
import type { PageResult, PageQuery, OrderVO } from '@/types'
import type { OrderPageQuery } from '@/types/admin'

/** 分页查询订单 */
export function getAdminOrderPage(params: OrderPageQuery) {
  return adminRequest.get('/admin/order/list', { params }) as Promise<PageResult<OrderVO>>
}

/** 订单详情 */
export function getOrderDetail(id: string | number) {
  return adminRequest.get(`/admin/order/${id}`) as Promise<OrderVO>
}

/** 批量发货 */
export function batchDelivery(orderIds: (string | number)[]) {
  return adminRequest.post('/admin/order/delivery', orderIds) as Promise<void>
}

/** 批量关闭订单 */
export function batchCloseOrders(orderIds: (string | number)[]) {
  return adminRequest.post('/admin/order/close', orderIds) as Promise<void>
}

/** 修改备注 */
export function updateOrderNote(id: string | number, note: string, status?: number) {
  return adminRequest.post('/admin/order/note', null, { params: { id, note, status } }) as Promise<void>
}
