import request from './index'
import type { OrderFormDTO, OrderVO, PageResult, PageQuery } from '@/types'

export function createOrder(data: OrderFormDTO): Promise<string> {
  return request.post('/orders', data)
}

export function getOrderPage(params: PageQuery): Promise<PageResult<OrderVO>> {
  return request.get('/orders/page', { params })
}

export function getOrderById(id: string): Promise<OrderVO> {
  return request.get(`/orders/${id}`)
}
