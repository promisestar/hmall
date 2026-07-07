import request from './index'
import type { PayApplyDTO, PayOrderFormDTO, PayOrderVO } from '@/types'

export function applyPayOrder(data: PayApplyDTO): Promise<string> {
  return request.post('/pay-orders', data)
}

export function tryPayOrderByBalance(id: number, data: PayOrderFormDTO): Promise<void> {
  return request.post(`/pay-orders/${id}`, data)
}

export function getPayOrderByBizOrderNo(bizOrderNo: number): Promise<PayOrderVO> {
  return request.get(`/pay-orders/biz/${bizOrderNo}`)
}

export function getPayOrderById(id: number): Promise<PayOrderVO> {
  return request.get(`/pay-orders/${id}`)
}
