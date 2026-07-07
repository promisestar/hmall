import request from './index'
import type { CartItem, CartFormDTO } from '@/types'

export function getCartList(): Promise<CartItem[]> {
  return request.get('/carts')
}

export function addToCart(data: CartFormDTO): Promise<void> {
  return request.post('/carts', data)
}

export function updateCartNum(id: number, data: { num: number }): Promise<void> {
  return request.put(`/carts/${id}`, data)
}

export function deleteCartItem(id: number): Promise<void> {
  return request.delete(`/carts/${id}`)
}

export function deleteCartItems(ids: number[]): Promise<void> {
  return request.delete('/carts', { data: { ids } })
}
