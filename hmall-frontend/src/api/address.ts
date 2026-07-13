import request from './index'
import type { Address } from '@/types'

export function getAddressList(): Promise<Address[]> {
  return request.get('/addresses')
}

export function addAddress(data: Omit<Address, 'id' | 'userId'>): Promise<void> {
  return request.post('/addresses', data)
}

export function updateAddress(id: number, data: Omit<Address, 'id' | 'userId'>): Promise<void> {
  return request.put(`/addresses/${id}`, data)
}

export function deleteAddress(id: number): Promise<void> {
  return request.delete(`/addresses/${id}`)
}

export function setDefaultAddress(id: number): Promise<void> {
  return request.put(`/addresses/default/${id}`)
}
