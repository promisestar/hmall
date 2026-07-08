import request from './index'
import type { PayApplyDTO, PayOrderFormDTO, PayOrderVO } from '@/types'

export function applyPayOrder(data: PayApplyDTO): Promise<string> {
  return request.post('/pay-orders', data, { responseType: 'text' })
}

export function tryPayOrderByBalance(id: string, data: PayOrderFormDTO): Promise<void> {
  return request.post(`/pay-orders/${id}`, data)
}

export function getPayOrderByBizOrderNo(bizOrderNo: string): Promise<PayOrderVO> {
  return request.get(`/pay-orders/biz/${bizOrderNo}`)
}

export function getPayOrderById(id: string): Promise<PayOrderVO> {
  return request.get(`/pay-orders/${id}`)
}
