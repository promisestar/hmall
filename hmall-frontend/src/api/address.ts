import request from './index'
import type { Address } from '@/types'

export function getAddressList(): Promise<Address[]> {
  return request.get('/addresses')
}
